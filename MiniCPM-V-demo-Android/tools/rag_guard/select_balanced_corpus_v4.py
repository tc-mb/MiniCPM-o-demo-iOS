"""Deterministic family-aware selection for a balanced Groundedness corpus."""

from __future__ import annotations

import hashlib
from collections import Counter, defaultdict
from typing import Mapping, Sequence


def _required_string(row: Mapping[str, object], key: str) -> str:
    value = row.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"balanced selector row requires {key}")
    return value


ContradictionSlice = str | tuple[str, str]


def _validate_quotas(quotas: Mapping[object, int], *, name: str) -> None:
    if not quotas or any(not isinstance(value, int) or value < 0 for value in quotas.values()):
        raise ValueError(f"{name} quotas must be non-negative integers")


def _validate_contradiction_slices(quotas: Mapping[ContradictionSlice, int]) -> None:
    for key in quotas:
        if isinstance(key, str) and key.strip():
            continue
        if (
            isinstance(key, tuple)
            and len(key) == 2
            and all(isinstance(value, str) and value.strip() for value in key)
        ):
            continue
        raise ValueError("contradiction quota keys must be hard type or (hard type, language)")


def _rank(
    rows: Sequence[Mapping[str, object]], *, seed: str, bucket: str
) -> list[Mapping[str, object]]:
    return sorted(
        rows,
        key=lambda row: hashlib.sha256(
            f"{seed}\0{bucket}\0{_required_string(row, 'id')}".encode("utf-8")
        ).hexdigest(),
    )


def select_balanced_groundedness(
    rows: Sequence[Mapping[str, object]],
    *,
    label_quotas: Mapping[str, int],
    contradiction_quotas: Mapping[ContradictionSlice, int],
    seed: str,
) -> list[dict[str, object]]:
    if not seed:
        raise ValueError("balanced selector seed is required")
    _validate_quotas(label_quotas, name="label")
    _validate_quotas(contradiction_quotas, name="contradiction")
    _validate_contradiction_slices(contradiction_quotas)
    if sum(contradiction_quotas.values()) != label_quotas.get("CONTRADICTED"):
        raise ValueError("contradiction quotas must equal the CONTRADICTED label quota")

    normalized: list[Mapping[str, object]] = []
    grounded_by_family: dict[str, list[Mapping[str, object]]] = defaultdict(list)
    contradicted_by_type: dict[str, list[Mapping[str, object]]] = defaultdict(list)
    for row in rows:
        row_id = _required_string(row, "id")
        label = _required_string(row, "label")
        family = _required_string(row, "mutation_family_id")
        if label not in label_quotas:
            continue
        normalized.append(row)
        if label == "GROUNDED":
            grounded_by_family[family].append(row)
        elif label == "CONTRADICTED":
            contradicted_by_type[_required_string(row, "hard_negative_type")].append(row)
        if row_id != row.get("id"):
            raise ValueError("row id normalization is not allowed")

    selected: dict[str, Mapping[str, object]] = {}
    for slice_key in sorted(contradiction_quotas, key=str):
        quota = contradiction_quotas[slice_key]
        if isinstance(slice_key, tuple):
            hard_type, language = slice_key
            slice_name = f"{hard_type}/{language}"
            raw_candidates = [
                row
                for row in contradicted_by_type.get(hard_type, [])
                if _required_string(row, "language") == language
            ]
        else:
            hard_type = slice_key
            slice_name = hard_type
            raw_candidates = contradicted_by_type.get(hard_type, [])
        candidates = _rank(
            raw_candidates, seed=seed, bucket=f"contradiction:{slice_name}"
        )
        if len(candidates) < quota:
            raise ValueError(
                f"{slice_name} has {len(candidates)} candidates but requires {quota}"
            )
        for candidate in candidates[:quota]:
            candidate_id = _required_string(candidate, "id")
            family = _required_string(candidate, "mutation_family_id")
            siblings = _rank(
                grounded_by_family.get(family, []), seed=seed, bucket=f"grounded-sibling:{family}"
            )
            if not siblings:
                raise ValueError(f"CONTRADICTED family {family} has no GROUNDED sibling")
            selected[candidate_id] = candidate
            sibling = siblings[0]
            selected[_required_string(sibling, "id")] = sibling

    selected_counts = Counter(_required_string(row, "label") for row in selected.values())
    for label in sorted(label_quotas):
        quota = label_quotas[label]
        current = selected_counts[label]
        if current > quota:
            raise ValueError(f"required family siblings exceed {label} quota")
        candidates = [
            row
            for row in normalized
            if _required_string(row, "label") == label
            and _required_string(row, "id") not in selected
        ]
        ranked = _rank(candidates, seed=seed, bucket=f"label:{label}")
        needed = quota - current
        if len(ranked) < needed:
            raise ValueError(f"{label} has insufficient candidates for quota {quota}")
        for row in ranked[:needed]:
            selected[_required_string(row, "id")] = row
        selected_counts[label] = quota

    result = [dict(row) for row in selected.values()]
    result.sort(key=lambda row: str(row["id"]))
    observed = Counter(str(row["label"]) for row in result)
    if any(observed[label] != quota for label, quota in label_quotas.items()):
        raise RuntimeError("balanced selector failed to satisfy label quotas")
    return result
