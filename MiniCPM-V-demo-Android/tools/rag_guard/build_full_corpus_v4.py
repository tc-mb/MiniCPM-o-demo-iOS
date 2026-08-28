"""Construct schema-v2 Answerability and Groundedness rows from approved sources."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping, Sequence

from tools.rag_guard.dataset_schema_v2 import MAX_TEXT_CHARS, validate_v2_row
from tools.rag_guard.dataset_correctness_v4 import (
    filter_orphaned_contradiction_families,
    filter_protected_input_budget,
)
from tools.rag_guard.prepare_training_v4 import audit_training_inputs
from tools.rag_guard.mutations.amount_date import mutate_single_number
from tools.rag_guard.mutations.entity_scope import mutate_single_scope
from tools.rag_guard.mutations.unit_scope import mutate_single_unit
from tools.rag_guard.qa_repairs_v4_2 import (
    build_visible_evidence_window,
    choose_type_matched_distractor,
    classify_numeric_hard_type,
)
from tools.rag_guard.select_balanced_corpus_v4 import select_balanced_groundedness
from tools.rag_guard.source_loaders_v4 import (
    ContractNliRecord,
    HoVerEvidenceStore,
    HoVerRecord,
    load_contract_nli_zip,
    load_hover_json,
)


_SPACE = re.compile(r"\s+")
_EMAIL = re.compile(r"[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,190}\.[A-Za-z]{2,24}")
_PHONE = re.compile(r"(?<!\d)(?:(?:\+?86[- ]?)?1[3-9]\d{9}|\+\d(?:[\d ()-]{6,}\d))(?!\d)")
_IDENTITY = re.compile(r"(?<!\d)\d{17}[0-9Xx](?!\d)")
TRANSFORM_VERSION = "rag-guard-v4.2-full-corpus-1"
ANSWERABILITY_QUOTAS = {"SUPPORTED": 48_000, "PARTIAL": 30_000, "UNSUPPORTED": 42_000}
ANSWERABILITY_LANGUAGE_QUOTAS = {
    # CMRC 2018 is the only approved Chinese QA source in this build.  After
    # the v4.2 visible-evidence gate, its usable supply is ~685 rows per
    # answerability label; keep a conservative 600-row Chinese cell and
    # replace the unavailable balance with same-label English rows.
    ("SUPPORTED", "zh"): 600,
    ("SUPPORTED", "en"): 47_400,
    ("PARTIAL", "zh"): 600,
    ("PARTIAL", "en"): 29_400,
    ("UNSUPPORTED", "zh"): 600,
    ("UNSUPPORTED", "en"): 41_400,
}
GROUNDEDNESS_QUOTAS = {
    "GROUNDED": 45_000,
    "PARTIAL": 37_500,
    "UNSUPPORTED": 30_000,
    "CONTRADICTED": 37_500,
}
GROUNDEDNESS_CONTRADICTION_QUOTAS = {
    # Chinese hard-negative cells are bounded by the CMRC 2018 source supply.
    # The English cells absorb the unavailable Chinese rows by hard type,
    # preserving the frozen 37,500 contradiction total without duplication.
    ("NEGATION_FLIP", "zh"): 600,
    ("NEGATION_FLIP", "en"): 9_400,
    ("WRONG_ENTITY", "zh"): 450,
    ("WRONG_ENTITY", "en"): 9_460,
    ("WRONG_AMOUNT", "zh"): 40,
    ("WRONG_AMOUNT", "en"): 4_500,
    ("WRONG_DATE", "zh"): 70,
    ("WRONG_DATE", "en"): 3_930,
    ("WRONG_UNIT", "zh"): 10,
    ("WRONG_UNIT", "en"): 490,
    ("MULTI_HOP_CONTRADICTION", "en"): 7_500,
    ("SCOPE_FLIP", "en"): 350,
    ("CONTRACT_CONTRADICTION", "en"): 700,
}


@dataclass(frozen=True)
class GeneratedCorpus:
    answerability: list[dict[str, object]]
    groundedness: list[dict[str, object]]


def select_by_label_quotas(
    rows: Sequence[dict[str, object]], quotas: Mapping[str, int], *, seed: str
) -> list[dict[str, object]]:
    if not seed or any(not isinstance(value, int) or value < 0 for value in quotas.values()):
        raise ValueError("seed and non-negative quotas are required")
    grouped: dict[str, list[dict[str, object]]] = defaultdict(list)
    for row in rows:
        label = row.get("label")
        row_id = row.get("id")
        if not isinstance(label, str) or not isinstance(row_id, str):
            raise ValueError("quota rows require string id and label")
        if label in quotas:
            grouped[label].append(row)
    selected: list[dict[str, object]] = []
    for label in sorted(quotas):
        ranked = sorted(
            grouped[label],
            key=lambda row: hashlib.sha256(
                f"{seed}\0{row['id']}".encode("utf-8")
            ).hexdigest(),
        )
        selected.extend(ranked[: quotas[label]])
    return sorted(selected, key=lambda row: str(row["id"]))


def select_by_label_language_quotas(
    rows: Sequence[dict[str, object]],
    quotas: Mapping[tuple[str, str], int],
    *,
    seed: str,
) -> list[dict[str, object]]:
    if not seed or any(
        not isinstance(key, tuple)
        or len(key) != 2
        or any(not isinstance(part, str) or not part for part in key)
        or not isinstance(value, int)
        or isinstance(value, bool)
        or value < 0
        for key, value in quotas.items()
    ):
        raise ValueError("seed and non-negative label/language quotas are required")
    grouped: dict[tuple[str, str], list[dict[str, object]]] = defaultdict(list)
    for row in rows:
        label = row.get("label")
        language = row.get("language")
        row_id = row.get("id")
        if not isinstance(label, str) or not isinstance(language, str) or not isinstance(row_id, str):
            raise ValueError("quota rows require string id, label, and language")
        key = (label, language)
        if key in quotas:
            grouped[key].append(row)
    selected: list[dict[str, object]] = []
    for key in sorted(quotas):
        ranked = sorted(
            grouped[key],
            key=lambda row: hashlib.sha256(f"{seed}\0{row['id']}".encode("utf-8")).hexdigest(),
        )
        if len(ranked) < quotas[key]:
            raise ValueError(
                f"candidate pool does not satisfy quota for label={key[0]} language={key[1]}"
            )
        selected.extend(ranked[: quotas[key]])
    return sorted(selected, key=lambda row: str(row["id"]))


def write_jsonl_atomic(path: Path, rows: Sequence[dict[str, object]]) -> None:
    if not rows:
        raise ValueError("refusing to write an empty corpus")
    resolved = path.resolve()
    resolved.parent.mkdir(parents=True, exist_ok=True)
    temporary = resolved.with_suffix(resolved.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as output:
        for row in rows:
            validate_v2_row(row)
            output.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    temporary.replace(resolved)


def _write_json_atomic(path: Path, value: object) -> None:
    resolved = path.resolve()
    resolved.parent.mkdir(parents=True, exist_ok=True)
    temporary = resolved.with_suffix(resolved.suffix + ".tmp")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(resolved)


def _summary(rows: Sequence[dict[str, object]]) -> dict[str, object]:
    return {
        "rows": len(rows),
        "labels": dict(sorted(Counter(str(row["label"]) for row in rows).items())),
        "sources": dict(sorted(Counter(str(row["source_dataset"]) for row in rows).items())),
        "languages": dict(sorted(Counter(str(row["language"]) for row in rows).items())),
    }


def _digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.resolve(strict=True).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _merge(target: GeneratedCorpus, source: GeneratedCorpus) -> GeneratedCorpus:
    target.answerability.extend(source.answerability)
    target.groundedness.extend(source.groundedness)
    return target


def build_all_sources(
    raw_root: Path,
    *,
    generator_commit: str,
    limit_per_source: int | None = None,
    tokenizer: object | None = None,
    max_length: int = 256,
) -> GeneratedCorpus:
    root = raw_root.resolve(strict=True)
    if limit_per_source is not None and limit_per_source < 1:
        raise ValueError("limit_per_source must be positive")
    result = GeneratedCorpus([], [])
    qa_sources = (
        (root / "squad_2" / "train-v2.0.json", "SQuAD 2.0", "2.0", "CC BY-SA 4.0", "en"),
        (root / "squad_2" / "dev-v2.0.json", "SQuAD 2.0", "2.0", "CC BY-SA 4.0", "en"),
        (root / "cmrc_2018" / "cmrc2018_train.json", "CMRC 2018", "2018", "CC BY-SA 4.0", "zh"),
        (root / "cmrc_2018" / "cmrc2018_dev.json", "CMRC 2018", "2018", "CC BY-SA 4.0", "zh"),
    )
    for path, dataset, version, license_name, language in qa_sources:
        _merge(
            result,
            build_qa_corpus(
                path,
                source_dataset=dataset,
                source_version=version,
                source_license=license_name,
                language=language,
                raw_sha256=_file_sha256(path),
                generator_commit=generator_commit,
                limit=limit_per_source,
                tokenizer=tokenizer,
                max_length=max_length,
            ),
        )
    contract_path = root / "contract_nli" / "contract-nli.zip"
    contract_records = load_contract_nli_zip(contract_path)
    if limit_per_source is not None:
        contract_records = contract_records[:limit_per_source]
    _merge(
        result,
        build_contract_corpus(
            contract_records,
            raw_sha256=_file_sha256(contract_path),
            generator_commit=generator_commit,
        ),
    )
    hover_train = root / "hover" / "hover_train_release_v1.1.json"
    hover_dev = root / "hover" / "hover_dev_release_v1.1.json"
    hover_database = root / "hover" / "wiki_wo_links.db"
    hover_records = load_hover_json(hover_train, split="train") + load_hover_json(hover_dev, split="dev")
    combined_hover_hash = _digest(
        "\n".join((_file_sha256(hover_train), _file_sha256(hover_dev), _file_sha256(hover_database)))
    )
    with HoVerEvidenceStore(hover_database) as store:
        _merge(
            result,
            build_hover_corpus(
                hover_records,
                store,
                raw_sha256=combined_hover_hash,
                generator_commit=generator_commit,
                limit=limit_per_source,
            ),
        )
    return result


def _clean(value: object, *, limit: int = MAX_TEXT_CHARS) -> str:
    if not isinstance(value, str):
        return ""
    text = _SPACE.sub(" ", value.replace("\x00", " ")).strip()
    text = _EMAIL.sub("[EMAIL]", text)
    text = _IDENTITY.sub("[IDENTITY]", text)
    text = _PHONE.sub("[PHONE]", text)
    return text[:limit].strip()


def _usable_answer(value: str) -> bool:
    """Reject punctuation-only extractive answers that cannot be grounded."""
    return bool(value) and any(character.isalnum() for character in value)


def _evidence_entries(items: Sequence[tuple[str, str]]) -> list[dict[str, str]]:
    result: list[dict[str, str]] = []
    remaining = MAX_TEXT_CHARS
    for index, (document_id, text) in enumerate(items, start=1):
        clean = _clean(text, limit=remaining)
        if not clean:
            continue
        result.append({"source_id": f"S{index}", "document_id": document_id, "text": clean})
        remaining -= len(clean)
        if remaining <= 0:
            break
    if not result:
        raise ValueError("row requires non-empty evidence")
    return result


def _make_row(
    *,
    task: str,
    label: str,
    question: str,
    evidence: Sequence[tuple[str, str]],
    answer: str,
    claim_supports: Sequence[tuple[str, str]],
    source_dataset: str,
    source_version: str,
    source_license: str,
    source_record_id: str,
    source_split: str,
    document_id: str,
    mutation_family_id: str,
    hard_negative_type: str,
    language: str,
    domain: str,
    raw_sha256: str,
    generator_commit: str,
) -> dict[str, object]:
    evidence_rows = _evidence_entries(evidence)
    source_ids = [item["source_id"] for item in evidence_rows]
    claims = [
        {
            "text": _clean(text),
            "support": support,
            "source_ids": source_ids,
            "material": True,
        }
        for text, support in claim_supports
    ]
    stable_id = _digest(
        "\0".join((task, source_dataset, source_record_id, label, mutation_family_id))
    )[:32]
    row: dict[str, object] = {
        "id": f"v4-{task}-{stable_id}",
        "task": task,
        "label": label,
        "question": _clean(question),
        "evidence": evidence_rows,
        "answer": _clean(answer),
        "atomic_claims": claims,
        "language": language,
        "domain": domain,
        "hard_negative_type": hard_negative_type,
        "mutation_family_id": mutation_family_id,
        "document_id": document_id,
        "conversation_id": "",
        "split": "train",
        "source_split": source_split,
        "distribution": "public_licensed",
        "redaction_status": "public_source_redacted",
        "source_dataset": source_dataset,
        "source_version": source_version,
        "source_record_id": source_record_id,
        "source_license": source_license,
        "license_status": "approved",
        "provenance": {
            "raw_sha256": raw_sha256,
            "transform_version": TRANSFORM_VERSION,
            "generator_commit": generator_commit,
        },
    }
    validate_v2_row(row)
    return row


def _question_for_claim(claim: str, language: str = "en") -> str:
    if language == "zh":
        return f"根据证据判断以下说法是否成立：{claim}"
    return f"Determine from the evidence whether this claim is true: {claim}"


def _derive_hover_contradiction(claim: str) -> str:
    """Create a contradiction from a supported claim without trusting HoVer's merged negative label."""
    for mutation in (mutate_single_scope, mutate_single_number, mutate_single_unit):
        candidate = mutation(claim)
        if candidate is not None and candidate != claim:
            return candidate
    stripped = claim.strip()
    if stripped.endswith("."):
        stripped = stripped[:-1]
    return f"It is not true that {stripped}."


