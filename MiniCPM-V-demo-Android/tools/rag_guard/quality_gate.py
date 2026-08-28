"""Dependency-free release gate for independently scored, redacted office data.

This module intentionally does not run model inference.  The scorer writes the
three probabilities for each example; this gate validates provenance, prevents
document leakage, chooses the answerability threshold on the office calibration
split, and evaluates that frozen threshold on a separate office test split.
"""

from __future__ import annotations

import argparse
import json
import math
import re
from dataclasses import asdict, dataclass
from itertools import combinations
from pathlib import Path
from typing import Iterable, Mapping, Sequence

from tools.rag_guard.training_data import (
    LABELS_BY_TASK,
    expected_calibration_error,
    macro_f1,
)


_PHONE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
_IDENTITY_NUMBER = re.compile(r"(?<!\d)\d{17}[0-9Xx](?!\d)")
_TEXT_FIELDS = ("question", "evidence", "answer")
APPROVED_PROVENANCE = {
    "real_office_redacted": ("reviewed", "production_office_qualification"),
    "public_office_licensed": (
        "public_source_reviewed",
        "public_prequalification_only",
    ),
}


@dataclass(frozen=True)
class ThresholdSelection:
    threshold: float
    precision: float
    recall: float


@dataclass(frozen=True)
class QualityGateRequirements:
    minimum_answerability_precision: float = 0.95
    minimum_answerability_recall: float = 0.90
    minimum_groundedness_macro_f1: float = 0.85
    maximum_groundedness_ece: float = 0.10
    minimum_groundedness_precision: float = 0.95
    minimum_groundedness_recall: float = 0.50
    minimum_examples_per_task: int = 100

    def __post_init__(self) -> None:
        for value in (
            self.minimum_answerability_precision,
            self.minimum_answerability_recall,
            self.minimum_groundedness_macro_f1,
            self.maximum_groundedness_ece,
            self.minimum_groundedness_precision,
            self.minimum_groundedness_recall,
        ):
            if not math.isfinite(value) or not 0.0 <= value <= 1.0:
                raise ValueError("quality requirements must be finite values in [0, 1]")
        if self.minimum_examples_per_task < 1:
            raise ValueError("minimum_examples_per_task must be positive")


@dataclass(frozen=True)
class QualityGateReport:
    passed: bool
    distribution: str
    qualification_scope: str
    classifier_sha256: str
    tokenizer_sha256: str
    answerability_threshold: float
    answerability_precision: float
    answerability_recall: float
    groundedness_threshold: float
    groundedness_precision: float
    groundedness_recall: float
    groundedness_macro_f1: float
    groundedness_ece: float
    answerability_test_count: int
    groundedness_test_count: int


def load_scored_jsonl(path: Path) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    with path.resolve().open("r", encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            try:
                value = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"invalid JSON on line {line_number}") from error
            if not isinstance(value, dict):
                raise ValueError(f"line {line_number} must contain an object")
            rows.append(value)
    if not rows:
        raise ValueError("scored evaluation file must not be empty")
    return rows


def validate_redacted_text(value: str) -> None:
    if _PHONE.search(value) or _IDENTITY_NUMBER.search(value):
        raise ValueError("sensitive identifier remains in redacted evaluation text")


def assert_document_isolation(splits: Mapping[str, set[str]]) -> None:
    for (left_name, left_ids), (right_name, right_ids) in combinations(splits.items(), 2):
        overlap = left_ids.intersection(right_ids)
        if overlap:
            raise ValueError(
                f"document leakage between {left_name} and {right_name}: {len(overlap)} document(s)"
            )


