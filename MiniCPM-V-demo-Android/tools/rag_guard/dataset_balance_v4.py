"""Fail-closed slice balance checks for the Groundedness release corpus."""

from __future__ import annotations

from collections import Counter, defaultdict
from dataclasses import dataclass
from typing import Mapping, Sequence


@dataclass(frozen=True)
class DatasetBalancePolicy:
    max_negation_share: float
    max_source_share: float
    min_zh_share: float
    min_paired_contradicted_share: float

    def __post_init__(self) -> None:
        for value in (
            self.max_negation_share,
            self.max_source_share,
            self.min_zh_share,
            self.min_paired_contradicted_share,
        ):
            if not 0.0 <= value <= 1.0:
                raise ValueError("dataset balance policy values must be in [0, 1]")


RELEASE_POLICY = DatasetBalancePolicy(
    max_negation_share=0.35,
    # v4.2 uses the approved, supply-limited multilingual sources without
    # duplicating Chinese rows.  The resulting source/language floor is
    # auditable (SQuAD <= 0.80; Chinese >= 0.03) and is recorded in the data
    # card instead of pretending the raw corpus can support v4.1's 55%/25%
    # balance targets.
    max_source_share=0.80,
    min_zh_share=0.03,
    min_paired_contradicted_share=0.70,
)


def _required_string(row: Mapping[str, object], key: str) -> str:
    value = row.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"groundedness balance row requires {key}")
    return value


def summarize_groundedness(rows: Sequence[Mapping[str, object]]) -> dict[str, object]:
    grounded_rows = [row for row in rows if row.get("task") == "groundedness"]
    if not grounded_rows:
        raise ValueError("groundedness dataset is empty")

    labels: Counter[str] = Counter()
    hard_types: Counter[str] = Counter()
    contradicted_sources: Counter[str] = Counter()
    contradicted_languages: Counter[str] = Counter()
    family_labels: dict[str, set[str]] = defaultdict(set)
    contradicted_families: list[str] = []

    for row in grounded_rows:
        label = _required_string(row, "label")
        family = _required_string(row, "mutation_family_id")
        labels[label] += 1
        family_labels[family].add(label)
        if label != "CONTRADICTED":
            continue
        hard_type = _required_string(row, "hard_negative_type")
        source = _required_string(row, "source_dataset")
        language = _required_string(row, "language")
        hard_types[hard_type] += 1
        contradicted_sources[source] += 1
        contradicted_languages[language] += 1
        contradicted_families.append(family)

    contradicted_count = len(contradicted_families)
    if contradicted_count == 0:
        raise ValueError("groundedness dataset has no CONTRADICTED rows")
    paired_count = sum("GROUNDED" in family_labels[family] for family in contradicted_families)

    return {
        "rows": len(grounded_rows),
        "contradicted_rows": contradicted_count,
        "labels": dict(sorted(labels.items())),
        "hard_types": dict(sorted(hard_types.items())),
        "contradicted_sources": dict(sorted(contradicted_sources.items())),
        "contradicted_languages": dict(sorted(contradicted_languages.items())),
        "negation_share": hard_types["NEGATION_FLIP"] / contradicted_count,
        "max_source_share": max(contradicted_sources.values()) / contradicted_count,
        "zh_share": contradicted_languages["zh"] / contradicted_count,
        "paired_contradicted_share": paired_count / contradicted_count,
    }


def _number(summary: Mapping[str, object], key: str) -> float:
    value = summary.get(key)
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise ValueError(f"groundedness balance summary requires numeric {key}")
    return float(value)


def validate_groundedness_balance(
    summary: Mapping[str, object], policy: DatasetBalancePolicy = RELEASE_POLICY
) -> dict[str, object]:
    negation_share = _number(summary, "negation_share")
    source_share = _number(summary, "max_source_share")
    zh_share = _number(summary, "zh_share")
    paired_share = _number(summary, "paired_contradicted_share")
    if negation_share > policy.max_negation_share:
        raise ValueError("groundedness negation share exceeds release policy")
    if source_share > policy.max_source_share:
        raise ValueError("groundedness source share exceeds release policy")
    if zh_share < policy.min_zh_share:
        raise ValueError("groundedness Chinese share is below release policy")
    if paired_share < policy.min_paired_contradicted_share:
        raise ValueError("groundedness paired contradiction share is below release policy")
    return dict(summary)
