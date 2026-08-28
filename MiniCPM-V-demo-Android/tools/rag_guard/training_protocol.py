"""Release protocol helpers that keep the frozen test split opt-in only."""

from __future__ import annotations


def evaluation_split_names(*, evaluate_test: bool = False) -> tuple[str, ...]:
    """Return evaluation splits without exposing test data during model selection."""

    if evaluate_test:
        return ("calibration", "test")
    return ("calibration",)
