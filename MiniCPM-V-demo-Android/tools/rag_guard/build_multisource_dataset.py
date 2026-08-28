"""Normalize licensed bilingual corpora into balanced RAG Guard training rows."""

from __future__ import annotations

import argparse
from collections import Counter
import gzip
import hashlib
import json
import os
import re
import shutil
import stat
import tarfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping, Sequence


_SPACE = re.compile(r"\s+")
_EMAIL = re.compile(r"(?i)(?<![\w.+-])[\w.+-]+@[\w.-]+\.[a-z]{2,}(?![\w.-])")
_PHONE = re.compile(
    r"(?<!\d)(?:(?:\+?86[- ]?)?1[3-9]\d{9}|\+\d(?:[\d ()-]{6,}\d))(?!\d)"
)
_IDENTITY = re.compile(r"(?<!\d)\d{17}[0-9Xx](?!\d)")
_MAX_JSON_BYTES = 512 * 1024 * 1024
_MAX_GZIP_BYTES = 512 * 1024 * 1024
_MAX_LINE_CHARS = 100_000
_MAX_ARCHIVE_ENTRIES = 20_000
_MAX_EXPANDED_BYTES = 2 * 1024 * 1024 * 1024
_MAX_COMPRESSION_RATIO = 500


@dataclass(frozen=True)
class CorpusExample:
    source: str
    source_document_id: str
    language: str
    domain: str
    question: str
    evidence: str
    answer: str

    @property
    def document_id(self) -> str:
        return f"{self.source}:{self.source_document_id}"


def safe_extract_zip(path: Path, destination: Path) -> None:
    path = path.resolve()
    destination = destination.resolve()
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path) as archive:
        members = archive.infolist()
        if len(members) > _MAX_ARCHIVE_ENTRIES:
            raise ValueError("too many zip members")
        total_size = 0
        seen: set[str] = set()
        for member in members:
            normalized = member.filename.replace("\\", "/")
            parts = Path(normalized).parts
            if (
                not normalized
                or normalized.startswith("/")
                or ".." in parts
                or any(":" in part for part in parts)
            ):
                raise ValueError(f"unsafe zip member: {member.filename}")
            mode = member.external_attr >> 16
            if stat.S_ISLNK(mode):
                raise ValueError(f"unsafe zip link: {member.filename}")
            if normalized in seen:
                raise ValueError(f"duplicate zip member: {member.filename}")
            seen.add(normalized)
            total_size += member.file_size
            if total_size > _MAX_EXPANDED_BYTES:
                raise ValueError("zip expands beyond safety limit")
            if member.file_size / max(member.compress_size, 1) > _MAX_COMPRESSION_RATIO:
                raise ValueError(f"unsafe zip compression ratio: {member.filename}")
        for member in members:
            target = (destination / member.filename.replace("\\", "/")).resolve()
            if os.path.commonpath((str(destination), str(target))) != str(destination):
                raise ValueError(f"unsafe zip target: {member.filename}")
            if member.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists() and target.is_symlink():
                raise ValueError(f"refusing to overwrite symlink: {member.filename}")
            with archive.open(member) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output, length=1024 * 1024)


def _clean(value: object, *, limit: int) -> str:
    if not isinstance(value, str):
        return ""
    text = _SPACE.sub(" ", value.replace("\x00", " ")).strip()
    text = _EMAIL.sub("[EMAIL]", text)
    text = _IDENTITY.sub("[IDENTITY]", text)
    text = _PHONE.sub("[PHONE]", text)
    return text[:limit].strip()


def _read_json(path: Path) -> Mapping[str, object]:
    path = path.resolve()
    if not path.is_file() or path.stat().st_size > _MAX_JSON_BYTES:
        raise ValueError(f"missing or oversized JSON source: {path.name}")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"JSON source must contain an object: {path.name}")
    return value


def _read_json_value(path: Path) -> object:
    path = path.resolve()
    if not path.is_file() or path.stat().st_size > _MAX_JSON_BYTES:
        raise ValueError(f"missing or oversized JSON source: {path.name}")
    return json.loads(path.read_text(encoding="utf-8"))


