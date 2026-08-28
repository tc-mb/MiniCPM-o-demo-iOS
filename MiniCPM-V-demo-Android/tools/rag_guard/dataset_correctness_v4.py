"""Fail-closed correctness gates for RAG Guard v4.1 corpus generation."""

from __future__ import annotations

from collections import Counter, defaultdict
from dataclasses import dataclass
import re
from typing import Mapping, Sequence

from tools.rag_guard.training_data import format_model_pair_v4


@dataclass(frozen=True)
class CorrectnessPolicy:
    max_exact_answer_share: float = 0.20
    max_source_label_share: float = 0.80
    min_source_rows: int = 100
    require_tokenizer: bool = True

    def __post_init__(self) -> None:
        for value in (self.max_exact_answer_share, self.max_source_label_share):
            if not isinstance(value, (int, float)) or isinstance(value, bool) or not 0.0 <= float(value) <= 1.0:
                raise ValueError("correctness shares must be in [0, 1]")
        if not isinstance(self.min_source_rows, int) or isinstance(self.min_source_rows, bool) or self.min_source_rows < 1:
            raise ValueError("min_source_rows must be positive")


RELEASE_CORRECTNESS_POLICY = CorrectnessPolicy()


_QA_SOURCE_DATASETS = {"SQuAD 2.0", "CMRC 2018"}
_QA_ANSWER_PREFIXES = ("the answer is ", "答案是")


def _normalized_text(value: object) -> str:
    if not isinstance(value, str):
        return ""
    return re.sub(r"\s+", " ", value.casefold().strip()).strip(" .。！？?!")


def _qa_grounded_answer(answer: object) -> str:
    text = _normalized_text(answer)
    for prefix in _QA_ANSWER_PREFIXES:
        if text.startswith(prefix):
            return text[len(prefix):].strip(" .。！？?!")
    return text


def _decisive_qa_evidence_not_visible_count(
    rows: Sequence[Mapping[str, object]],
) -> int:
    """Count QA family rows whose true answer is absent from the visible evidence.

    Only SQuAD/CMRC generated families have a single extractive answer that can
    be checked without a model. ContractNLI and HoVer are relation/multi-hop
    claims, so their evidence requires the task-specific annotation semantics.
    """
    families: dict[str, list[Mapping[str, object]]] = defaultdict(list)
    grounded_answers: dict[str, str] = {}
    for row in rows:
        if row.get("task") != "groundedness" or row.get("source_dataset") not in _QA_SOURCE_DATASETS:
            continue
        family = row.get("mutation_family_id")
        if not isinstance(family, str) or not family:
            continue
        families[family].append(row)
        if row.get("label") == "GROUNDED":
            answer = _qa_grounded_answer(row.get("answer"))
            if answer:
                grounded_answers[family] = answer
    invisible = 0
    for family, family_rows in families.items():
        answer = grounded_answers.get(family)
        if not answer:
            continue
        for row in family_rows:
            evidence = row.get("evidence")
            visible = " ".join(
                str(item.get("text", ""))
                for item in evidence
                if isinstance(item, Mapping)
            ) if isinstance(evidence, list) else ""
            if answer not in _normalized_text(visible):
                invisible += 1
    return invisible


def filter_protected_input_budget(
    rows: Sequence[Mapping[str, object]], *, tokenizer: object, max_length: int
) -> tuple[list[Mapping[str, object]], list[str]]:
    """Remove rows whose protected query/answer cannot fit without truncation."""
    if not isinstance(max_length, int) or isinstance(max_length, bool) or not 32 <= max_length <= 1024:
        raise ValueError("max_length must be between 32 and 1024")
    accepted: list[Mapping[str, object]] = []
    rejected: list[str] = []
    batch_size = 1024
    for start in range(0, len(rows), batch_size):
        batch = rows[start : start + batch_size]
        protected = [format_model_pair_v4(row)[0] for row in batch]
        encoded = tokenizer(
            protected,
            [""] * len(protected),
            add_special_tokens=True,
            truncation=False,
            padding=False,
        )
        input_ids = encoded.get("input_ids")
        if not isinstance(input_ids, list) or len(input_ids) != len(batch):
            raise ValueError("tokenizer returned invalid protected input IDs")
        for row, ids in zip(batch, input_ids):
            if not isinstance(ids, list):
                raise ValueError("tokenizer returned invalid protected input IDs")
            if len(ids) > max_length:
                row_id = row.get("id")
                if not isinstance(row_id, str) or not row_id:
                    raise ValueError("overflow row requires a string ID")
                rejected.append(row_id)
            else:
                accepted.append(row)
    return accepted, rejected


def filter_orphaned_contradiction_families(
    rows: Sequence[Mapping[str, object]],
) -> tuple[list[Mapping[str, object]], list[str]]:
    """Remove families whose contradiction lost its required grounded sibling."""
    family_labels: dict[str, set[str]] = defaultdict(set)
    for row in rows:
        family = row.get("mutation_family_id")
        label = row.get("label")
        if not isinstance(family, str) or not family or not isinstance(label, str) or not label:
            raise ValueError("family filtering requires string family and label")
        family_labels[family].add(label)
    rejected = sorted(
        family
        for family, labels in family_labels.items()
        if "CONTRADICTED" in labels and "GROUNDED" not in labels
    )
    rejected_set = set(rejected)
    return [row for row in rows if row["mutation_family_id"] not in rejected_set], rejected


