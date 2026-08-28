import unittest

from tools.rag_guard.test_dataset_schema_v2 import groundedness_row


def row_for_label(label: str, *, index: int, source: str = "source-a") -> dict[str, object]:
    support = {
        "GROUNDED": "entailed",
        "PARTIAL": "missing",
        "UNSUPPORTED": "missing",
        "CONTRADICTED": "contradicted",
    }[label]
    answer = f"候选回答 {label} {index}"
    return groundedness_row(
        id=f"row-{source}-{label}-{index}",
        label=label,
        answer=answer,
        atomic_claims=[
            {
                "text": answer,
                "support": support,
                "source_ids": ["S1"],
                "material": True,
            }
        ],
        source_dataset=source,
        source_record_id=f"record-{label}-{index}",
        mutation_family_id=f"family-{source}-{label}-{index}",
        document_id=f"doc-{source}-{label}-{index}",
        hard_negative_type="WRONG_ENTITY" if label == "CONTRADICTED" else "NONE",
    )


class DatasetCorrectnessV4Test(unittest.TestCase):
    def test_release_summary_accepts_visible_diverse_rows(self) -> None:
        from tools.rag_guard.dataset_correctness_v4 import (
            CorrectnessPolicy,
            summarize_dataset_correctness,
            validate_dataset_correctness,
        )

        class VisibleTokenizer:
            def __call__(self, first: object, second: object = None, **_kwargs: object):
                size = len(first) if isinstance(first, list) else 1
                return {"input_ids": [[1, 2, 3, 4] for _ in range(size)]}

        rows = [row_for_label(label, index=index) for label in (
            "GROUNDED", "PARTIAL", "UNSUPPORTED", "CONTRADICTED"
        ) for index in range(3)]
        report = summarize_dataset_correctness(rows, tokenizer=VisibleTokenizer(), max_length=256)
        validated = validate_dataset_correctness(
            report,
            CorrectnessPolicy(
                max_exact_answer_share=0.40,
                max_source_label_share=0.40,
                min_source_rows=4,
                require_tokenizer=True,
            ),
        )

        self.assertEqual(0, validated["protected_input_overflow_rows"])
        self.assertTrue(validated["tokenizer_checked"])

    def test_release_gate_rejects_untrusted_hover_merged_negative(self) -> None:
        from tools.rag_guard.dataset_correctness_v4 import summarize_dataset_correctness, validate_dataset_correctness

        row = row_for_label("CONTRADICTED", index=1, source="HoVer")
        row["source_record_id"] = "hover-negative:contradicted:g"
        report = summarize_dataset_correctness([row])
        with self.assertRaisesRegex(ValueError, "HoVer"):
            validate_dataset_correctness(report)

    def test_release_gate_rejects_dominant_exact_answer_template(self) -> None:
        from tools.rag_guard.dataset_correctness_v4 import (
            CorrectnessPolicy,
            summarize_dataset_correctness,
            validate_dataset_correctness,
        )

        rows = [row_for_label("UNSUPPORTED", index=index) for index in range(10)]
        for row in rows:
            row["answer"] = "The document specifies a separate conclusion not requested here."
        report = summarize_dataset_correctness(rows)
        with self.assertRaisesRegex(ValueError, "answer template"):
            validate_dataset_correctness(
                report,
                CorrectnessPolicy(
                    max_exact_answer_share=0.20,
                    max_source_label_share=1.0,
                    min_source_rows=100,
                    require_tokenizer=False,
                ),
            )

    def test_release_gate_rejects_source_that_determines_label(self) -> None:
        from tools.rag_guard.dataset_correctness_v4 import (
            CorrectnessPolicy,
            summarize_dataset_correctness,
            validate_dataset_correctness,
        )

        rows = [row_for_label("GROUNDED", index=index, source="single-label") for index in range(10)]
        report = summarize_dataset_correctness(rows)
        with self.assertRaisesRegex(ValueError, "source label share"):
            validate_dataset_correctness(
                report,
                CorrectnessPolicy(
                    max_exact_answer_share=1.0,
                    max_source_label_share=0.80,
                    min_source_rows=5,
                    require_tokenizer=False,
                ),
            )

    def test_release_gate_rejects_protected_input_overflow(self) -> None:
        from tools.rag_guard.dataset_correctness_v4 import summarize_dataset_correctness, validate_dataset_correctness

        class OverflowTokenizer:
            def __call__(self, first: object, second: object = None, **_kwargs: object):
                size = len(first) if isinstance(first, list) else 1
                return {"input_ids": [[1] * 300 for _ in range(size)]}

        report = summarize_dataset_correctness(
            [row_for_label("GROUNDED", index=1)],
            tokenizer=OverflowTokenizer(),
            max_length=256,
        )
        with self.assertRaisesRegex(ValueError, "protected input"):
            validate_dataset_correctness(report)

    def test_release_gate_rejects_invisible_decisive_qa_evidence(self) -> None:
        from tools.rag_guard.dataset_correctness_v4 import (
            CorrectnessPolicy,
            summarize_dataset_correctness,
            validate_dataset_correctness,
        )

        row = row_for_label("GROUNDED", index=1, source="SQuAD 2.0")
        row["answer"] = "The answer is Visible fact."
        row["atomic_claims"] = [{
            "text": "The answer is Visible fact.",
            "support": "entailed",
            "source_ids": ["S1"],
            "material": True,
        }]
        row["evidence"] = [{
            "source_id": "S1",
            "document_id": "doc-1",
            "text": "This evidence contains a different fact.",
        }]
        class VisibleTokenizer:
            def __call__(self, first: object, second: object = None, **_kwargs: object):
                size = len(first) if isinstance(first, list) else 1
                return {"input_ids": [[1, 2, 3] for _ in range(size)]}

        report = summarize_dataset_correctness([row], tokenizer=VisibleTokenizer(), max_length=256)
        with self.assertRaisesRegex(ValueError, "decisive evidence"):
            validate_dataset_correctness(
                report,
                CorrectnessPolicy(
                    max_exact_answer_share=1.0,
                    max_source_label_share=1.0,
                    min_source_rows=100,
                    require_tokenizer=True,
                ),
            )

    def test_token_budget_filter_removes_overflow_before_quota_selection(self) -> None:
        from tools.rag_guard.dataset_correctness_v4 import filter_protected_input_budget

        class SelectiveTokenizer:
            def __call__(self, first: object, second: object = None, **_kwargs: object):
                values = first if isinstance(first, list) else [first]
                return {
                    "input_ids": [
                        [1] * (300 if "OVERFLOW" in str(value) else 20)
                        for value in values
                    ]
                }

        visible = row_for_label("GROUNDED", index=1)
        overflow = row_for_label("GROUNDED", index=2)
        overflow["answer"] = "OVERFLOW"
        overflow["atomic_claims"] = [
            {
                "text": "OVERFLOW",
                "support": "entailed",
                "source_ids": ["S1"],
                "material": True,
            }
        ]

        accepted, rejected = filter_protected_input_budget(
            [visible, overflow], tokenizer=SelectiveTokenizer(), max_length=256
        )

        self.assertEqual([visible["id"]], [row["id"] for row in accepted])
        self.assertEqual([overflow["id"]], rejected)

    def test_orphaned_contradiction_filter_removes_the_entire_family(self) -> None:
        from tools.rag_guard.dataset_correctness_v4 import filter_orphaned_contradiction_families

        grounded = row_for_label("GROUNDED", index=1)
        grounded["mutation_family_id"] = "complete-family"
        contradicted = row_for_label("CONTRADICTED", index=2)
        contradicted["mutation_family_id"] = "complete-family"
        orphan = row_for_label("CONTRADICTED", index=3)
        orphan["mutation_family_id"] = "orphan-family"
        unrelated = row_for_label("UNSUPPORTED", index=4)
        unrelated["mutation_family_id"] = "unsupported-only-family"

        accepted, rejected_families = filter_orphaned_contradiction_families(
            [grounded, contradicted, orphan, unrelated]
        )

        self.assertEqual(
            {grounded["id"], contradicted["id"], unrelated["id"]},
            {row["id"] for row in accepted},
        )
        self.assertEqual(["orphan-family"], rejected_families)


if __name__ == "__main__":
    unittest.main()