def build_contract_corpus(
    records: Sequence[ContractNliRecord],
    *,
    raw_sha256: str,
    generator_commit: str,
) -> GeneratedCorpus:
    answerability: list[dict[str, object]] = []
    groundedness: list[dict[str, object]] = []
    by_document: dict[str, list[ContractNliRecord]] = defaultdict(list)
    for record in records:
        by_document[record.document_id].append(record)
        family = "contract-" + _digest(f"{record.split}\0{record.document_id}\0{record.hypothesis_id}")[:24]
        evidence = [(record.document_id, record.evidence)]
        answer_label = "UNSUPPORTED" if record.choice == "NotMentioned" else "SUPPORTED"
        answerability.append(
            _make_row(
                task="answerability",
                label=answer_label,
                question=_question_for_claim(record.hypothesis),
                evidence=evidence,
                answer="",
                claim_supports=(),
                source_dataset="ContractNLI",
                source_version="1.0",
                source_license="CC BY 4.0",
                source_record_id=f"{record.split}:{record.document_id}:{record.hypothesis_id}:a",
                source_split=record.split,
                document_id=record.document_id,
                mutation_family_id=family,
                hard_negative_type="NONE" if answer_label == "SUPPORTED" else "NOT_MENTIONED",
                language="en",
                domain="contract",
                raw_sha256=raw_sha256,
                generator_commit=generator_commit,
            )
        )
        ground_label, support, hard_type = {
            "Entailment": ("GROUNDED", "entailed", "NONE"),
            "NotMentioned": ("UNSUPPORTED", "missing", "NOT_MENTIONED"),
            "Contradiction": ("CONTRADICTED", "contradicted", "CONTRACT_CONTRADICTION"),
        }[record.choice]
        groundedness.append(
            _make_row(
                task="groundedness",
                label=ground_label,
                question=_question_for_claim(record.hypothesis),
                evidence=evidence,
                answer=record.hypothesis,
                claim_supports=((record.hypothesis, support),),
                source_dataset="ContractNLI",
                source_version="1.0",
                source_license="CC BY 4.0",
                source_record_id=f"{record.split}:{record.document_id}:{record.hypothesis_id}:g",
                source_split=record.split,
                document_id=record.document_id,
                mutation_family_id=family,
                hard_negative_type=hard_type,
                language="en",
                domain="contract",
                raw_sha256=raw_sha256,
                generator_commit=generator_commit,
            )
        )
        if record.choice == "Contradiction" and record.evidence != record.hypothesis:
            groundedness.append(
                _make_row(
                    task="groundedness",
                    label="GROUNDED",
                    question=_question_for_claim(record.evidence),
                    evidence=evidence,
                    answer=record.evidence,
                    claim_supports=((record.evidence, "entailed"),),
                    source_dataset="ContractNLI",
                    source_version="1.0",
                    source_license="CC BY 4.0",
                    source_record_id=f"{record.split}:{record.document_id}:{record.hypothesis_id}:evidence-sibling:g",
                    source_split=record.split,
                    document_id=record.document_id,
                    mutation_family_id=family,
                    hard_negative_type="NONE",
                    language="en",
                    domain="contract",
                    raw_sha256=raw_sha256,
                    generator_commit=generator_commit,
                )
            )
        scope_contradiction = mutate_single_scope(record.hypothesis)
        if record.choice == "Entailment" and scope_contradiction is not None:
            groundedness.append(
                _make_row(
                    task="groundedness",
                    label="CONTRADICTED",
                    question=_question_for_claim(scope_contradiction),
                    evidence=evidence,
                    answer=scope_contradiction,
                    claim_supports=((scope_contradiction, "contradicted"),),
                    source_dataset="ContractNLI",
                    source_version="1.0",
                    source_license="CC BY 4.0",
                    source_record_id=f"{record.split}:{record.document_id}:{record.hypothesis_id}:scope:g",
                    source_split=record.split,
                    document_id=record.document_id,
                    mutation_family_id=family,
                    hard_negative_type="SCOPE_FLIP",
                    language="en",
                    domain="contract",
                    raw_sha256=raw_sha256,
                    generator_commit=generator_commit,
                )
            )
    for document_id, group in by_document.items():
        entailed = next((record for record in group if record.choice == "Entailment"), None)
        missing = next((record for record in group if record.choice == "NotMentioned"), None)
        if entailed is None or missing is None:
            continue
        family = "contract-pair-" + _digest(f"{entailed.split}\0{document_id}")[:24]
        combined_question = (
            f"Determine both claims from the evidence: {entailed.hypothesis} Also: {missing.hypothesis}"
        )
        evidence = [(document_id, entailed.evidence)]
        answerability.append(
            _make_row(
                task="answerability", label="PARTIAL", question=combined_question, evidence=evidence,
                answer="", claim_supports=(), source_dataset="ContractNLI", source_version="1.0",
                source_license="CC BY 4.0", source_record_id=f"{entailed.split}:{document_id}:partial:a",
                source_split=entailed.split, document_id=document_id, mutation_family_id=family,
                hard_negative_type="MISSING_FIELD", language="en", domain="contract",
                raw_sha256=raw_sha256, generator_commit=generator_commit,
            )
        )
        groundedness.append(
            _make_row(
                task="groundedness", label="PARTIAL", question=combined_question, evidence=evidence,
                answer=f"{entailed.hypothesis} {missing.hypothesis}",
                claim_supports=((entailed.hypothesis, "entailed"), (missing.hypothesis, "missing")),
                source_dataset="ContractNLI", source_version="1.0", source_license="CC BY 4.0",
                source_record_id=f"{entailed.split}:{document_id}:partial:g", source_split=entailed.split,
                document_id=document_id, mutation_family_id=family, hard_negative_type="MISSING_FIELD",
                language="en", domain="contract", raw_sha256=raw_sha256,
                generator_commit=generator_commit,
            )
        )
    return GeneratedCorpus(answerability, groundedness)


