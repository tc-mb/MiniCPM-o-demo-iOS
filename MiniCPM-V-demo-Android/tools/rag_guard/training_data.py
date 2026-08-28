"""Validated input formatting and dependency-free metrics for RAG guard training."""

from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Iterable, Mapping, Sequence


LABELS_BY_TASK_V3: dict[str, tuple[str, ...]] = {
    "answerability": ("SUPPORTED", "PARTIAL", "UNSUPPORTED"),
    "groundedness": ("GROUNDED", "PARTIAL", "UNGROUNDED"),
}

LABELS_BY_TASK_V4: dict[str, tuple[str, ...]] = {
    "answerability": ("SUPPORTED", "PARTIAL", "UNSUPPORTED"),
    "groundedness": ("GROUNDED", "PARTIAL", "UNSUPPORTED", "CONTRADICTED"),
}

# The installed Android model and the v3 training/export tools still consume the
# three-class Groundedness contract. V4 callers must opt in explicitly so data
# cannot silently cross the model-version boundary.
LABELS_BY_TASK = LABELS_BY_TASK_V3

_REQUIRED_TEXT_FIELDS = (
    "id",
    "task",
    "label",
    "question",
    "evidence",
    "answer",
    "document_id",
    "split",
    "language",
)


def format_model_input(row: Mapping[str, str]) -> str:
    task = row.get("task")
    if task not in LABELS_BY_TASK:
        raise ValueError(f"unsupported task: {task!r}")
    question = row.get("question", "").strip()
    evidence = row.get("evidence", "").strip()
    if not question or not evidence:
        raise ValueError("question and evidence must be non-empty")
    parts = [f"query: {question}", f"evidence: {evidence}"]
    if task == "groundedness":
        answer = row.get("answer", "").strip()
        if not answer:
            raise ValueError("groundedness answer must be non-empty")
        parts.append(f"answer: {answer}")
    return "\n".join(parts)


def format_model_input_v4(row: Mapping[str, object]) -> str:
    """Format a schema-v2 row without flattening away evidence source IDs."""
    protected, evidence = format_model_pair_v4(row)
    return f"{protected}\n{evidence}"


def format_model_pair_v4(row: Mapping[str, object]) -> tuple[str, str]:
    """Return protected query/answer text and separately truncatable evidence."""
    from tools.rag_guard.dataset_schema_v2 import validate_v2_row

    validate_v2_row(row)
    task = str(row["task"])
    protected = [f"query: {str(row['question']).strip()}"]
    if task == "groundedness":
        protected.append(f"answer: {str(row['answer']).strip()}")
    evidence_parts: list[str] = []
    evidence = row["evidence"]
    assert isinstance(evidence, list)  # Guaranteed by validate_v2_row.
    for item in evidence:
        assert isinstance(item, dict)
        evidence_parts.append(f"evidence [{item['source_id']}]: {str(item['text']).strip()}")
    return "\n".join(protected), "\n".join(evidence_parts)


def encode_model_pairs_v4(
    rows: Sequence[Mapping[str, object]], *, tokenizer: object, max_length: int
) -> Mapping[str, object]:
    """Tokenize v4 rows while allowing truncation only on the evidence sequence."""
    if not rows:
        raise ValueError("rows must be non-empty")
    if not isinstance(max_length, int) or isinstance(max_length, bool) or not 32 <= max_length <= 1024:
        raise ValueError("max_length must be between 32 and 1024")
    pairs = [format_model_pair_v4(row) for row in rows]
    protected = [pair[0] for pair in pairs]
    evidence = [pair[1] for pair in pairs]
    preflight = tokenizer(
        protected,
        [""] * len(protected),
        add_special_tokens=True,
        truncation=False,
        padding=False,
    )
    protected_ids = preflight.get("input_ids")
    if not isinstance(protected_ids, list) or len(protected_ids) != len(rows):
        raise ValueError("tokenizer returned invalid protected input IDs")
    if any(not isinstance(ids, list) or len(ids) > max_length for ids in protected_ids):
        raise ValueError("protected query and answer exceed max_length")
    return tokenizer(
        protected,
        evidence,
        add_special_tokens=True,
        truncation="only_second",
        max_length=max_length,
        padding=False,
    )


