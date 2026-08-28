"""Strict, dependency-free validation for the RAG Guard v4 JSONL contract."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Mapping, Sequence

from tools.rag_guard.training_data import LABELS_BY_TASK_V4


MAX_FILE_BYTES = 512 * 1024 * 1024
MAX_LINE_BYTES = 2 * 1024 * 1024
MAX_TEXT_CHARS = 100_000
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
COMMIT_PATTERN = re.compile(r"^[0-9a-f]{40}$")
ALLOWED_SPLITS = {"train", "calibration", "test", "regression"}
ALLOWED_LANGUAGES = {"zh", "en", "mixed"}
ALLOWED_SUPPORT = {"entailed", "missing", "contradicted"}
APPROVED_LICENSE_STATUS = "approved"


def _required_text(row: Mapping[str, object], field: str, *, allow_empty: bool = False) -> str:
    value = row.get(field)
    if not isinstance(value, str) or (not allow_empty and not value.strip()):
        raise ValueError(f"{field} must be a non-empty string")
    if len(value) > MAX_TEXT_CHARS:
        raise ValueError(f"{field} exceeds maximum length")
    return value


def _validate_evidence(value: object) -> set[str]:
    if not isinstance(value, list) or not value:
        raise ValueError("evidence must be a non-empty list")
    source_ids: set[str] = set()
    for item in value:
        if not isinstance(item, dict):
            raise ValueError("evidence entries must be objects")
        source_id = _required_text(item, "source_id")
        _required_text(item, "document_id")
        _required_text(item, "text")
        if source_id in source_ids:
            raise ValueError("duplicate source_id")
        source_ids.add(source_id)
    return source_ids


def _validate_claims(value: object, source_ids: set[str], *, required: bool) -> None:
    if not isinstance(value, list) or (required and not value):
        raise ValueError("atomic_claims must be a non-empty list for groundedness")
    for claim in value:
        if not isinstance(claim, dict):
            raise ValueError("atomic_claims entries must be objects")
        _required_text(claim, "text")
        if claim.get("support") not in ALLOWED_SUPPORT:
            raise ValueError("atomic claim support is invalid")
        if not isinstance(claim.get("material"), bool):
            raise ValueError("atomic claim material must be boolean")
        references = claim.get("source_ids")
        if not isinstance(references, list) or any(
            not isinstance(item, str) or item not in source_ids for item in references
        ):
            raise ValueError("atomic claim source_ids are invalid")


def _validate_provenance(value: object) -> None:
    if not isinstance(value, dict):
        raise ValueError("provenance must be an object")
    raw_hash = value.get("raw_sha256")
    if not isinstance(raw_hash, str) or SHA256_PATTERN.fullmatch(raw_hash) is None:
        raise ValueError("raw_sha256 must be 64 lowercase hexadecimal characters")
    transform_version = value.get("transform_version")
    if not isinstance(transform_version, str) or not transform_version.strip():
        raise ValueError("transform_version must be a non-empty string")
    commit = value.get("generator_commit")
    if not isinstance(commit, str) or COMMIT_PATTERN.fullmatch(commit) is None:
        raise ValueError("generator_commit must be 40 lowercase hexadecimal characters")


def validate_v2_row(row: Mapping[str, object]) -> None:
    if not isinstance(row, Mapping):
        raise ValueError("row must be an object")
    task = _required_text(row, "task")
    if task not in LABELS_BY_TASK_V4:
        raise ValueError("invalid task")
    label = _required_text(row, "label")
    if label not in LABELS_BY_TASK_V4[task]:
        raise ValueError(f"invalid {task} label")
    for field in (
        "id",
        "question",
        "language",
        "domain",
        "hard_negative_type",
        "mutation_family_id",
        "document_id",
        "split",
        "distribution",
        "redaction_status",
        "source_dataset",
        "source_version",
        "source_record_id",
        "source_license",
    ):
        _required_text(row, field)
    _required_text(row, "conversation_id", allow_empty=True)
    answer = _required_text(row, "answer", allow_empty=task == "answerability")
    if task == "groundedness" and not answer.strip():
        raise ValueError("groundedness answer must be non-empty")
    if row["split"] not in ALLOWED_SPLITS:
        raise ValueError("split is invalid")
    if row["language"] not in ALLOWED_LANGUAGES:
        raise ValueError("language is invalid")
    if row.get("license_status") != APPROVED_LICENSE_STATUS:
        raise ValueError("license_status must be approved")
    source_ids = _validate_evidence(row.get("evidence"))
    _validate_claims(row.get("atomic_claims"), source_ids, required=task == "groundedness")
    _validate_provenance(row.get("provenance"))


def validate_jsonl(path: Path) -> int:
    resolved = path.resolve(strict=True)
    if not resolved.is_file() or resolved.stat().st_size > MAX_FILE_BYTES:
        raise ValueError("dataset file is missing or too large")
    count = 0
    with resolved.open("rb") as source:
        for line_number, raw_line in enumerate(source, start=1):
            if len(raw_line) > MAX_LINE_BYTES:
                raise ValueError(f"line {line_number} exceeds maximum length")
            try:
                row = json.loads(raw_line.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise ValueError(f"invalid JSON on line {line_number}") from error
            if not isinstance(row, dict):
                raise ValueError(f"line {line_number} must be an object")
            validate_v2_row(row)
            count += 1
    if count == 0:
        raise ValueError("dataset is empty")
    return count


def main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("path", type=Path)
    parsed = parser.parse_args(arguments)
    count = validate_jsonl(parsed.path)
    print(json.dumps({"passed": True, "rows": count}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
