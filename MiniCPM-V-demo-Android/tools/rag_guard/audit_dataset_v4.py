"""Fail-closed quality, privacy, license, and split audit for Guard v4."""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter, defaultdict
from pathlib import Path
from typing import Mapping, Sequence

from tools.rag_guard.dataset_schema_v2 import validate_v2_row
from tools.rag_guard.dataset_balance_v4 import summarize_groundedness, validate_groundedness_balance
from tools.rag_guard.dataset_correctness_v4 import (
    summarize_dataset_correctness,
    validate_dataset_correctness,
)


PHONE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
IDENTITY = re.compile(r"(?<![0-9A-Za-z])\d{17}[0-9Xx](?![0-9A-Za-z])")
EMAIL = re.compile(r"[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,190}\.[A-Za-z]{2,24}")
FAMILY_KEYS = (
    "document_id",
    "conversation_id",
    "mutation_family_id",
    "translation_family_id",
    "near_duplicate_cluster_id",
)


def validate_registry(registry: Mapping[str, object]) -> None:
    sources = registry.get("sources")
    if not isinstance(sources, list):
        raise ValueError("registry sources must be a list")
    seen: set[str] = set()
    for source in sources:
        if not isinstance(source, dict) or not isinstance(source.get("id"), str):
            raise ValueError("registry source is invalid")
        source_id = source["id"]
        if source_id in seen:
            raise ValueError("duplicate registry source")
        seen.add(source_id)
        if source.get("enabled") is True and source.get("license_status") != "approved":
            raise ValueError("enabled source license is not approved")


def _content_strings(row: Mapping[str, object]):
    for field in ("question", "answer"):
        value = row.get(field)
        if isinstance(value, str):
            yield value
    evidence = row.get("evidence")
    if isinstance(evidence, list):
        for item in evidence:
            if isinstance(item, dict) and isinstance(item.get("text"), str):
                yield item["text"]
    claims = row.get("atomic_claims")
    if isinstance(claims, list):
        for claim in claims:
            if isinstance(claim, dict) and isinstance(claim.get("text"), str):
                yield claim["text"]


def _reject_sensitive_data(row: Mapping[str, object]) -> None:
    for value in _content_strings(row):
        if PHONE.search(value) or IDENTITY.search(value) or EMAIL.search(value):
            raise ValueError("sensitive data detected")


def audit_rows(rows: Sequence[Mapping[str, object]]) -> dict[str, object]:
    if not rows:
        raise ValueError("dataset is empty")
    ids: set[str] = set()
    family_splits: dict[tuple[str, str], set[str]] = defaultdict(set)
    counts: Counter[str] = Counter()
    for row in rows:
        _reject_sensitive_data(row)
        validate_v2_row(row)
        row_id = str(row["id"])
        if row_id in ids:
            raise ValueError("duplicate row id")
        ids.add(row_id)
        split = str(row["split"])
        counts[f"{split}/{row['task']}/{row['label']}/{row['language']}"] += 1
        for key in FAMILY_KEYS:
            value = row.get(key)
            if isinstance(value, str) and value.strip():
                family_splits[(key, value)].add(split)
    for (key, _value), splits in family_splits.items():
        if len(splits) > 1:
            raise ValueError(f"{key} leakage between splits")
    return {"passed": True, "rows": len(rows), "counts": dict(sorted(counts.items()))}


def audit_release_balance(rows: Sequence[Mapping[str, object]]) -> dict[str, object]:
    return validate_groundedness_balance(summarize_groundedness(rows))


def audit_release_correctness(
    rows: Sequence[Mapping[str, object]], *, tokenizer: object, max_length: int
) -> dict[str, object]:
    return validate_dataset_correctness(
        summarize_dataset_correctness(rows, tokenizer=tokenizer, max_length=max_length)
    )


def _read_jsonl_files(directory: Path, *, pattern: str = "*.jsonl") -> list[dict[str, object]]:
    resolved = directory.resolve(strict=True)
    rows: list[dict[str, object]] = []
    for path in sorted(resolved.glob(pattern)):
        with path.open("r", encoding="utf-8") as source:
            for line_number, line in enumerate(source, start=1):
                value = json.loads(line)
                if not isinstance(value, dict):
                    raise ValueError(f"{path.name}:{line_number} must be an object")
                rows.append(value)
    return rows


def main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--registry", type=Path, required=True)
    parser.add_argument("--input-dir", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--pattern", default="*.jsonl")
    parser.add_argument("--profile", choices=("smoke", "release"), default="release")
    parser.add_argument("--tokenizer", type=Path)
    parser.add_argument("--max-length", type=int, default=256)
    parsed = parser.parse_args(arguments)
    registry = json.loads(parsed.registry.resolve(strict=True).read_text(encoding="utf-8"))
    if not isinstance(registry, dict):
        raise ValueError("registry must be an object")
    validate_registry(registry)
    rows = _read_jsonl_files(parsed.input_dir, pattern=parsed.pattern)
    report = audit_rows(rows)
    if parsed.profile == "release":
        if parsed.tokenizer is None:
            parser.error("--tokenizer is required for the release profile")
        tokenizer_path = parsed.tokenizer.resolve(strict=True)
        if not tokenizer_path.is_dir():
            parser.error("--tokenizer must be a local directory")
        from transformers import AutoTokenizer

        tokenizer = AutoTokenizer.from_pretrained(tokenizer_path, local_files_only=True)
        report["groundedness_balance"] = audit_release_balance(rows)
        report["dataset_correctness"] = audit_release_correctness(
            rows,
            tokenizer=tokenizer,
            max_length=parsed.max_length,
        )
    output = parsed.report.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(output)
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