def _iter_qa(path: Path) -> Iterable[tuple[str, str, str, str, list[Mapping[str, object]]]]:
    value = json.loads(path.resolve(strict=True).read_text(encoding="utf-8"))
    if not isinstance(value, dict) or not isinstance(value.get("data"), list):
        raise ValueError("invalid QA source")
    for article_index, article in enumerate(value["data"]):
        if not isinstance(article, dict) or not isinstance(article.get("paragraphs"), list):
            raise ValueError("invalid QA article")
        title = str(article.get("title") or f"article-{article_index}")
        for paragraph_index, paragraph in enumerate(article["paragraphs"]):
            if not isinstance(paragraph, dict) or not isinstance(paragraph.get("qas"), list):
                raise ValueError("invalid QA paragraph")
            context = _clean(paragraph.get("context"))
            if context:
                yield title, str(paragraph_index), context, str(value.get("version") or "unknown"), paragraph["qas"]


def build_qa_corpus(
    path: Path,
    *,
    source_dataset: str,
    source_version: str,
    source_license: str,
    language: str,
    raw_sha256: str,
    generator_commit: str,
    limit: int | None = None,
    tokenizer: object | None = None,
    max_length: int = 256,
) -> GeneratedCorpus:
    answerability: list[dict[str, object]] = []
    groundedness: list[dict[str, object]] = []
    produced_questions = 0
    paragraphs = list(_iter_qa(path))
    natural_questions: list[tuple[str, str, str]] = []
    for title, paragraph_index, _context, _raw_version, qas in paragraphs:
        document_id = f"{source_dataset}:{title}:{paragraph_index}"
        for qa in qas:
            if not isinstance(qa, dict) or bool(qa.get("is_impossible")):
                continue
            answers = qa.get("answers")
            if not isinstance(answers, list) or not answers or not isinstance(answers[0], dict):
                continue
            candidate_question = _clean(qa.get("question"))
            candidate_answer = _clean(answers[0].get("text"))
            if candidate_question and _usable_answer(candidate_answer):
                natural_questions.append((document_id, candidate_question, candidate_answer))
    for title, paragraph_index, context, raw_version, qas in paragraphs:
        document_id = f"{source_dataset}:{title}:{paragraph_index}"
        answer_pool: list[str] = []
        for candidate_qa in qas:
            if not isinstance(candidate_qa, dict) or bool(candidate_qa.get("is_impossible")):
                continue
            candidate_answers = candidate_qa.get("answers")
            if not isinstance(candidate_answers, list) or not candidate_answers:
                continue
            candidate = _clean(
                candidate_answers[0].get("text") if isinstance(candidate_answers[0], dict) else ""
            )
            if _usable_answer(candidate) and candidate not in answer_pool:
                answer_pool.append(candidate)
        impossible_questions = [
            _clean(qa.get("question")) for qa in qas
            if isinstance(qa, dict) and (bool(qa.get("is_impossible")) or not qa.get("answers"))
        ]
        for question_index, qa in enumerate(qas):
            if limit is not None and produced_questions >= limit:
                return GeneratedCorpus(answerability, groundedness)
            if not isinstance(qa, dict):
                raise ValueError("invalid QA row")
            question = _clean(qa.get("question"))
            if not question:
                continue
            record_id = str(qa.get("id") or f"{title}:{paragraph_index}:{question_index}")
            impossible = bool(qa.get("is_impossible")) or not qa.get("answers")
            family = "qa-" + _digest(f"{source_dataset}\0{record_id}\0{document_id}")[:24]
            if impossible:
                plausible = qa.get("plausible_answers")
                plausible_answer = _clean(
                    plausible[0].get("text")
                    if isinstance(plausible, list) and plausible and isinstance(plausible[0], dict)
                    else ""
                )
                evidence_text = context
                if tokenizer is not None:
                    window = build_visible_evidence_window(
                        context,
                        required_texts=(plausible_answer,) if plausible_answer else (),
                        protected_text=f"query: {question}",
                        tokenizer=tokenizer,
                        max_length=max_length,
                        evidence_prefix="evidence [S1]: ",
                    )
                    if window is None:
                        continue
                    evidence_text = window
                answerability.append(
                    _make_row(
                        task="answerability", label="UNSUPPORTED", question=question,
                        evidence=((document_id, evidence_text),), answer="", claim_supports=(),
                        source_dataset=source_dataset, source_version=source_version or raw_version,
                        source_license=source_license, source_record_id=f"{record_id}:a",
                        source_split="source", document_id=document_id, mutation_family_id=family,
                        hard_negative_type="ADVERSARIAL_UNANSWERABLE", language=language,
                        domain="general_qa", raw_sha256=raw_sha256, generator_commit=generator_commit,
                    )
                )
                produced_questions += 1
                continue
            answers = qa.get("answers")
            if not isinstance(answers, list) or not answers:
                raise ValueError("answerable QA row has no answers")
            answer = _clean(answers[0].get("text") if isinstance(answers[0], dict) else "")
            if not _usable_answer(answer):
                continue
            wrong_answer = choose_type_matched_distractor(answer, answer_pool, language=language)
            numeric_answer = mutate_single_number(answer)
            unit_answer = mutate_single_unit(answer)
            prefix = "答案是" if language == "zh" else "The answer is"
            grounded_answer = f"{prefix}{answer}。" if language == "zh" else f"{prefix} {answer}."
            contradicted = f"答案不是{answer}。" if language == "zh" else f"The answer is not {answer}."
            relation_candidate = (
                (f"答案是{wrong_answer}。" if language == "zh" else f"The answer is {wrong_answer}.")
                if wrong_answer is not None
                else None
            )
            numeric_candidate = (
                (f"答案是{numeric_answer}。" if language == "zh" else f"The answer is {numeric_answer}.")
                if numeric_answer is not None
                else None
            )
            unit_candidate = (
                (f"答案是{unit_answer}。" if language == "zh" else f"The answer is {unit_answer}.")
                if unit_answer is not None
                else None
            )
            missing_question = (
                impossible_questions[question_index % len(impossible_questions)]
                if impossible_questions
                else next(
                    (
                        candidate_question
                        for candidate_document, candidate_question, candidate_answer in natural_questions
                        if candidate_document != document_id
                        and candidate_question != question
                        and candidate_answer.casefold() not in context.casefold()
                    ),
                    None,
                )
            )
            if missing_question is None and tokenizer is None:
                missing_code = _digest(f"{family}\0missing-question")[:12].upper()
                missing_question = (
                    f"相关参考编号{missing_code}的值是什么？"
                    if language == "zh"
                    else f"What is the value of related reference code {missing_code}?"
                )
            partial_question = (
                (
                    f"{question.rstrip('？?')}，另外，{missing_question}"
                    if language == "zh"
                    else f"{question.rstrip('?')} Also, {missing_question}"
                )
                if missing_question is not None
                else None
            )
            neutral_code = _digest(f"{family}\0neutral")[:12].upper()
            missing_claim = (
                f"相关参考编号为{neutral_code}。"
                if language == "zh"
                else f"The related reference code is {neutral_code}."
            )
            unsupported = (
                f"答案是编号{neutral_code}。"
                if language == "zh"
                else f"The answer is reference {neutral_code}."
            )
            grounded_partial = f"{grounded_answer} {missing_claim}"
            protected_texts = [
                f"query: {question}",
                *(
                    [f"query: {partial_question}", f"query: {missing_question}"]
                    if partial_question is not None and missing_question is not None
                    else []
                ),
                *[
                    f"query: {question}\nanswer: {value}"
                    for value in (
                        grounded_answer,
                        grounded_partial,
                        unsupported,
                        contradicted,
                        relation_candidate,
                        numeric_candidate,
                        unit_candidate,
                    )
                    if value is not None
                ],
            ]
            evidence_text = context
            if tokenizer is not None:
                protected_text = max(
                    protected_texts,
                    key=lambda value: len(
                        tokenizer(
                            value,
                            "",
                            add_special_tokens=True,
                            truncation=False,
                            padding=False,
                        )["input_ids"]
                    ),
                )
                window = build_visible_evidence_window(
                    context,
                    required_texts=tuple(
                        value for value in (answer, wrong_answer) if value is not None
                    ),
                    protected_text=protected_text,
                    tokenizer=tokenizer,
                    max_length=max_length,
                    evidence_prefix="evidence [S1]: ",
                )
                if window is None and wrong_answer is not None and relation_candidate is not None:
                    relation_protected = f"query: {question}\nanswer: {relation_candidate}"
                    protected_texts = [
                        value for value in protected_texts if value != relation_protected
                    ]
                    protected_text = max(
                        protected_texts,
                        key=lambda value: len(
                            tokenizer(
                                value,
                                "",
                                add_special_tokens=True,
                                truncation=False,
                                padding=False,
                            )["input_ids"]
                        ),
                    )
                    window = build_visible_evidence_window(
                        context,
                        required_texts=(answer,),
                        protected_text=protected_text,
                        tokenizer=tokenizer,
                        max_length=max_length,
                        evidence_prefix="evidence [S1]: ",
                    )
                    wrong_answer = None
                    relation_candidate = None
                if window is None:
                    continue
                evidence_text = window
            answerability.append(
                _make_row(
                    task="answerability", label="SUPPORTED", question=question,
                    evidence=((document_id, evidence_text),), answer="", claim_supports=(),
                    source_dataset=source_dataset, source_version=source_version or raw_version,
                    source_license=source_license, source_record_id=f"{record_id}:a",
                    source_split="source", document_id=document_id, mutation_family_id=family,
                    hard_negative_type="NONE", language=language, domain="general_qa",
                    raw_sha256=raw_sha256, generator_commit=generator_commit,
                )
            )
            if missing_question is not None:
                assert partial_question is not None
                for derived_label, derived_question, hard_type, suffix in (
                    ("PARTIAL", partial_question, "MISSING_FIELD", "partial"),
                    ("UNSUPPORTED", missing_question, "ADVERSARIAL_UNANSWERABLE", "unsupported"),
                ):
                    answerability.append(
                        _make_row(
                            task="answerability", label=derived_label, question=derived_question,
                            evidence=((document_id, evidence_text),), answer="", claim_supports=(),
                            source_dataset=source_dataset, source_version=source_version or raw_version,
                            source_license=source_license, source_record_id=f"{record_id}:{suffix}:a",
                            source_split="source", document_id=document_id, mutation_family_id=family,
                            hard_negative_type=hard_type, language=language, domain="general_qa",
                            raw_sha256=raw_sha256, generator_commit=generator_commit,
                        )
                    )
            family_rows: list[tuple[str, str, tuple[tuple[str, str], ...], str, str]] = [
                ("GROUNDED", grounded_answer, ((grounded_answer, "entailed"),), "NONE", "grounded"),
                ("PARTIAL", grounded_partial, ((grounded_answer, "entailed"), (missing_claim, "missing")), "MISSING_FIELD", "partial"),
                ("UNSUPPORTED", unsupported, ((unsupported, "missing"),), "UNRELATED_ANSWER", "unsupported"),
                ("CONTRADICTED", contradicted, ((contradicted, "contradicted"),), "NEGATION_FLIP", "contradicted"),
            ]
            contradiction_candidates: list[tuple[str, str, str]] = []
            if relation_candidate is not None:
                contradiction_candidates.append((relation_candidate, "WRONG_ENTITY", "contradicted-entity"))
            if numeric_candidate is not None:
                contradiction_candidates.append((
                    numeric_candidate,
                    classify_numeric_hard_type(answer, language),
                    "contradicted-number",
                ))
            if unit_candidate is not None:
                contradiction_candidates.append((unit_candidate, "WRONG_UNIT", "contradicted-unit"))
            seen_candidates = {contradicted}
            for candidate, hard_type, suffix in contradiction_candidates:
                if candidate in seen_candidates:
                    continue
                seen_candidates.add(candidate)
                family_rows.append(
                    ("CONTRADICTED", candidate, ((candidate, "contradicted"),), hard_type, suffix)
                )
            for label, candidate, claims, hard_type, suffix in family_rows:
                groundedness.append(
                    _make_row(
                        task="groundedness", label=label, question=question,
                        evidence=((document_id, evidence_text),), answer=candidate, claim_supports=claims,
                        source_dataset=source_dataset, source_version=source_version or raw_version,
                        source_license=source_license, source_record_id=f"{record_id}:{suffix}:g",
                        source_split="source", document_id=document_id, mutation_family_id=family,
                        hard_negative_type=hard_type, language=language, domain="general_qa",
                        raw_sha256=raw_sha256, generator_commit=generator_commit,
                    )
                )
            produced_questions += 1
    return GeneratedCorpus(answerability, groundedness)


