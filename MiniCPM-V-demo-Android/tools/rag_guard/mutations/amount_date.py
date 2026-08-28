"""Literal amount/date mutation helpers that do not scan unrelated identifiers."""

import re

MAX_MUTATION_TEXT_CHARS = 100_000
_NUMBER = re.compile(r"(?<!\d)(\d{1,9})(?!\d)")


def replace_exact_fact(text: str, *, original: str, replacement: str) -> str:
    if not text or len(text) > MAX_MUTATION_TEXT_CHARS:
        raise ValueError("text is empty or too long")
    if not original or not replacement or original == replacement:
        raise ValueError("mutation values must be distinct and non-empty")
    if text.count(original) != 1:
        raise ValueError("original fact must occur exactly once")
    return text.replace(original, replacement, 1)


def mutate_single_number(text: str) -> str | None:
    if not text or len(text) > MAX_MUTATION_TEXT_CHARS:
        raise ValueError("text is empty or too long")
    matches = list(_NUMBER.finditer(text))
    if len(matches) != 1:
        return None
    match = matches[0]
    original = match.group(1)
    if len(original) > 1 and original.startswith("0"):
        return None
    replacement = str(int(original) + 1)
    return text[: match.start(1)] + replacement + text[match.end(1) :]
