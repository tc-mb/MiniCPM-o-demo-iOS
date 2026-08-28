"""Text-free per-row training dynamics for ambiguity and label-review triage."""

from __future__ import annotations

import math
from collections import defaultdict
from typing import Mapping


class TrainingDynamicsRecorder:
    def __init__(self) -> None:
        self._rows: dict[str, dict[int, tuple[str, int, int, float]]] = defaultdict(dict)

    def record(
        self,
        row_id: str,
        *,
        task: str,
        epoch: int,
        gold_label: int,
        predicted_label: int,
        gold_probability: float,
    ) -> None:
        if not isinstance(row_id, str) or not row_id.strip() or not isinstance(task, str) or not task.strip():
            raise ValueError("row ID and task must be non-empty strings")
        if not isinstance(epoch, int) or isinstance(epoch, bool) or epoch < 1:
            raise ValueError("epoch must be a positive integer")
        if any(not isinstance(value, int) or isinstance(value, bool) or value < 0 for value in (gold_label, predicted_label)):
            raise ValueError("labels must be non-negative integers")
        if not isinstance(gold_probability, (int, float)) or isinstance(gold_probability, bool):
            raise ValueError("gold probability must be numeric")
        probability = float(gold_probability)
        if not math.isfinite(probability) or not 0.0 <= probability <= 1.0:
            raise ValueError("gold probability must be finite and in [0, 1]")
        observations = self._rows[row_id]
        if epoch in observations:
            raise ValueError("duplicate epoch observation for row")
        if observations:
            prior_task, prior_gold, _prediction, _probability = next(iter(observations.values()))
            if prior_task != task or prior_gold != gold_label:
                raise ValueError("row task and gold label must remain stable")
        observations[epoch] = (task, gold_label, predicted_label, probability)

    def summarize(self) -> dict[str, dict[str, object]]:
        result: dict[str, dict[str, object]] = {}
        for row_id in sorted(self._rows):
            ordered = [self._rows[row_id][epoch] for epoch in sorted(self._rows[row_id])]
            task, gold_label, _prediction, _probability = ordered[0]
            probabilities = [item[3] for item in ordered]
            predictions = [item[2] for item in ordered]
            mean = sum(probabilities) / len(probabilities)
            variability = math.sqrt(
                sum((probability - mean) ** 2 for probability in probabilities) / len(probabilities)
            )
            flips = sum(left != right for left, right in zip(predictions, predictions[1:]))
            result[row_id] = {
                "row_id": row_id,
                "task": task,
                "gold_label": gold_label,
                "observations": len(ordered),
                "mean_gold_probability": mean,
                "variability": variability,
                "prediction_flip_count": flips,
            }
        return result


def _number(row: Mapping[str, object], key: str) -> float:
    value = row.get(key)
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise ValueError(f"training dynamics summary requires numeric {key}")
    return float(value)


def select_review_rows(
    summaries: Mapping[str, Mapping[str, object]],
    *,
    max_mean_gold_probability: float,
    min_variability: float,
    min_prediction_flips: int,
) -> list[dict[str, object]]:
    if not 0.0 <= max_mean_gold_probability <= 1.0 or not 0.0 <= min_variability <= 1.0:
        raise ValueError("review probability thresholds must be in [0, 1]")
    if not isinstance(min_prediction_flips, int) or min_prediction_flips < 0:
        raise ValueError("minimum prediction flips must be non-negative")
    selected: list[dict[str, object]] = []
    for row_id in sorted(summaries):
        summary = summaries[row_id]
        observed_id = summary.get("row_id")
        if observed_id != row_id:
            raise ValueError("training dynamics summary row ID mismatch")
        mean = _number(summary, "mean_gold_probability")
        variability = _number(summary, "variability")
        flips = _number(summary, "prediction_flip_count")
        if (
            mean <= max_mean_gold_probability
            or variability >= min_variability
            or flips >= min_prediction_flips
        ):
            selected.append(dict(summary))
    return selected
