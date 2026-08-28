"""Deterministic QA repair helpers for the independently versioned v4.2 corpus."""

from __future__ import annotations

import re
from typing import Mapping, Sequence


MAX_VALUE_CHARS = 100_000
_EN_MONTH = re.compile(
    r"\b(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|"
    r"jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\b",
    re.IGNORECASE,
)
_EN_TEMPORAL_UNIT = re.compile(
    r"\b(?:day|days|week|weeks|month|months|year|years)\b",
    re.IGNORECASE,
)
_YEAR = re.compile(r"(?<!\d)(?:1[5-9]\d{2}|20\d{2})(?!\d)")
_ISO_DATE = re.compile(r"(?<!\d)\d{4}[-/]\d{1,2}(?:[-/]\d{1,2})?(?!\d)")
_ARABIC_NUMBER = re.compile(r"(?<!\w)\d+(?:[.,]\d+)?(?!\w)")
_ZH_NUMBER = re.compile(r"[零〇一二三四五六七八九十百千万亿两]")


def _validated(value: str, name: str) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > MAX_VALUE_CHARS:
        raise ValueError(f"{name} must be a non-empty bounded string")
    return value.strip()


def _answer_type(answer: str, language: str) -> str:
    value = _validated(answer, "answer")
    if language not in {"zh", "en"}:
        raise ValueError("language must be zh or en")
    temporal = (
        bool(_YEAR.search(value))
        or bool(_ISO_DATE.search(value))
        or (language == "en" and (bool(_EN_MONTH.search(value)) or bool(_EN_TEMPORAL_UNIT.search(value))))
        or (language == "zh" and any(marker in value for marker in "年月日天周"))
    )
    if temporal:
        return "temporal"
    if _ARABIC_NUMBER.search(value) or (language == "zh" and _ZH_NUMBER.search(value)):
        return "numeric"
    return "text"


def classify_numeric_hard_type(answer: str, language: str) -> str:
    """Separate temporal numeric mutations from amounts without source-specific shortcuts."""

    return "WRONG_DATE" if _answer_type(answer, language) == "temporal" else "WRONG_AMOUNT"


def choose_type_matched_distractor(
    answer: str,
    candidates: Sequence[str],
    *,
    language: str = "en",
) -> str | None:
    """Choose the first distinct candidate with the same coarse semantic type."""

    value = _validated(answer, "answer")
    expected_type = _answer_type(value, language)
    for candidate in candidates:
        observed = _validated(candidate, "candidate")
        if observed != value and _answer_type(observed, language) == expected_type:
            return observed
    return None


def _flat_integer_ids(encoded: Mapping[str, object]) -> list[int]:
    values = encoded.get("input_ids")
    if not isinstance(values, list) or any(not isinstance(value, int) for value in values):
        raise ValueError("tokenizer returned invalid input IDs")
    return values


def build_visible_evidence_window(
    context: str,
    *,
    required_texts: Sequence[str],
    protected_text: str,
    tokenizer: object,
    max_length: int,
    evidence_prefix: str = "",
) -> str | None:
    """Return an exact-token window that keeps all decisive evidence spans visible."""

    source = _validated(context, "context")
    protected = _validated(protected_text, "protected text")
    if not isinstance(evidence_prefix, str) or len(evidence_prefix) > MAX_VALUE_CHARS:
        raise ValueError("evidence prefix must be a bounded string")
    if not isinstance(max_length, int) or isinstance(max_length, bool) or not 32 <= max_length <= 1024:
        raise ValueError("max_length must be between 32 and 1024")
    required = tuple(_validated(value, "required text") for value in required_texts)
    protected_encoding = tokenizer(
        protected,
        "",
        add_special_tokens=True,
        truncation=False,
        padding=False,
    )
    if not isinstance(protected_encoding, Mapping):
        raise ValueError("tokenizer returned an invalid protected encoding")
    prefix_encoding = tokenizer(
        evidence_prefix,
        add_special_tokens=False,
        truncation=False,
        padding=False,
    )
    if not isinstance(prefix_encoding, Mapping):
        raise ValueError("tokenizer returned an invalid prefix encoding")
    evidence_budget = (
        max_length
        - len(_flat_integer_ids(protected_encoding))
        - len(_flat_integer_ids(prefix_encoding))
    )
    if evidence_budget < 1:
        return None
    evidence_encoding = tokenizer(
        source,
        add_special_tokens=False,
        truncation=False,
        padding=False,
        return_offsets_mapping=True,
    )
    if not isinstance(evidence_encoding, Mapping):
        raise ValueError("tokenizer returned an invalid evidence encoding")
    evidence_ids = _flat_integer_ids(evidence_encoding)
    offsets = evidence_encoding.get("offset_mapping")
    if (
        not isinstance(offsets, list)
        or len(offsets) != len(evidence_ids)
        or any(
            not isinstance(pair, (list, tuple))
            or len(pair) != 2
            or any(not isinstance(value, int) for value in pair)
            for pair in offsets
        )
    ):
        raise ValueError("tokenizer returned invalid evidence offsets")
    if not evidence_ids:
        return None
    character_spans: list[tuple[int, int]] = []
    folded_source = source.casefold()
    for value in required:
        start = folded_source.find(value.casefold())
        if start < 0:
            return None
        character_spans.append((start, start + len(value)))
    if character_spans:
        required_start = min(span[0] for span in character_spans)
        required_end = max(span[1] for span in character_spans)
        token_indices = [
            index
            for index, pair in enumerate(offsets)
            if int(pair[1]) > required_start and int(pair[0]) < required_end
        ]
        if not token_indices:
            return None
        left = min(token_indices)
        right = max(token_indices)
    else:
        left = 0
        right = 0
    required_tokens = right - left + 1
    if required_tokens > evidence_budget:
        return None
    remaining = evidence_budget - required_tokens
    before = min(left, remaining // 2)
    after = min(len(evidence_ids) - right - 1, remaining - before)
    remaining -= before + after
    if remaining:
        extra_before = min(left - before, remaining)
        before += extra_before
        remaining -= extra_before
    if remaining:
        after += min(len(evidence_ids) - right - 1 - after, remaining)
    window_left = left - before
    window_right = right + after
    start_offset = int(offsets[window_left][0])
    end_offset = int(offsets[window_right][1])
    window = source[start_offset:end_offset].strip()
    if not window or any(value.casefold() not in window.casefold() for value in required):
        return None
    final_encoding = tokenizer(
        protected,
        evidence_prefix + window,
        add_special_tokens=True,
        truncation=False,
        padding=False,
    )
    if not isinstance(final_encoding, Mapping) or len(_flat_integer_ids(final_encoding)) > max_length:
        return None
    return window