def load_squad_documents(path: Path, *, source: str, language: str) -> list[CorpusExample]:
    return _load_squad_payload(_read_json(path), source=source, language=language)


def _load_squad_payload(
    payload: Mapping[str, object], *, source: str, language: str
) -> list[CorpusExample]:
    if language not in {"zh", "en"}:
        raise ValueError("language must be zh or en")
    data = payload.get("data")
    if not isinstance(data, list):
        raise ValueError("SQuAD source is missing data")
    examples: list[CorpusExample] = []
    for document_index, document in enumerate(data):
        if not isinstance(document, dict):
            continue
        title = _clean(document.get("title"), limit=240) or f"document-{document_index}"
        paragraphs = document.get("paragraphs")
        if not isinstance(paragraphs, list):
            context = document.get("context_text")
            qas = document.get("qas")
            paragraphs = [{"context": context, "qas": qas}] if isinstance(qas, list) else []
        for paragraph_index, paragraph in enumerate(paragraphs):
            if not isinstance(paragraph, dict):
                continue
            raw_context = paragraph.get("context", paragraph.get("context_text"))
            qas = paragraph.get("qas")
            if not isinstance(raw_context, str) or len(raw_context) < 20 or not isinstance(qas, list):
                continue
            chosen: CorpusExample | None = None
            for qa in qas:
                if not isinstance(qa, dict) or qa.get("is_impossible") is True:
                    continue
                answers = qa.get("answers")
                if isinstance(answers, dict):
                    texts = answers.get("text")
                    starts = answers.get("answer_start")
                    answers = (
                        [{"text": texts[0], "answer_start": starts[0] if isinstance(starts, list) and starts else None}]
                        if isinstance(texts, list) and texts
                        else []
                    )
                if not isinstance(answers, list) or not answers or not isinstance(answers[0], dict):
                    continue
                question = _clean(
                    qa.get("question", qa.get("query_text", qa.get("query"))), limit=500
                )
                answer = _clean(answers[0].get("text"), limit=500)
                if len(question) < 4 or len(answer) < 1:
                    continue
                answer_start = answers[0].get("answer_start")
                if not isinstance(answer_start, int) or answer_start < 0:
                    answer_start = raw_context.find(str(answers[0].get("text", "")))
                if answer_start < 0:
                    continue
                radius = max(100, (1_400 - len(answer)) // 2)
                window_start = max(0, answer_start - radius)
                window_end = min(len(raw_context), answer_start + len(str(answers[0].get("text", ""))) + radius)
                context = _clean(raw_context[window_start:window_end], limit=1_400)
                if answer not in context:
                    continue
                chosen = CorpusExample(
                    source=source,
                    source_document_id=f"{title}:{paragraph_index}",
                    language=language,
                    domain="general-reading",
                    question=question,
                    evidence=context,
                    answer=answer,
                )
                break
            if chosen is not None:
                examples.append(chosen)
    return examples


def load_squad_tar_documents(
    path: Path, *, source: str, language: str
) -> list[CorpusExample]:
    path = path.resolve()
    if not path.is_file() or path.stat().st_size > _MAX_GZIP_BYTES:
        raise ValueError("missing or oversized tar source")
    examples: list[CorpusExample] = []
    with tarfile.open(path, "r:gz") as archive:
        members = archive.getmembers()
        if len(members) > 10_000:
            raise ValueError("too many tar members")
        for member in members:
            parts = Path(member.name.replace("\\", "/")).parts
            if member.name.startswith(("/", "\\")) or ".." in parts:
                raise ValueError(f"unsafe tar member: {member.name}")
            if member.issym() or member.islnk():
                raise ValueError(f"unsafe tar link: {member.name}")
            if not member.isfile() or not member.name.lower().endswith(".json"):
                continue
            if member.size > _MAX_JSON_BYTES:
                raise ValueError(f"oversized tar JSON member: {member.name}")
            lowered = member.name.lower()
            if "train" not in lowered and "dev" not in lowered:
                continue
            stream = archive.extractfile(member)
            if stream is None:
                continue
            value = json.load(stream)
            if not isinstance(value, dict):
                continue
            examples.extend(
                _load_squad_payload(value, source=f"{source}:{Path(member.name).stem}", language=language)
            )
    if not examples:
        raise ValueError("tar source contained no supported SQuAD documents")
    return examples


def load_oasst_messages(path: Path) -> list[tuple[str, str, str]]:
    path = path.resolve()
    if not path.is_file() or path.stat().st_size > _MAX_GZIP_BYTES:
        raise ValueError("missing or oversized OASST1 source")
    prompts: list[tuple[str, str, str]] = []
    with gzip.open(path, "rt", encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            if len(line) > _MAX_LINE_CHARS:
                raise ValueError(f"oversized OASST1 record on line {line_number}")
            value = json.loads(line)
            if not isinstance(value, dict):
                continue
            language = value.get("lang")
            if language in {"zh-CN", "zh-TW"}:
                language = "zh"
            if (
                value.get("role") != "prompter"
                or language not in {"zh", "en"}
                or value.get("deleted") is True
                or value.get("review_result") is False
            ):
                continue
            message_id = value.get("message_id")
            text = _clean(value.get("text"), limit=500)
            if isinstance(message_id, str) and len(text) >= 4:
                prompts.append((f"oasst1:{message_id}", str(language), text))
    return prompts


def _iter_dialogues(value: object) -> Iterable[Mapping[str, object]]:
    if isinstance(value, dict):
        messages = value.get("messages")
        if isinstance(messages, list):
            yield value
        else:
            for child in value.values():
                yield from _iter_dialogues(child)
    elif isinstance(value, list):
        for child in value:
            yield from _iter_dialogues(child)


def _message_text(message: Mapping[str, object]) -> str:
    for key in ("content", "message", "utterance", "text"):
        text = _clean(message.get(key), limit=500)
        if text:
            return text
    return ""


def load_dialogue_prompts(
    paths: Sequence[Path], *, source: str, language: str
) -> list[tuple[str, str, str]]:
    if language not in {"zh", "en"}:
        raise ValueError("language must be zh or en")
    prompts: list[tuple[str, str, str]] = []
    for path in sorted((item.resolve() for item in paths), key=str):
        value = _read_json_value(path)
        for dialogue_index, dialogue in enumerate(_iter_dialogues(value)):
            messages = dialogue.get("messages")
            assert isinstance(messages, list)
            for message_index, message in enumerate(messages):
                if not isinstance(message, dict):
                    continue
                role = str(message.get("role", message.get("speaker", ""))).lower()
                is_user = role in {"usr", "user", "human", "prompter"}
                if not role:
                    is_user = message_index % 2 == 0
                text = _message_text(message)
                if is_user and len(text) >= 2:
                    prompt_id = f"{source}:{path.stem}:{dialogue_index}:{message_index}"
                    prompts.append((prompt_id, language, text))
    return prompts


def load_kdconv(root: Path) -> tuple[list[CorpusExample], list[tuple[str, str, str]]]:
    root = root.resolve()
    paths = sorted(
        path
        for path in root.glob("data/*/*.json")
        if path.is_file() and not path.name.startswith("kb_")
    )
    documents: list[CorpusExample] = []
    prompts: list[tuple[str, str, str]] = []
    for path in paths:
        value = _read_json_value(path)
        for dialogue_index, dialogue in enumerate(_iter_dialogues(value)):
            messages = dialogue.get("messages")
            assert isinstance(messages, list)
            for message_index, message in enumerate(messages):
                if not isinstance(message, dict):
                    continue
                text = _message_text(message)
                identity = f"kdconv:{path.parent.name}:{path.stem}:{dialogue_index}:{message_index}"
                attrs = message.get("attrs")
                if not isinstance(attrs, list) or not attrs:
                    if len(text) >= 2:
                        prompts.append((identity, "zh", text))
                    continue
                if message_index == 0 or not text:
                    continue
                previous = messages[message_index - 1]
                if not isinstance(previous, dict):
                    continue
                question = _message_text(previous)
                if not question:
                    continue
                evidence_parts: list[str] = []
                for attr in attrs:
                    if not isinstance(attr, dict):
                        continue
                    name = _clean(attr.get("name"), limit=120)
                    relation = _clean(attr.get("attrname"), limit=120)
                    value_text = _clean(attr.get("attrvalue"), limit=900)
                    evidence = "：".join(part for part in (name, relation, value_text) if part)
                    if len(evidence) >= 4:
                        evidence_parts.append(evidence)
                evidence_text = "；".join(evidence_parts)
                if len(question) >= 2 and len(evidence_text) >= 8:
                    documents.append(
                        CorpusExample(
                            source="kdconv",
                            source_document_id=identity,
                            language="zh",
                            domain=path.parent.name,
                            question=question,
                            evidence=evidence_text,
                            answer=text,
                        )
                    )
    return documents, prompts


def _rank(seed: str, value: str) -> str:
    return hashlib.sha256(f"{seed}\0{value}".encode("utf-8")).hexdigest()


def _deduplicate_examples(examples: Iterable[CorpusExample]) -> list[CorpusExample]:
    by_document: dict[str, CorpusExample] = {}
    seen_content: set[str] = set()
    for example in examples:
        if example.language not in {"zh", "en"}:
            continue
        cleaned = CorpusExample(
            source=_clean(example.source, limit=80),
            source_document_id=_clean(example.source_document_id, limit=240),
            language=example.language,
            domain=_clean(example.domain, limit=80),
            question=_clean(example.question, limit=500),
            evidence=_clean(example.evidence, limit=1_400),
            answer=_clean(example.answer, limit=500),
        )
        if min(len(cleaned.question), len(cleaned.evidence), len(cleaned.answer)) < 1:
            continue
        content_id = _rank(
            "content",
            f"{cleaned.language}\0{cleaned.question}\0{cleaned.evidence}\0{cleaned.answer}",
        )
        if cleaned.document_id in by_document or content_id in seen_content:
            continue
        by_document[cleaned.document_id] = cleaned
        seen_content.add(content_id)
    return list(by_document.values())


def _split_documents(examples: Sequence[CorpusExample], seed: str) -> dict[str, list[CorpusExample]]:
    result: dict[str, list[CorpusExample]] = {"train": [], "calibration": [], "test": []}
    for language in ("zh", "en"):
        ordered = sorted(
            (example for example in examples if example.language == language),
            key=lambda item: (_rank(seed, item.document_id), item.document_id),
        )
        if len(ordered) < 20:
            raise ValueError(f"at least 20 {language} documents are required")
        calibration_count = max(2, round(len(ordered) * 0.05))
        test_count = max(2, round(len(ordered) * 0.05))
        train_end = len(ordered) - calibration_count - test_count
        result["train"].extend(ordered[:train_end])
        result["calibration"].extend(ordered[train_end : train_end + calibration_count])
        result["test"].extend(ordered[train_end + calibration_count :])
    return result


def _row(
    example: CorpusExample,
    *,
    split: str,
    task: str,
    label: str,
    suffix: str,
    question: str,
    answer: str,
    construction: str,
    row_identity: str | None = None,
) -> dict[str, str]:
    identity = row_identity or example.document_id
    return {
        "id": f"v3-{split}-{_rank(suffix, identity)[:24]}-{suffix}",
        "task": task,
        "label": label,
        "question": question,
        "evidence": example.evidence,
        "answer": answer,
        "document_id": example.document_id,
        "split": split,
        "language": example.language,
        "hard_negative_type": construction,
        "source": example.source,
    }


def _document_rows(examples: Sequence[CorpusExample], split: str) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    by_language = {
        language: [item for item in examples if item.language == language]
        for language in ("zh", "en")
    }
    for language, peers in by_language.items():
        for position, example in enumerate(peers):
            distractor = peers[(position + 1) % len(peers)]
            conjunction = "另外，请回答：" if language == "zh" else " Also answer: "
            unsupported_clause = (
                f"另外，{distractor.answer}。"
                if language == "zh"
                else f" Additionally, {distractor.answer}"
            )
            rows.extend(
                [
                    _row(example, split=split, task="answerability", label="SUPPORTED", suffix="a-s", question=example.question, answer="", construction="gold"),
                    _row(example, split=split, task="answerability", label="PARTIAL", suffix="a-p", question=example.question + conjunction + distractor.question, answer="", construction="mixed_query"),
                    _row(example, split=split, task="answerability", label="UNSUPPORTED", suffix="a-u", question=distractor.question, answer="", construction="wrong_document_query"),
                    _row(example, split=split, task="groundedness", label="GROUNDED", suffix="g-g", question=example.question, answer=example.answer, construction="gold"),
                    _row(example, split=split, task="groundedness", label="PARTIAL", suffix="g-p", question=example.question, answer=example.answer + unsupported_clause, construction="unsupported_clause"),
                    _row(example, split=split, task="groundedness", label="UNGROUNDED", suffix="g-u", question=example.question, answer=distractor.answer, construction="wrong_document_answer"),
                ]
            )
    return rows


def _conversation_rows(
    prompts: Sequence[tuple[str, str, str]],
    split_documents: Mapping[str, Sequence[CorpusExample]],
    seed: str,
) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    candidates_by_split_language = {
        (split, language): [
            item for item in documents if item.language == language
        ]
        for split, documents in split_documents.items()
        for language in ("zh", "en")
    }
    for prompt_id, language, prompt in prompts:
        if language not in {"zh", "en"}:
            continue
        split_bucket = int(_rank(seed, prompt_id)[:8], 16) % 100
        split = "train" if split_bucket < 90 else "calibration" if split_bucket < 95 else "test"
        candidates = candidates_by_split_language[(split, language)]
        if not candidates:
            continue
        evidence = candidates[int(_rank(seed, prompt_id)[8:16], 16) % len(candidates)]
        cleaned_prompt = _clean(prompt, limit=500)
        if len(cleaned_prompt) < 4:
            continue
        rows.append(
            _row(
                evidence,
                split=split,
                task="answerability",
                label="UNSUPPORTED",
                suffix="a-daily",
                question=cleaned_prompt,
                answer="",
                construction="daily_conversation_irrelevant_evidence",
                row_identity=prompt_id,
            )
        )
    return rows


def _balanced(rows: Sequence[dict[str, str]], seed: str) -> list[dict[str, str]]:
    selected: list[dict[str, str]] = []
    for task, labels in (
        ("answerability", ("SUPPORTED", "PARTIAL", "UNSUPPORTED")),
        ("groundedness", ("GROUNDED", "PARTIAL", "UNGROUNDED")),
    ):
        groups = {
            (label, language): [
                row
                for row in rows
                if row["task"] == task
                and row["label"] == label
                and row["language"] == language
            ]
            for label in labels
            for language in ("zh", "en")
        }
        count = min(len(group) for group in groups.values())
        if count == 0:
            raise ValueError(f"missing label group for {task}")
        for (_label, _language), group in groups.items():
            ordered = sorted(group, key=lambda row: _rank(seed, row["id"]))
            selected.extend(ordered[:count])
    return sorted(selected, key=lambda row: (row["task"], _rank(seed, row["id"])))


def build_balanced_rows(
    documents: Sequence[CorpusExample],
    conversation_prompts: Sequence[tuple[str, str, str]],
    *,
    seed: str,
    excluded_document_ids: set[str] | None = None,
) -> dict[str, tuple[dict[str, str], ...]]:
    excluded = excluded_document_ids or set()
    eligible = [item for item in _deduplicate_examples(documents) if item.document_id not in excluded]
    split_documents = _split_documents(eligible, seed)
    all_rows: dict[str, list[dict[str, str]]] = {
        split: _document_rows(examples, split) for split, examples in split_documents.items()
    }
    for row in _conversation_rows(conversation_prompts, split_documents, seed):
        all_rows[row["split"]].append(row)
    result = {split: tuple(_balanced(rows, seed)) for split, rows in all_rows.items()}
    split_ids = {
        split: {row["document_id"] for row in rows} for split, rows in result.items()
    }
    if (
        split_ids["train"] & split_ids["calibration"]
        or split_ids["train"] & split_ids["test"]
        or split_ids["calibration"] & split_ids["test"]
    ):
        raise AssertionError("document leakage between generated splits")
    return result


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _write_jsonl(path: Path, rows: Sequence[Mapping[str, str]]) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as output:
        for row in rows:
            output.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    temporary.replace(path)


def write_training_dataset(
    output_dir: Path,
    rows_by_split: Mapping[str, Sequence[Mapping[str, str]]],
    *,
    source_counts: Mapping[str, int],
    provenance: Mapping[str, object] | None = None,
) -> dict[str, object]:
    output_dir = output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    outputs: dict[str, dict[str, object]] = {}
    aggregate: Counter[str] = Counter()
    for split in ("train", "calibration", "test"):
        rows = rows_by_split.get(split)
        if not rows:
            raise ValueError(f"missing generated split: {split}")
        for task in ("answerability", "groundedness"):
            selected = [row for row in rows if row.get("task") == task]
            if not selected:
                raise ValueError(f"missing generated task: {split}/{task}")
            path = output_dir / f"{task}_{split}.jsonl"
            _write_jsonl(path, selected)
            outputs[path.name] = {"rows": len(selected), "sha256": _file_sha256(path)}
            for row in selected:
                key = f"{split}/{task}/{row['label']}/{row['language']}"
                aggregate[key] += 1
    manifest: dict[str, object] = {
        "schema_version": 1,
        "dataset": "rag_guard_multisource_bilingual_v3",
        "source_counts": dict(sorted(source_counts.items())),
        "counts": dict(sorted(aggregate.items())),
        "outputs": outputs,
        "contains_raw_text": False,
        "provenance": dict(provenance or {}),
    }
    manifest_path = output_dir / "dataset_manifest.json"
    temporary = manifest_path.with_suffix(".json.tmp")
    temporary.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(manifest_path)
    return manifest


def _capped_documents(
    examples: Sequence[CorpusExample], *, maximum_per_source_language: int, seed: str
) -> list[CorpusExample]:
    if maximum_per_source_language < 20:
        raise ValueError("maximum documents per source/language must be at least 20")
    groups: dict[tuple[str, str], list[CorpusExample]] = {}
    for example in examples:
        groups.setdefault((example.source, example.language), []).append(example)
    selected: list[CorpusExample] = []
    for key, group in sorted(groups.items()):
        ordered = sorted(group, key=lambda item: _rank(seed, item.document_id))
        selected.extend(ordered[:maximum_per_source_language])
    return selected


def _capped_prompts(
    prompts: Sequence[tuple[str, str, str]], *, maximum_per_source_language: int, seed: str
) -> list[tuple[str, str, str]]:
    groups: dict[tuple[str, str], list[tuple[str, str, str]]] = {}
    for prompt in prompts:
        source = prompt[0].split(":", 1)[0]
        groups.setdefault((source, prompt[1]), []).append(prompt)
    selected: list[tuple[str, str, str]] = []
    for key, group in sorted(groups.items()):
        ordered = sorted(group, key=lambda item: _rank(seed, item[0]))
        selected.extend(ordered[:maximum_per_source_language])
    return selected


def _load_public_archives(doc2dial_path: Path, cuad_path: Path) -> list[CorpusExample]:
    from tools.rag_guard.public_office_dataset import _load_cuad, _load_doc2dial

    doc2dial, _ = _load_doc2dial(doc2dial_path, None)
    cuad, _ = _load_cuad(cuad_path, None)
    return [
        CorpusExample(
            source=f"public-{example.source}",
            source_document_id=example.source_document_id,
            language="en",
            domain=example.domain,
            question=example.question,
            evidence=example.evidence,
            answer=example.answer,
        )
        for example in [*doc2dial, *cuad]
    ]


def _load_excluded_document_ids(path: Path | None) -> set[str]:
    if path is None:
        return set()
    value = _read_json(path)
    documents = value.get("documents")
    if not isinstance(documents, dict):
        raise ValueError("excluded manifest is missing documents")
    result: set[str] = set()
    for split_ids in documents.values():
        if isinstance(split_ids, list):
            result.update(item for item in split_ids if isinstance(item, str))
    return result


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build the bilingual multi-source RAG Guard v3 dataset.")
    parser.add_argument("--squad-en", type=Path, action="append", default=[])
    parser.add_argument("--squad-zh", type=Path, action="append", default=[])
    parser.add_argument("--squad-zh-tar", type=Path, action="append", default=[])
    parser.add_argument("--doc2dial", type=Path, required=True)
    parser.add_argument("--cuad", type=Path, required=True)
    parser.add_argument("--oasst", type=Path, required=True)
    parser.add_argument("--crosswoz-dir", type=Path, required=True)
    parser.add_argument("--kdconv-dir", type=Path, required=True)
    parser.add_argument("--excluded-manifest", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--seed", default="rag-guard-multisource-bilingual-v3")
    parser.add_argument("--max-documents-per-source-language", type=int, default=15_000)
    parser.add_argument("--max-prompts-per-source-language", type=int, default=10_000)
    return parser.parse_args()


def main() -> int:
    arguments = _parse_args()
    documents: list[CorpusExample] = []
    prompts: list[tuple[str, str, str]] = []
    input_paths: list[Path] = []
    for path in arguments.squad_en:
        documents.extend(load_squad_documents(path, source=path.stem, language="en"))
        input_paths.append(path)
    for path in arguments.squad_zh:
        documents.extend(load_squad_documents(path, source=path.stem, language="zh"))
        input_paths.append(path)
    for path in arguments.squad_zh_tar:
        documents.extend(load_squad_tar_documents(path, source=path.stem, language="zh"))
        input_paths.append(path)
    documents.extend(_load_public_archives(arguments.doc2dial, arguments.cuad))
    input_paths.extend((arguments.doc2dial, arguments.cuad, arguments.oasst))
    prompts.extend(load_oasst_messages(arguments.oasst))
    crosswoz_paths = sorted(arguments.crosswoz_dir.glob("*.json"))
    prompts.extend(load_dialogue_prompts(crosswoz_paths, source="crosswoz", language="zh"))
    input_paths.extend(crosswoz_paths)
    kdconv_documents, kdconv_prompts = load_kdconv(arguments.kdconv_dir)
    documents.extend(kdconv_documents)
    prompts.extend(kdconv_prompts)
    kdconv_paths = sorted(
        path
        for path in arguments.kdconv_dir.glob("data/*/*.json")
        if not path.name.startswith("kb_")
    )
    input_paths.extend(kdconv_paths)

    documents = _capped_documents(
        documents,
        maximum_per_source_language=arguments.max_documents_per_source_language,
        seed=arguments.seed,
    )
    prompts = _capped_prompts(
        prompts,
        maximum_per_source_language=arguments.max_prompts_per_source_language,
        seed=arguments.seed,
    )
    excluded = _load_excluded_document_ids(arguments.excluded_manifest)
    rows = build_balanced_rows(
        documents,
        prompts,
        seed=arguments.seed,
        excluded_document_ids=excluded,
    )
    source_counts = Counter(example.source for example in documents)
    source_counts.update(f"daily:{item[0].split(':', 1)[0]}" for item in prompts)
    provenance = {
        "input_sha256": {
            str(path.resolve()): _file_sha256(path.resolve())
            for path in sorted(set(input_paths), key=lambda item: str(item.resolve()))
        },
        "licenses": {
            "squad2": "CC BY-SA 4.0",
            "cmrc2018": "CC BY-SA 4.0",
            "drcd": "CC BY-SA 4.0",
            "dureader_robust": "Apache-2.0",
            "doc2dial": "CC BY 3.0",
            "cuad": "CC BY 4.0",
            "oasst1": "Apache-2.0",
            "crosswoz": "Apache-2.0",
            "kdconv": "Apache-2.0",
        },
        "seed": arguments.seed,
        "excluded_document_count": len(excluded),
    }
    manifest = write_training_dataset(
        arguments.output_dir,
        rows,
        source_counts=source_counts,
        provenance=provenance,
    )
    print(json.dumps({"counts": manifest["counts"], "source_counts": manifest["source_counts"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
