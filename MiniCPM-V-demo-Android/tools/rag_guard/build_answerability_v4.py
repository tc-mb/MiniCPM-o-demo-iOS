"""Build provenance-preserving Answerability v4 rows from licensed QA sources."""

from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

from tools.rag_guard.dataset_schema_v2 import validate_v2_row


@dataclass(frozen=True)
class AnswerabilitySourceRecord:
    source_dataset: str
    source_version: str
    source_license: str
    source_record_id: str
    document_id: str
    language: str
    domain: str
    question: str
    evidence: str


@dataclass(frozen=True)
class LabeledAnswerability:
    label: str
    question: str
    evidence: str


def _digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.resolve(strict=True).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def contract_text_to_answerability(
    *, question: str, evidence: str, source_record_id: str
) -> LabeledAnswerability:
    if not question.strip() or not evidence.strip() or not source_record_id.strip():
        raise ValueError("question, evidence, and source_record_id must be non-empty")
    return LabeledAnswerability("SUPPORTED", question.strip(), evidence.strip())


def _row(
    source: AnswerabilitySourceRecord,
    *,
    label: str,
    question: str,
    suffix: str,
    raw_sha256: str,
    split: str = "train",
    generator_commit: str = "0" * 40,
) -> dict[str, object]:
    family_id = "answerability-" + _digest(
        f"{source.source_dataset}\0{source.source_record_id}\0{source.document_id}"
    )[:24]
    row: dict[str, object] = {
        "id": f"{family_id}-{suffix}",
        "task": "answerability",
        "label": label,
        "question": question.strip(),
        "evidence": [
            {
                "source_id": "S1",
                "document_id": source.document_id,
                "text": source.evidence.strip(),
            }
        ],
        "answer": "",
        "atomic_claims": [],
        "language": source.language,
        "domain": source.domain,
        "hard_negative_type": {
            "SUPPORTED": "NONE",
            "PARTIAL": "MISSING_FIELD",
            "UNSUPPORTED": "SIMILAR_BUT_NO_ANSWER",
        }[label],
        "mutation_family_id": family_id,
        "document_id": source.document_id,
        "conversation_id": "",
        "split": split,
        "distribution": "public_licensed",
        "redaction_status": "public_source_reviewed",
        "source_dataset": source.source_dataset,
        "source_version": source.source_version,
        "source_record_id": source.source_record_id,
        "source_license": source.source_license,
        "license_status": "approved",
        "provenance": {
            "raw_sha256": raw_sha256,
            "transform_version": "rag-guard-v4",
            "generator_commit": generator_commit,
        },
    }
    validate_v2_row(row)
    return row


def build_answerability_family(
    source: AnswerabilitySourceRecord,
    *,
    missing_question: str,
    unsupported_question: str,
    raw_sha256: str = "0" * 64,
) -> list[dict[str, object]]:
    if not missing_question.strip() or not unsupported_question.strip():
        raise ValueError("hard-negative questions must be non-empty")
    conjunction = "另外，" if source.language == "zh" else " Also, "
    partial_question = source.question.rstrip("？?") + conjunction + missing_question.strip()
    return [
        _row(
            source,
            label="SUPPORTED",
            question=source.question,
            suffix="supported",
            raw_sha256=raw_sha256,
        ),
        _row(
            source,
            label="PARTIAL",
            question=partial_question,
            suffix="partial",
            raw_sha256=raw_sha256,
        ),
        _row(
            source,
            label="UNSUPPORTED",
            question=unsupported_question,
            suffix="unsupported",
            raw_sha256=raw_sha256,
        ),
    ]


def load_squad_answerability(
    path: Path,
    *,
    source_dataset: str,
    source_version: str,
    source_license: str,
    language: str,
) -> list[dict[str, object]]:
    resolved = path.resolve(strict=True)
    payload = json.loads(resolved.read_text(encoding="utf-8"))
    if not isinstance(payload, dict) or not isinstance(payload.get("data"), list):
        raise ValueError("invalid SQuAD payload")
    raw_hash = _file_sha256(resolved)
    rows: list[dict[str, object]] = []
    for article_index, article in enumerate(payload["data"]):
        if not isinstance(article, dict) or not isinstance(article.get("paragraphs"), list):
            raise ValueError("invalid SQuAD article")
        title = str(article.get("title") or f"article-{article_index}")
        for paragraph_index, paragraph in enumerate(article["paragraphs"]):
            if not isinstance(paragraph, dict) or not isinstance(paragraph.get("qas"), list):
                raise ValueError("invalid SQuAD paragraph")
            context = str(paragraph.get("context") or "").strip()
            if not context:
                continue
            document_id = f"{source_dataset}:{title}:{paragraph_index}"
            for question_index, qa in enumerate(paragraph["qas"]):
                if not isinstance(qa, dict):
                    raise ValueError("invalid SQuAD question")
                question = str(qa.get("question") or "").strip()
                record_id = str(qa.get("id") or f"{title}:{paragraph_index}:{question_index}")
                if not question:
                    continue
                source = AnswerabilitySourceRecord(
                    source_dataset=source_dataset,
                    source_version=source_version,
                    source_license=source_license,
                    source_record_id=record_id,
                    document_id=document_id,
                    language=language,
                    domain="general_qa",
                    question=question,
                    evidence=context,
                )
                impossible = bool(qa.get("is_impossible")) or not qa.get("answers")
                rows.append(
                    _row(
                        source,
                        label="UNSUPPORTED" if impossible else "SUPPORTED",
                        question=question,
                        suffix="unsupported" if impossible else "supported",
                        raw_sha256=raw_hash,
                    )
                )
    if not rows:
        raise ValueError("SQuAD source produced no rows")
    return rows


def _write_jsonl(path: Path, rows: Sequence[dict[str, object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as output:
        for row in rows:
            output.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    temporary.replace(path)


def main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--squad", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--source-dataset", required=True)
    parser.add_argument("--source-version", required=True)
    parser.add_argument("--source-license", required=True)
    parser.add_argument("--language", choices=("zh", "en"), required=True)
    parsed = parser.parse_args(arguments)
    rows = load_squad_answerability(
        parsed.squad,
        source_dataset=parsed.source_dataset,
        source_version=parsed.source_version,
        source_license=parsed.source_license,
        language=parsed.language,
    )
    _write_jsonl(parsed.output.resolve(), rows)
    print(json.dumps({"rows": len(rows), "output": str(parsed.output.resolve())}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