def build_hover_corpus(
    records: Sequence[HoVerRecord],
    store: HoVerEvidenceStore,
    *,
    raw_sha256: str,
    generator_commit: str,
    limit: int | None = None,
) -> GeneratedCorpus:
    answerability: list[dict[str, object]] = []
    groundedness: list[dict[str, object]] = []
    groups: dict[str, list[HoVerRecord]] = defaultdict(list)
    for record in records:
        groups[record.hpqa_id].append(record)
    group_items = sorted(groups.items())
    processed = 0
    for group_index, (hpqa_id, group) in enumerate(group_items):
        supported = [record for record in group if record.label == "SUPPORTED"]
        if not supported:
            continue
        unrelated_group = group_items[(group_index + 1) % len(group_items)][1]
        unrelated_titles = list(dict.fromkeys(fact[0] for record in unrelated_group for fact in record.supporting_facts))
        unrelated_evidence = [
            (f"hover-wiki:{title}", store.get(title)) for title in unrelated_titles[:1]
        ]
        for positive in supported:
            if limit is not None and processed >= limit:
                return GeneratedCorpus(answerability, groundedness)
            titles = list(dict.fromkeys(title for title, _index in positive.supporting_facts))
            evidence = [(f"hover-wiki:{title}", store.get(title)) for title in titles]
            family = "hover-" + _digest(f"{hpqa_id}\0{positive.uid}")[:24]
            question = _question_for_claim(positive.claim)
            document_id = f"hover:{hpqa_id}"
            answerability.append(
                _make_row(
                    task="answerability", label="SUPPORTED", question=question, evidence=evidence,
                    answer="", claim_supports=(), source_dataset="HoVer", source_version="1.1",
                    source_license="CC BY-SA 4.0", source_record_id=f"{positive.uid}:supported:a",
                    source_split=positive.split, document_id=document_id, mutation_family_id=family,
                    hard_negative_type="NONE", language="en", domain="fact_verification",
                    raw_sha256=raw_sha256, generator_commit=generator_commit,
                )
            )
            groundedness.append(
                _make_row(
                    task="groundedness", label="GROUNDED", question=question, evidence=evidence,
                    answer=positive.claim, claim_supports=((positive.claim, "entailed"),),
                    source_dataset="HoVer", source_version="1.1", source_license="CC BY-SA 4.0",
                    source_record_id=f"{positive.uid}:grounded:g", source_split=positive.split,
                    document_id=document_id, mutation_family_id=family, hard_negative_type="NONE",
                    language="en", domain="fact_verification", raw_sha256=raw_sha256,
                    generator_commit=generator_commit,
                )
            )
            if len(evidence) > 1:
                partial_evidence = evidence[:-1]
                answerability.append(
                    _make_row(
                        task="answerability", label="PARTIAL", question=question,
                        evidence=partial_evidence, answer="", claim_supports=(), source_dataset="HoVer",
                        source_version="1.1", source_license="CC BY-SA 4.0",
                        source_record_id=f"{positive.uid}:partial:a", source_split=positive.split,
                        document_id=document_id, mutation_family_id=family, hard_negative_type="MISSING_HOP",
                        language="en", domain="fact_verification", raw_sha256=raw_sha256,
                        generator_commit=generator_commit,
                    )
                )
                groundedness.append(
                    _make_row(
                        task="groundedness", label="PARTIAL", question=question,
                        evidence=partial_evidence, answer=positive.claim,
                        claim_supports=((positive.claim, "entailed"), ("A required evidence hop is missing.", "missing")),
                        source_dataset="HoVer", source_version="1.1", source_license="CC BY-SA 4.0",
                        source_record_id=f"{positive.uid}:partial:g", source_split=positive.split,
                        document_id=document_id, mutation_family_id=family, hard_negative_type="MISSING_HOP",
                        language="en", domain="fact_verification", raw_sha256=raw_sha256,
                        generator_commit=generator_commit,
                    )
                )
            if unrelated_evidence:
                answerability.append(
                    _make_row(
                        task="answerability", label="UNSUPPORTED", question=question,
                        evidence=unrelated_evidence, answer="", claim_supports=(), source_dataset="HoVer",
                        source_version="1.1", source_license="CC BY-SA 4.0",
                        source_record_id=f"{positive.uid}:unsupported:a", source_split=positive.split,
                        document_id=document_id, mutation_family_id=family,
                        hard_negative_type="SIMILAR_BUT_NO_ANSWER", language="en",
                        domain="fact_verification", raw_sha256=raw_sha256,
                        generator_commit=generator_commit,
                    )
                )
                groundedness.append(
                    _make_row(
                        task="groundedness", label="UNSUPPORTED", question=question,
                        evidence=unrelated_evidence, answer=positive.claim,
                        claim_supports=((positive.claim, "missing"),), source_dataset="HoVer",
                        source_version="1.1", source_license="CC BY-SA 4.0",
                        source_record_id=f"{positive.uid}:unsupported:g", source_split=positive.split,
                        document_id=document_id, mutation_family_id=family,
                        hard_negative_type="UNRELATED_EVIDENCE", language="en",
                        domain="fact_verification", raw_sha256=raw_sha256,
                        generator_commit=generator_commit,
                    )
                )
            contradiction = _derive_hover_contradiction(positive.claim)
            contradiction_question = _question_for_claim(contradiction)
            answerability.append(
                _make_row(
                    task="answerability", label="SUPPORTED", question=contradiction_question,
                    evidence=evidence, answer="", claim_supports=(), source_dataset="HoVer",
                    source_version="1.1", source_license="CC BY-SA 4.0",
                    source_record_id=f"{positive.uid}:derived-resolved:a", source_split=positive.split,
                    document_id=document_id, mutation_family_id=family, hard_negative_type="NONE",
                    language="en", domain="fact_verification", raw_sha256=raw_sha256,
                    generator_commit=generator_commit,
                )
            )
            groundedness.append(
                _make_row(
                    task="groundedness", label="CONTRADICTED", question=contradiction_question,
                    evidence=evidence, answer=contradiction,
                    claim_supports=((contradiction, "contradicted"),), source_dataset="HoVer",
                    source_version="1.1", source_license="CC BY-SA 4.0",
                    source_record_id=f"{positive.uid}:derived-contradicted:g", source_split=positive.split,
                    document_id=document_id, mutation_family_id=family,
                    hard_negative_type=(
                        "MULTI_HOP_CONTRADICTION" if positive.num_hops > 1 else "NEGATION_FLIP"
                    ),
                    language="en", domain="fact_verification", raw_sha256=raw_sha256,
                    generator_commit=generator_commit,
                )
            )
            processed += 1
    return GeneratedCorpus(answerability, groundedness)


