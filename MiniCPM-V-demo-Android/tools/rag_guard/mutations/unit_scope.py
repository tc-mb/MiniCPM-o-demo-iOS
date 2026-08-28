"""Bounded unit mutations for factual contrast examples."""

from __future__ import annotations

import re


MAX_MUTATION_TEXT_CHARS = 100_000
_UNITS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"(?<![A-Za-z])days(?![A-Za-z])", re.IGNORECASE), "months"),
    (re.compile(r"(?<![A-Za-z])day(?![A-Za-z])", re.IGNORECASE), "month"),
    (re.compile(r"(?<![A-Za-z])years(?![A-Za-z])", re.IGNORECASE), "months"),
    (re.compile(r"(?<![A-Za-z])year(?![A-Za-z])", re.IGNORECASE), "month"),
    (re.compile("个月"), "天"),
    (re.compile("天"), "个月"),
    (re.compile("万元"), "元"),
    (re.compile("元"), "万元"),
)


def mutate_single_unit(text: str) -> str | None:
    if not text or len(text) > MAX_MUTATION_TEXT_CHARS:
        raise ValueError("text is empty or too long")
    matches: list[tuple[re.Match[str], str]] = []
    occupied: set[tuple[int, int]] = set()
    for pattern, replacement in _UNITS:
        for match in pattern.finditer(text):
            span = (match.start(), match.end())
            if span not in occupied:
                occupied.add(span)
                matches.append((match, replacement))
    if len(matches) != 1:
        return None
    match, replacement = matches[0]
    if match.group(0)[:1].isupper():
        replacement = replacement[:1].upper() + replacement[1:]
    return text[: match.start()] + replacement + text[match.end() :]