def _validated_rows(
    rows: Iterable[Mapping[str, object]],
    *,
    expected_model_sha256: str | None = None,
    expected_tokenizer_sha256: str | None = None,
    expected_distribution: str = "real_office_redacted",
) -> list[Mapping[str, object]]:
    if expected_distribution not in APPROVED_PROVENANCE:
        raise ValueError("unsupported expected evaluation distribution")
    expected_redaction_status = APPROVED_PROVENANCE[expected_distribution][0]
    validated: list[Mapping[str, object]] = []
    seen_ids: set[str] = set()
    for row in rows:
        row_id = row.get("id")
        task = row.get("task")
        label = row.get("label")
        document_id = row.get("document_id")
        if not isinstance(row_id, str) or not row_id or row_id in seen_ids:
            raise ValueError("evaluation IDs must be non-empty and unique within a split")
        if (
            not isinstance(task, str)
            or not isinstance(label, str)
            or task not in LABELS_BY_TASK
            or label not in LABELS_BY_TASK[task]
        ):
            raise ValueError(f"invalid task or label for {row_id}")
        if not isinstance(document_id, str) or not document_id:
            raise ValueError(f"missing document_id for {row_id}")
        if row.get("distribution") != expected_distribution:
            raise ValueError(f"unapproved evaluation distribution for {row_id}")
        if row.get("redaction_status") != expected_redaction_status:
            raise ValueError(f"evaluation row has not been reviewed for redaction: {row_id}")
        model_sha256 = row.get("model_sha256")
        tokenizer_sha256 = row.get("tokenizer_sha256")
        for name, value in (
            ("model", model_sha256),
            ("tokenizer", tokenizer_sha256),
        ):
            if (
                not isinstance(value, str)
                or len(value) != 64
                or any(character not in "0123456789abcdef" for character in value)
            ):
                raise ValueError(f"invalid {name} SHA-256 for {row_id}")
        if expected_model_sha256 is not None and model_sha256 != expected_model_sha256:
            raise ValueError(f"model SHA-256 mismatch for {row_id}")
        if expected_tokenizer_sha256 is not None and tokenizer_sha256 != expected_tokenizer_sha256:
            raise ValueError(f"tokenizer SHA-256 mismatch for {row_id}")
        for field in _TEXT_FIELDS:
            value = row.get(field)
            if not isinstance(value, str):
                raise ValueError(f"missing text field {field} for {row_id}")
            validate_redacted_text(value)
        probabilities = row.get("probabilities")
        if (
            not isinstance(probabilities, list)
            or len(probabilities) != 3
            or any(
                not isinstance(value, (int, float))
                or isinstance(value, bool)
                or not math.isfinite(value)
                or not 0.0 <= value <= 1.0
                for value in probabilities
            )
            or not math.isclose(sum(probabilities), 1.0, abs_tol=1e-4)
        ):
            raise ValueError(f"invalid probability vector for {row_id}")
        seen_ids.add(row_id)
        validated.append(row)
    if not validated:
        raise ValueError("evaluation split must not be empty")
    return validated


def _binary_metrics(targets: Sequence[bool], predictions: Sequence[bool]) -> tuple[float, float]:
    true_positive = sum(target and predicted for target, predicted in zip(targets, predictions))
    false_positive = sum(not target and predicted for target, predicted in zip(targets, predictions))
    false_negative = sum(target and not predicted for target, predicted in zip(targets, predictions))
    precision = true_positive / (true_positive + false_positive) if true_positive + false_positive else 0.0
    recall = true_positive / (true_positive + false_negative) if true_positive + false_negative else 0.0
    return precision, recall


def _answerability_metrics(
    rows: Sequence[Mapping[str, object]], threshold: float
) -> tuple[float, float]:
    targets = [row["label"] == "SUPPORTED" for row in rows]
    predictions = []
    for row in rows:
        probabilities = row["probabilities"]
        assert isinstance(probabilities, list)
        predictions.append(
            max(range(3), key=probabilities.__getitem__) == 0 and probabilities[0] >= threshold
        )
    return _binary_metrics(targets, predictions)


def _groundedness_metrics(
    rows: Sequence[Mapping[str, object]], threshold: float
) -> tuple[float, float]:
    targets = [row["label"] == "GROUNDED" for row in rows]
    predictions = []
    for row in rows:
        probabilities = row["probabilities"]
        assert isinstance(probabilities, list)
        predictions.append(
            max(range(3), key=probabilities.__getitem__) == 0 and probabilities[0] >= threshold
        )
    return _binary_metrics(targets, predictions)