def main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--registry", type=Path, required=True)
    parser.add_argument("--raw-root", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--generator-commit", required=True)
    parser.add_argument("--limit-per-source", type=int)
    parser.add_argument("--seed", default="rag-guard-v4.2-full-corpus")
    parser.add_argument("--tokenizer", type=Path)
    parser.add_argument("--max-length", type=int, default=256)
    parsed = parser.parse_args(arguments)
    if parsed.limit_per_source is None and parsed.tokenizer is None:
        parser.error("--tokenizer is required for a full v4.1 corpus build")
    if re.fullmatch(r"[0-9a-f]{40}", parsed.generator_commit) is None:
        parser.error("generator-commit must be 40 lowercase hexadecimal characters")
    registry_path = parsed.registry.resolve(strict=True)
    registry = json.loads(registry_path.read_text(encoding="utf-8"))
    if not isinstance(registry, dict):
        raise ValueError("registry must be an object")
    preflight = audit_training_inputs(registry, parsed.raw_root)
    if not preflight["ready_for_dataset_build"]:
        raise ValueError("training input preflight failed")
    tokenizer: object | None = None
    tokenizer_path: Path | None = None
    if parsed.tokenizer is not None:
        tokenizer_path = parsed.tokenizer.resolve(strict=True)
        if not tokenizer_path.is_dir():
            parser.error("--tokenizer must be a local directory")
        from transformers import AutoTokenizer

        tokenizer = AutoTokenizer.from_pretrained(tokenizer_path, local_files_only=True, use_fast=True)
    generated = build_all_sources(
        parsed.raw_root,
        generator_commit=parsed.generator_commit,
        limit_per_source=parsed.limit_per_source,
        tokenizer=tokenizer,
        max_length=parsed.max_length,
    )
    token_budget: dict[str, object] | None = None
    if tokenizer is not None and tokenizer_path is not None:
        answerability_candidates, rejected_answerability = filter_protected_input_budget(
            generated.answerability,
            tokenizer=tokenizer,
            max_length=parsed.max_length,
        )
        visible_groundedness, rejected_groundedness = filter_protected_input_budget(
            generated.groundedness,
            tokenizer=tokenizer,
            max_length=parsed.max_length,
        )
        groundedness_candidates, rejected_groundedness_families = (
            filter_orphaned_contradiction_families(visible_groundedness)
        )
        generated = GeneratedCorpus(
            answerability=[dict(row) for row in answerability_candidates],
            groundedness=[dict(row) for row in groundedness_candidates],
        )
        rejected_ids = sorted(rejected_answerability + rejected_groundedness)
        token_budget = {
            "max_length": parsed.max_length,
            "tokenizer": str(tokenizer_path),
            "rejected_answerability": len(rejected_answerability),
            "rejected_groundedness": len(rejected_groundedness),
            "rejected_id_sha256": _digest("\0".join(rejected_ids)),
            "rejected_orphaned_groundedness_families": len(rejected_groundedness_families),
            "rejected_orphaned_family_sha256": _digest(
                "\0".join(rejected_groundedness_families)
            ),
        }
    if parsed.limit_per_source is None:
        answerability = select_by_label_language_quotas(
            generated.answerability,
            ANSWERABILITY_LANGUAGE_QUOTAS,
            seed=parsed.seed + ":answerability",
        )
        groundedness = select_balanced_groundedness(
            generated.groundedness,
            label_quotas=GROUNDEDNESS_QUOTAS,
            contradiction_quotas=GROUNDEDNESS_CONTRADICTION_QUOTAS,
            seed=parsed.seed + ":groundedness",
        )
        expected_answerability = sum(ANSWERABILITY_QUOTAS.values())
        expected_groundedness = sum(GROUNDEDNESS_QUOTAS.values())
        if len(answerability) != expected_answerability or len(groundedness) != expected_groundedness:
            raise ValueError("candidate pool does not satisfy frozen label quotas")
    else:
        answerability = generated.answerability
        groundedness = generated.groundedness
    output_dir = parsed.output_dir.resolve()
    answerability_path = output_dir / "answerability.jsonl"
    groundedness_path = output_dir / "groundedness.jsonl"
    write_jsonl_atomic(answerability_path, answerability)
    write_jsonl_atomic(groundedness_path, groundedness)
    manifest = {
        "schema_version": 2,
        "transform_version": TRANSFORM_VERSION,
        "generator_commit": parsed.generator_commit,
        "seed": parsed.seed,
        "smoke_limit_per_source": parsed.limit_per_source,
        "registry_sha256": _file_sha256(registry_path),
        "answerability": _summary(answerability),
        "groundedness": _summary(groundedness),
        "token_budget": token_budget,
    }
    _write_json_atomic(output_dir / "corpus-manifest.json", manifest)
    print(json.dumps(manifest, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