def load_jsonl(path: Path, *, expected_task: str, expected_split: str) -> list[dict[str, str]]:
    if expected_task not in LABELS_BY_TASK:
        raise ValueError(f"unsupported task: {expected_task!r}")
    rows: list[dict[str, str]] = []
    with path.resolve().open("r", encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            try:
                row = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"invalid JSON on line {line_number}") from error
            if not isinstance(row, dict):
                raise ValueError(f"line {line_number} must contain an object")
            if any(not isinstance(row.get(field), str) for field in _REQUIRED_TEXT_FIELDS):
                raise ValueError(f"line {line_number} has missing or non-string fields")
            if row["task"] != expected_task:
                raise ValueError(f"unexpected task on line {line_number}")
            if row["split"] != expected_split:
                raise ValueError(f"unexpected split on line {line_number}")
            if row["label"] not in LABELS_BY_TASK[expected_task]:
                raise ValueError(f"invalid label on line {line_number}")
            format_model_input(row)
            rows.append(row)
    if not rows:
        raise ValueError(f"dataset is empty: {path}")
    return rows


def load_jsonl_v4(
    path: Path, *, expected_task: str, expected_split: str
) -> list[dict[str, object]]:
    from tools.rag_guard.dataset_schema_v2 import MAX_FILE_BYTES, MAX_LINE_BYTES, validate_v2_row

    if expected_task not in LABELS_BY_TASK_V4:
        raise ValueError(f"unsupported task: {expected_task!r}")
    resolved = path.resolve(strict=True)
    if not resolved.is_file() or resolved.stat().st_size > MAX_FILE_BYTES:
        raise ValueError("dataset file is missing or too large")
    rows: list[dict[str, object]] = []
    with resolved.open("rb") as source:
        for line_number, raw_line in enumerate(source, start=1):
            if len(raw_line) > MAX_LINE_BYTES:
                raise ValueError(f"line {line_number} exceeds maximum length")
            try:
                row = json.loads(raw_line.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise ValueError(f"invalid JSON on line {line_number}") from error
            if not isinstance(row, dict):
                raise ValueError(f"line {line_number} must contain an object")
            validate_v2_row(row)
            if row["task"] != expected_task or row["split"] != expected_split:
                raise ValueError(f"unexpected task or split on line {line_number}")
            format_model_input_v4(row)
            rows.append(row)
    if not rows:
        raise ValueError(f"dataset is empty: {path}")
    return rows


def macro_f1(targets: Sequence[int], predictions: Sequence[int], class_count: int) -> float:
    if len(targets) != len(predictions) or not targets or class_count < 2:
        raise ValueError("targets and predictions must be non-empty and aligned")
    scores: list[float] = []
    for label in range(class_count):
        true_positive = sum(t == label and p == label for t, p in zip(targets, predictions))
        false_positive = sum(t != label and p == label for t, p in zip(targets, predictions))
        false_negative = sum(t == label and p != label for t, p in zip(targets, predictions))
        denominator = 2 * true_positive + false_positive + false_negative
        scores.append(0.0 if denominator == 0 else (2 * true_positive) / denominator)
    return sum(scores) / class_count


def expected_calibration_error(
    probabilities: Sequence[Sequence[float]],
    targets: Sequence[int],
    *,
    bins: int = 10,
) -> float:
    if len(probabilities) != len(targets) or not targets or bins < 1:
        raise ValueError("probabilities and targets must be non-empty and aligned")
    grouped: list[list[tuple[float, bool]]] = [[] for _ in range(bins)]
    for row, target in zip(probabilities, targets):
        if not row or any(not math.isfinite(value) or value < 0.0 or value > 1.0 for value in row):
            raise ValueError("probabilities must be finite values in [0, 1]")
        prediction = max(range(len(row)), key=row.__getitem__)
        confidence = row[prediction]
        index = min(int(confidence * bins), bins - 1)
        grouped[index].append((confidence, prediction == target))
    total = len(targets)
    error = 0.0
    for bucket in grouped:
        if bucket:
            average_confidence = sum(item[0] for item in bucket) / len(bucket)
            average_accuracy = sum(item[1] for item in bucket) / len(bucket)
            error += (len(bucket) / total) * abs(average_accuracy - average_confidence)
    return error
