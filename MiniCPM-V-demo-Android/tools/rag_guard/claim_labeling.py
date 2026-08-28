"""Aggregate atomic evidence relations into the v4 Groundedness label."""

from __future__ import annotations

from collections.abc import Sequence


VALID_SUPPORT_LABELS = {"entailed", "missing", "contradicted"}


def aggregate_claim_support(labels: Sequence[str]) -> str:
    if not labels:
        raise ValueError("at least one material claim is required")
    if any(label not in VALID_SUPPORT_LABELS for label in labels):
        raise ValueError("invalid atomic support label")
    if "contradicted" in labels:
        return "CONTRADICTED"
    entailed = labels.count("entailed")
    if entailed == len(labels):
        return "GROUNDED"
    if entailed > 0:
        return "PARTIAL"
    return "UNSUPPORTED"