def select_answerability_threshold(
    rows: Sequence[Mapping[str, object]],
    *,
    minimum_precision: float,
    expected_distribution: str = "real_office_redacted",
) -> ThresholdSelection:
    if not math.isfinite(minimum_precision) or not 0.0 <= minimum_precision <= 1.0:
        raise ValueError("minimum_precision must be a finite value in [0, 1]")
    answerability = [
        row
        for row in _validated_rows(rows, expected_distribution=expected_distribution)
        if row["task"] == "answerability"
    ]
    if not answerability or not any(row["label"] == "SUPPORTED" for row in answerability):
        raise ValueError("answerability calibration requires supported examples")
    thresholds = sorted(
        {float(row["probabilities"][0]) for row in answerability},  # type: ignore[index]
        reverse=True,
    )
    eligible: list[ThresholdSelection] = []
    for threshold in thresholds:
        precision, recall = _answerability_metrics(answerability, threshold)
        if precision >= minimum_precision:
            eligible.append(ThresholdSelection(threshold, precision, recall))
    if not eligible:
        raise ValueError("no answerability threshold satisfies minimum precision")
    return max(eligible, key=lambda result: (result.recall, result.precision, result.threshold))


def select_groundedness_threshold(
    rows: Sequence[Mapping[str, object]],
    *,
    minimum_precision: float,
    expected_distribution: str = "real_office_redacted",
) -> ThresholdSelection:
    if not math.isfinite(minimum_precision) or not 0.0 <= minimum_precision <= 1.0:
        raise ValueError("minimum_precision must be a finite value in [0, 1]")
    groundedness = [
        row
        for row in _validated_rows(rows, expected_distribution=expected_distribution)
        if row["task"] == "groundedness"
    ]
    if not groundedness or not any(row["label"] == "GROUNDED" for row in groundedness):
        raise ValueError("groundedness calibration requires grounded examples")
    thresholds = sorted(
        {float(row["probabilities"][0]) for row in groundedness},  # type: ignore[index]
        reverse=True,
    )
    eligible: list[ThresholdSelection] = []
    for threshold in thresholds:
        precision, recall = _groundedness_metrics(groundedness, threshold)
        if precision >= minimum_precision:
            eligible.append(ThresholdSelection(threshold, precision, recall))
    if not eligible:
        raise ValueError("no groundedness threshold satisfies minimum precision")
    return max(eligible, key=lambda result: (result.recall, result.precision, result.threshold))


def evaluate_quality_gate(
    office_calibration_rows: Sequence[Mapping[str, object]],
    office_test_rows: Sequence[Mapping[str, object]],
    *,
    training_document_ids: set[str],
    classifier_sha256: str,
    tokenizer_sha256: str,
    requirements: QualityGateRequirements = QualityGateRequirements(),
    expected_distribution: str = "real_office_redacted",
) -> QualityGateReport:
    calibration = _validated_rows(
        office_calibration_rows,
        expected_model_sha256=classifier_sha256,
        expected_tokenizer_sha256=tokenizer_sha256,
        expected_distribution=expected_distribution,
    )
    test = _validated_rows(
        office_test_rows,
        expected_model_sha256=classifier_sha256,
        expected_tokenizer_sha256=tokenizer_sha256,
        expected_distribution=expected_distribution,
    )
    assert_document_isolation(
        {
            "training": set(training_document_ids),
            "office_calibration": {str(row["document_id"]) for row in calibration},
            "office_test": {str(row["document_id"]) for row in test},
        }
    )
    selection = select_answerability_threshold(
        [row for row in calibration if row["task"] == "answerability"],
        minimum_precision=requirements.minimum_answerability_precision,
        expected_distribution=expected_distribution,
    )
    grounded_selection = select_groundedness_threshold(
        [row for row in calibration if row["task"] == "groundedness"],
        minimum_precision=requirements.minimum_groundedness_precision,
        expected_distribution=expected_distribution,
    )
    answerability = [row for row in test if row["task"] == "answerability"]
    groundedness = [row for row in test if row["task"] == "groundedness"]
    if len(answerability) < requirements.minimum_examples_per_task:
        raise ValueError("insufficient independent answerability test examples")
    if len(groundedness) < requirements.minimum_examples_per_task:
        raise ValueError("insufficient independent groundedness test examples")

    answer_precision, answer_recall = _answerability_metrics(
        answerability, selection.threshold
    )
    grounded_precision, grounded_recall = _groundedness_metrics(
        groundedness, grounded_selection.threshold
    )
    ground_targets = [LABELS_BY_TASK["groundedness"].index(str(row["label"])) for row in groundedness]
    ground_probabilities = [row["probabilities"] for row in groundedness]
    ground_predictions = [
        max(range(3), key=probabilities.__getitem__)  # type: ignore[union-attr]
        for probabilities in ground_probabilities
    ]
    ground_f1 = macro_f1(ground_targets, ground_predictions, 3)
    ground_ece = expected_calibration_error(ground_probabilities, ground_targets)  # type: ignore[arg-type]
    passed = (
        answer_precision >= requirements.minimum_answerability_precision
        and answer_recall >= requirements.minimum_answerability_recall
        and ground_f1 >= requirements.minimum_groundedness_macro_f1
        and ground_ece <= requirements.maximum_groundedness_ece
        and grounded_precision >= requirements.minimum_groundedness_precision
        and grounded_recall >= requirements.minimum_groundedness_recall
    )
    return QualityGateReport(
        passed=passed,
        distribution=expected_distribution,
        qualification_scope=APPROVED_PROVENANCE[expected_distribution][1],
        classifier_sha256=classifier_sha256,
        tokenizer_sha256=tokenizer_sha256,
        answerability_threshold=selection.threshold,
        answerability_precision=answer_precision,
        answerability_recall=answer_recall,
        groundedness_threshold=grounded_selection.threshold,
        groundedness_precision=grounded_precision,
        groundedness_recall=grounded_recall,
        groundedness_macro_f1=ground_f1,
        groundedness_ece=ground_ece,
        answerability_test_count=len(answerability),
        groundedness_test_count=len(groundedness),
    )


