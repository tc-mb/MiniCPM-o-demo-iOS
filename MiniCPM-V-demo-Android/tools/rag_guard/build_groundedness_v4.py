"""Build four-class Groundedness families with atomic evidence relations."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass

from tools.rag_guard.claim_labeling import aggregate_claim_support
from tools.rag_guard.dataset_schema_v2 import validate_v2_row


@dataclass(frozen=True)
class GroundednessSourceRecord:
    source_dataset: str
    source_version: str
    source_license: str
    source_record_id: str
    document_id: str
    language: str
    domain: str
    question: str
    evidence: str
    grounded_answer: str


def _digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def contract_nli_groundedness_label(choice: str) -> str:
    mapping = {
        "Entailment": "GROUNDED",
        "NotMentioned": "UNSUPPORTED",
        "Contradiction": "CONTRADICTED",
    }
    try:
        return mapping[choice]
    except KeyError as error:
        raise ValueError("unsupported ContractNLI choice") from error


def _claim(text: str, support: str) -> dict[str, object]:
    return {
        "text": text.strip(),
        "support": support,
        "source_ids": ["S1"],
        "material": True,
    }


def _row(
    source: GroundednessSourceRecord,
    *,
    answer: str,
    claims: list[dict[str, object]],
    suffix: str,
    hard_negative_type: str,
    raw_sha256: str,
    split: str = "train",
    generator_commit: str = "0" * 40,
) -> dict[str, object]:
    support_labels = [str(claim["support"]) for claim in claims if claim.get("material") is True]
    label = aggregate_claim_support(support_labels)
    family_id = "groundedness-" + _digest(
        f"{source.source_dataset}\0{source.source_record_id}\0{source.document_id}"
    )[:24]
    row: dict[str, object] = {
        "id": f"{family_id}-{suffix}",
        "task": "groundedness",
        "label": label,
        "question": source.question.strip(),
        "evidence": [
            {
                "source_id": "S1",
                "document_id": source.document_id,
                "text": source.evidence.strip(),
            }
        ],
        "answer": answer.strip(),
        "atomic_claims": claims,
        "language": source.language,
        "domain": source.domain,
        "hard_negative_type": hard_negative_type,
        "mutation_family_id": family_id,
        "document_id": source.document_id,
        "conversation_id": "",
        "split": split,
        "distribution": "public_licensed",
        "redaction_status": "public_source_reviewed",
        "source_dataset": source.source_dataset,
        "source_version": source.source_version,
        "source_record_id": source.source_record_id,
        "source_license": source.source_license,
        "license_status": "approved",
        "provenance": {
            "raw_sha256": raw_sha256,
            "transform_version": "rag-guard-v4",
            "generator_commit": generator_commit,
        },
    }
    validate_v2_row(row)
    return row


def build_groundedness_family(
    source: GroundednessSourceRecord,
    *,
    missing_claim: str,
    unsupported_answer: str,
    contradicted_answer: str,
    contradiction_type: str,
    raw_sha256: str = "0" * 64,
) -> list[dict[str, object]]:
    values = (
        source.question,
        source.evidence,
        source.grounded_answer,
        missing_claim,
        unsupported_answer,
        contradicted_answer,
        contradiction_type,
    )
    if any(not value.strip() for value in values):
        raise ValueError("groundedness family fields must be non-empty")
    return [
        _row(
            source,
            answer=source.grounded_answer,
            claims=[_claim(source.grounded_answer, "entailed")],
            suffix="grounded",
            hard_negative_type="NONE",
            raw_sha256=raw_sha256,
        ),
        _row(
            source,
            answer=f"{source.grounded_answer} {missing_claim}",
            claims=[
                _claim(source.grounded_answer, "entailed"),
                _claim(missing_claim, "missing"),
            ],
            suffix="partial",
            hard_negative_type="MISSING_FIELD",
            raw_sha256=raw_sha256,
        ),
        _row(
            source,
            answer=unsupported_answer,
            claims=[_claim(unsupported_answer, "missing")],
            suffix="unsupported",
            hard_negative_type="NO_SUPPORTED_CLAIM",
            raw_sha256=raw_sha256,
        ),
        _row(
            source,
            answer=contradicted_answer,
            claims=[_claim(contradicted_answer, "contradicted")],
            suffix="contradicted",
            hard_negative_type=contradiction_type,
            raw_sha256=raw_sha256,
        ),
    ]
