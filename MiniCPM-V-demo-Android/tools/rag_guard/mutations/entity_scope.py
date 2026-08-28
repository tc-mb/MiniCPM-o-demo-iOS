"""Literal entity, polarity, and scope mutation helpers."""

import re

MAX_MUTATION_TEXT_CHARS = 100_000
_SCOPES: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"(?<![A-Za-z])permitted(?![A-Za-z])", re.IGNORECASE), "prohibited"),
    (re.compile(r"(?<![A-Za-z])prohibited(?![A-Za-z])", re.IGNORECASE), "permitted"),
    (re.compile(r"(?<![A-Za-z])required(?![A-Za-z])", re.IGNORECASE), "prohibited"),
    (re.compile("可以"), "不得"),
    (re.compile("允许"), "禁止"),
    (re.compile("必须"), "不得"),
    (re.compile("不得"), "可以"),
)


def replace_exact_entity(text: str, original: str, replacement: str) -> str:
    if not text or len(text) > MAX_MUTATION_TEXT_CHARS:
        raise ValueError("text is empty or too long")
    if not original or not replacement or original == replacement:
        raise ValueError("mutation values must be distinct and non-empty")
    if text.count(original) != 1:
        raise ValueError("original entity must occur exactly once")
    return text.replace(original, replacement, 1)


def mutate_single_scope(text: str) -> str | None:
    if not text or len(text) > MAX_MUTATION_TEXT_CHARS:
        raise ValueError("text is empty or too long")
    matches: list[tuple[re.Match[str], str]] = []
    occupied: set[tuple[int, int]] = set()
    for pattern, replacement in _SCOPES:
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