def _write_report(path: Path, report: QualityGateReport) -> None:
    path = path.resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(asdict(report), ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def _load_document_ids(path: Path) -> set[str]:
    values = {line.strip() for line in path.resolve().read_text(encoding="utf-8").splitlines()}
    values.discard("")
    return values


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Evaluate the pinned RAG guard on independent redacted office scores."
    )
    parser.add_argument("--office-calibration", type=Path, required=True)
    parser.add_argument("--office-test", type=Path, required=True)
    parser.add_argument("--training-document-ids", type=Path, required=True)
    parser.add_argument("--classifier-sha256", required=True)
    parser.add_argument("--tokenizer-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--distribution",
        choices=tuple(APPROVED_PROVENANCE),
        default="real_office_redacted",
    )
    parser.add_argument("--minimum-answerability-precision", type=float, default=0.95)
    parser.add_argument("--minimum-answerability-recall", type=float, default=0.90)
    parser.add_argument("--minimum-groundedness-macro-f1", type=float, default=0.85)
    parser.add_argument("--maximum-groundedness-ece", type=float, default=0.10)
    parser.add_argument("--minimum-groundedness-precision", type=float, default=0.95)
    parser.add_argument("--minimum-groundedness-recall", type=float, default=0.50)
    parser.add_argument("--minimum-examples-per-task", type=int, default=100)
    return parser.parse_args()


def main() -> int:
    arguments = _parse_args()
    requirements = QualityGateRequirements(
        minimum_answerability_precision=arguments.minimum_answerability_precision,
        minimum_answerability_recall=arguments.minimum_answerability_recall,
        minimum_groundedness_macro_f1=arguments.minimum_groundedness_macro_f1,
        maximum_groundedness_ece=arguments.maximum_groundedness_ece,
        minimum_groundedness_precision=arguments.minimum_groundedness_precision,
        minimum_groundedness_recall=arguments.minimum_groundedness_recall,
        minimum_examples_per_task=arguments.minimum_examples_per_task,
    )
    report = evaluate_quality_gate(
        load_scored_jsonl(arguments.office_calibration),
        load_scored_jsonl(arguments.office_test),
        training_document_ids=_load_document_ids(arguments.training_document_ids),
        classifier_sha256=arguments.classifier_sha256,
        tokenizer_sha256=arguments.tokenizer_sha256,
        requirements=requirements,
        expected_distribution=arguments.distribution,
    )
    _write_report(arguments.output, report)
    print(json.dumps(asdict(report), ensure_ascii=False, sort_keys=True))
    return 0 if report.passed else 2


if __name__ == "__main__":
    raise SystemExit(main())
