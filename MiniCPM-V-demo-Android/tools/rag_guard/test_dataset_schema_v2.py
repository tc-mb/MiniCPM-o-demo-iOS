import unittest

from tools.rag_guard.dataset_schema_v2 import validate_v2_row


def groundedness_row(**overrides: object) -> dict[str, object]:
    row: dict[str, object] = {
        "id": "v4-row-1",
        "task": "groundedness",
        "label": "CONTRADICTED",
        "question": "差旅上限是多少？",
        "evidence": [
            {"source_id": "S1", "document_id": "doc-1", "text": "上限为800元。"}
        ],
        "answer": "上限为1500元。",
        "atomic_claims": [
            {
                "text": "上限为1500元。",
                "support": "contradicted",
                "source_ids": ["S1"],
                "material": True,
            }
        ],
        "language": "zh",
        "domain": "travel",
        "hard_negative_type": "WRONG_AMOUNT",
        "mutation_family_id": "family-1",
        "document_id": "doc-1",
        "conversation_id": "",
        "split": "train",
        "distribution": "public_licensed",
        "redaction_status": "public_source_reviewed",
        "source_dataset": "fixture",
        "source_version": "1",
        "source_record_id": "source-1",
        "source_license": "CC-BY-4.0",
        "license_status": "approved",
        "provenance": {
            "raw_sha256": "a" * 64,
            "transform_version": "rag-guard-v4",
            "generator_commit": "b" * 40,
        },
    }
    row.update(overrides)
    return row


class DatasetSchemaV2Test(unittest.TestCase):
    def test_valid_groundedness_row_is_accepted(self) -> None:
        validate_v2_row(groundedness_row())

    def test_groundedness_rejects_legacy_ungrounded_label(self) -> None:
        with self.assertRaisesRegex(ValueError, "invalid groundedness label"):
            validate_v2_row(groundedness_row(label="UNGROUNDED"))

    def test_groundedness_requires_atomic_claims(self) -> None:
        with self.assertRaisesRegex(ValueError, "atomic_claims"):
            validate_v2_row(groundedness_row(atomic_claims=[]))

    def test_unapproved_license_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "license_status"):
            validate_v2_row(groundedness_row(license_status="review_required"))

    def test_duplicate_source_ids_are_rejected(self) -> None:
        evidence = groundedness_row()["evidence"]
        with self.assertRaisesRegex(ValueError, "duplicate source_id"):
            validate_v2_row(groundedness_row(evidence=[*evidence, evidence[0]]))

    def test_provenance_hashes_are_required(self) -> None:
        with self.assertRaisesRegex(ValueError, "raw_sha256"):
            validate_v2_row(
                groundedness_row(
                    provenance={
                        "raw_sha256": "not-a-hash",
                        "transform_version": "rag-guard-v4",
                        "generator_commit": "b" * 40,
                    }
                )
            )


if __name__ == "__main__":
    unittest.main()