def _protected_overflow_count(
    rows: Sequence[Mapping[str, object]], *, tokenizer: object, max_length: int
) -> int:
    _accepted, rejected = filter_protected_input_budget(
        rows, tokenizer=tokenizer, max_length=max_length
    )
    return len(rejected)


def summarize_dataset_correctness(
    rows: Sequence[Mapping[str, object]],
    *,
    tokenizer: object | None = None,
    max_length: int = 256,
) -> dict[str, object]:
    if not rows:
        raise ValueError("dataset is empty")
    label_answers: dict[str, Counter[str]] = defaultdict(Counter)
    source_labels: dict[tuple[str, str], Counter[str]] = defaultdict(Counter)
    untrusted_hover = 0
    for row in rows:
        task = str(row.get("task", ""))
        label = str(row.get("label", ""))
        source = str(row.get("source_dataset", ""))
        source_labels[(task, source)][label] += 1
        if task == "groundedness":
            answer = str(row.get("answer", "")).strip()
            if answer:
                label_answers[label][answer] += 1
            if (
                source == "HoVer"
                and label == "CONTRADICTED"
                and ":derived-" not in str(row.get("source_record_id", ""))
            ):
                untrusted_hover += 1
    exact_answer_share: dict[str, float] = {}
    for label, answers in label_answers.items():
        total = sum(answers.values())
        exact_answer_share[label] = max(answers.values()) / total
    source_label_rows: dict[str, int] = {}
    source_label_max_share: dict[str, float] = {}
    source_label_counts: dict[str, dict[str, int]] = {}
    for (task, source), labels in sorted(source_labels.items()):
        key = f"{task}/{source}"
        total = sum(labels.values())
        source_label_rows[key] = total
        source_label_max_share[key] = max(labels.values()) / total
        source_label_counts[key] = dict(sorted(labels.items()))
    overflow = 0
    if tokenizer is not None:
        overflow = _protected_overflow_count(rows, tokenizer=tokenizer, max_length=max_length)
    decisive_evidence_not_visible = _decisive_qa_evidence_not_visible_count(rows)
    return {
        "rows": len(rows),
        "tokenizer_checked": tokenizer is not None,
        "max_length": max_length,
        "protected_input_overflow_rows": overflow,
        "decisive_qa_evidence_not_visible_rows": decisive_evidence_not_visible,
        "untrusted_hover_contradicted_rows": untrusted_hover,
        "max_exact_answer_share_by_label": dict(sorted(exact_answer_share.items())),
        "source_label_rows": source_label_rows,
        "source_label_max_share": source_label_max_share,
        "source_label_counts": source_label_counts,
    }


def validate_dataset_correctness(
    summary: Mapping[str, object],
    policy: CorrectnessPolicy = RELEASE_CORRECTNESS_POLICY,
) -> dict[str, object]:
    untrusted_hover = summary.get("untrusted_hover_contradicted_rows")
    if not isinstance(untrusted_hover, int) or isinstance(untrusted_hover, bool):
        raise ValueError("HoVer contradiction count is invalid")
    if untrusted_hover:
        raise ValueError("HoVer merged negatives cannot be labeled CONTRADICTED")
    if policy.require_tokenizer and summary.get("tokenizer_checked") is not True:
        raise ValueError("release correctness requires a tokenizer check")
    overflow = summary.get("protected_input_overflow_rows")
    if not isinstance(overflow, int) or isinstance(overflow, bool):
        raise ValueError("protected input overflow count is invalid")
    if overflow:
        raise ValueError("protected input exceeds the model token budget")
    decisive_evidence = summary.get("decisive_qa_evidence_not_visible_rows")
    if not isinstance(decisive_evidence, int) or isinstance(decisive_evidence, bool):
        raise ValueError("decisive evidence visibility count is invalid")
    if decisive_evidence:
        raise ValueError("decisive evidence is not visible in the model input")
    shares = summary.get("max_exact_answer_share_by_label")
    if not isinstance(shares, Mapping):
        raise ValueError("answer template shares are missing")
    for share in shares.values():
        if not isinstance(share, (int, float)) or isinstance(share, bool):
            raise ValueError("answer template share is invalid")
        if float(share) > policy.max_exact_answer_share:
            raise ValueError("answer template share exceeds release policy")
    row_counts = summary.get("source_label_rows")
    source_shares = summary.get("source_label_max_share")
    if not isinstance(row_counts, Mapping) or not isinstance(source_shares, Mapping):
        raise ValueError("source label summaries are missing")
    for key, count in row_counts.items():
        share = source_shares.get(key)
        if not isinstance(count, int) or isinstance(count, bool):
            raise ValueError("source label row count is invalid")
        if not isinstance(share, (int, float)) or isinstance(share, bool):
            raise ValueError("source label share is invalid")
        if count >= policy.min_source_rows and float(share) > policy.max_source_label_share:
            raise ValueError("source label share exceeds release policy")
    return dict(summary)
