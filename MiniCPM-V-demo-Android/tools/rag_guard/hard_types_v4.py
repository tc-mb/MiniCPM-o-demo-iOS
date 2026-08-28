"""Release contradiction taxonomy and deterministic family-pair rotation."""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Sequence


RELEASE_CONTRADICTION_TYPES: tuple[str, ...] = (
    "CONTRACT_CONTRADICTION",
    "MULTI_HOP_CONTRADICTION",
    "NEGATION_FLIP",
    "SCOPE_FLIP",
    "WRONG_AMOUNT",
    "WRONG_DATE",
    "WRONG_ENTITY",
    "WRONG_UNIT",
)


def build_pair_groups(
    pair_ids: Sequence[int], pair_roles: Sequence[int]
) -> tuple[tuple[int, tuple[int, ...]], ...]:
    """Collect one grounded index and every contradicted sibling for each family."""
    if len(pair_ids) != len(pair_roles):
        raise ValueError("pair IDs and roles must be aligned")
    grouped: dict[int, dict[int, list[int]]] = defaultdict(lambda: defaultdict(list))
    for index, (pair_id, role) in enumerate(zip(pair_ids, pair_roles)):
        if not isinstance(pair_id, int) or not isinstance(role, int):
            raise ValueError("pair IDs and roles must be integers")
        if pair_id < 0 or role == 0:
            continue
        if role not in (-1, 1):
            raise ValueError("pair roles must be -1, 0, or 1")
        grouped[pair_id][role].append(index)
    result: list[tuple[int, tuple[int, ...]]] = []
    for pair_id in sorted(grouped):
        roles = grouped[pair_id]
        positives = roles.get(1, [])
        negatives = roles.get(-1, [])
        if len(positives) != 1:
            raise ValueError("each pair family requires exactly one grounded sibling")
        if negatives:
            result.append((positives[0], tuple(negatives)))
    return tuple(result)


def select_pair_members(
    groups: Sequence[tuple[int, Sequence[int]]], *, epoch: int
) -> tuple[tuple[int, int], ...]:
    """Select one different contradicted sibling per family on successive epochs."""
    if not isinstance(epoch, int) or isinstance(epoch, bool) or epoch < 0:
        raise ValueError("epoch must be a non-negative integer")
    selected: list[tuple[int, int]] = []
    for positive, negatives in groups:
        if not negatives:
            raise ValueError("pair group requires a contradicted sibling")
        selected.append((positive, int(negatives[epoch % len(negatives)])))
    return tuple(selected)
