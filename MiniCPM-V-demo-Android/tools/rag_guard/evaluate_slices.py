"""Hard release gates and deterministic checkpoint ordering for RAG Guard v4."""

from __future__ import annotations

from typing import Mapping, Sequence


def per_class_metrics(
    targets: Sequence[int], predictions: Sequence[int], labels: Sequence[str]
) -> dict[str, dict[str, float]]:
    if len(targets) != len(predictions) or not targets or not labels:
        raise ValueError("targets, predictions, and labels must be aligned and non-empty")
    result: dict[str, dict[str, float]] = {}
    for index, label in enumerate(labels):
        true_positive = sum(target == index and prediction == index for target, prediction in zip(targets, predictions))
        false_positive = sum(target != index and prediction == index for target, prediction in zip(targets, predictions))
        false_negative = sum(target == index and prediction != index for target, prediction in zip(targets, predictions))
        result[label] = {
            "precision": 0.0 if true_positive + false_positive == 0 else true_positive / (true_positive + false_positive),
            "recall": 0.0 if true_positive + false_negative == 0 else true_positive / (true_positive + false_negative),
        }
    return result


def _number(value: object) -> float:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise ValueError("required metric is missing or non-numeric")
    result = float(value)
    if not 0.0 <= result <= 1.0:
        raise ValueError("metric must be in [0, 1]")
    return result


def _required_metrics(metrics: Mapping[str, object]) -> tuple[float, float, float, float, float]:
    answerability = metrics.get("answerability")
    groundedness = metrics.get("groundedness")
    hard_slices = metrics.get("hard_slices")
    if not isinstance(answerability, Mapping) or not isinstance(groundedness, Mapping):
        raise ValueError("task metrics are required")
    per_class = groundedness.get("per_class")
    if not isinstance(per_class, Mapping):
        raise ValueError("per-class metrics are required")
    contradicted = per_class.get("CONTRADICTED")
    if not isinstance(contradicted, Mapping):
        raise ValueError("CONTRADICTED metrics are required")
    if not isinstance(hard_slices, Mapping) or not hard_slices:
        raise ValueError("hard-slice metrics are required")
    recalls: list[float] = []
    for value in hard_slices.values():
        if not isinstance(value, Mapping):
            raise ValueError("hard-slice entry must be an object")
        recalls.append(_number(value.get("recall")))
    return (
        _number(answerability.get("macro_f1")),
        _number(groundedness.get("macro_f1")),
        _number(contradicted.get("precision")),
        min(recalls),
        _number(groundedness.get("ece")),
    )


def eligible_checkpoint(metrics: Mapping[str, object]) -> bool:
    try:
        answerability_f1, groundedness_f1, contradicted_precision, _worst_recall, _ece = _required_metrics(metrics)
    except ValueError:
        return False
    return (
        answerability_f1 >= 0.95
        and groundedness_f1 >= 0.88
        and contradicted_precision >= 0.98
    )


def checkpoint_rank(metrics: Mapping[str, object]) -> tuple[float, float, float]:
    if not eligible_checkpoint(metrics):
        raise ValueError("checkpoint does not satisfy hard eligibility gates")
    _answerability_f1, groundedness_f1, _precision, worst_recall, ece = _required_metrics(metrics)
    return (worst_recall, groundedness_f1, -ece)


def checkpoint_selection_rank(metrics: Mapping[str, object]) -> tuple[float, float, float, float, float, float]:
    """Rank every valid calibration result without weakening the release gates."""

    answerability_f1, groundedness_f1, contradicted_precision, worst_recall, ece = _required_metrics(metrics)
    gate_coverage = min(
        answerability_f1 / 0.95,
        groundedness_f1 / 0.88,
        contradicted_precision / 0.98,
    )
    return (
        1.0 if eligible_checkpoint(metrics) else 0.0,
        gate_coverage,
        worst_recall,
        groundedness_f1,
        answerability_f1,
        -ece,
    )
