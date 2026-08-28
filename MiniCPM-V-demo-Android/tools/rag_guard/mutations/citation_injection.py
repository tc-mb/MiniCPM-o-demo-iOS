"""Create controlled citation mismatches without interpreting document instructions."""

import re


SOURCE_ID = re.compile(r"^S[1-9][0-9]{0,3}$")
MAX_MUTATION_TEXT_CHARS = 100_000


def replace_citation(text: str, original_source_id: str, replacement_source_id: str) -> str:
    if not text or len(text) > MAX_MUTATION_TEXT_CHARS:
        raise ValueError("text is empty or too long")
    if (
        SOURCE_ID.fullmatch(original_source_id) is None
        or SOURCE_ID.fullmatch(replacement_source_id) is None
        or original_source_id == replacement_source_id
    ):
        raise ValueError("source ids must be distinct canonical ids")
    original = f"[{original_source_id}]"
    if text.count(original) != 1:
        raise ValueError("original citation must occur exactly once")
    return text.replace(original, f"[{replacement_source_id}]", 1)
