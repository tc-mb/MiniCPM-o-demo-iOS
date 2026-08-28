import unittest


class CheckpointAuditV4Test(unittest.TestCase):
    def test_summarizes_task_metrics_by_language_source_and_hard_type(self) -> None:
        from tools.rag_guard.checkpoint_audit_v4 import summarize_classification_slices

        rows = [
            {
                "task": "groundedness",
                "label": "CONTRADICTED",
                "language": "zh",
                "source_dataset": "CMRC 2018",
                "hard_negative_type": "WRONG_ENTITY",
            },
            {
                "task": "groundedness",
                "label": "GROUNDED",
                "language": "zh",
                "source_dataset": "CMRC 2018",
                "hard_negative_type": "NONE",
            },
            {
                "task": "answerability",
                "label": "SUPPORTED",
                "language": "en",
                "source_dataset": "SQuAD 2.0",
                "hard_negative_type": "NONE",
            },
        ]

        report = summarize_classification_slices(rows, predictions=[3, 0, 2])

        self.assertEqual(2, report["overall"]["groundedness"]["count"])
        self.assertEqual(0.0, report["overall"]["answerability"]["accuracy"])
        self.assertEqual(
            1.0,
            report["by_language"]["zh"]["groundedness"]["per_class"]["CONTRADICTED"]["recall"],
        )
        self.assertEqual(
            1,
            report["by_hard_negative_type"]["WRONG_ENTITY"]["groundedness"]["count"],
        )
        self.assertIn("CMRC 2018", report["by_source_dataset"])

    def test_rejects_misaligned_or_unknown_predictions(self) -> None:
        from tools.rag_guard.checkpoint_audit_v4 import summarize_classification_slices

        row = {
            "task": "answerability",
            "label": "SUPPORTED",
            "language": "en",
            "source_dataset": "SQuAD 2.0",
        }

        with self.assertRaisesRegex(ValueError, "aligned"):
            summarize_classification_slices([row], predictions=[])
        with self.assertRaisesRegex(ValueError, "prediction"):
            summarize_classification_slices([row], predictions=[3])

    def test_builds_text_free_misclassification_records(self) -> None:
        from tools.rag_guard.checkpoint_audit_v4 import build_misclassification_records

        rows = [
            {
                "id": "row-1",
                "task": "groundedness",
                "label": "CONTRADICTED",
                "language": "en",
                "source_dataset": "SQuAD 2.0",
                "hard_negative_type": "WRONG_ENTITY",
                "mutation_family_id": "family-1",
                "document_id": "document-1",
                "question": "Who signed it?",
                "evidence": "Alice signed the agreement.",
                "answer": "Bob signed the agreement.",
            },
            {
                "id": "row-2",
                "task": "answerability",
                "label": "SUPPORTED",
                "language": "zh",
                "source_dataset": "CMRC 2018",
                "hard_negative_type": "NONE",
                "mutation_family_id": "family-2",
                "document_id": "document-2",
                "question": "谁签署了协议？",
                "evidence": "张三签署了协议。",
                "answer": "张三",
            },
        ]

        records = build_misclassification_records(rows, predictions=[0, 0])

        self.assertEqual(1, len(records))
        self.assertEqual("GROUNDED", records[0]["predicted_label"])
        self.assertEqual(14, records[0]["question_chars"])
        self.assertNotIn("question", records[0])
        self.assertNotIn("evidence", records[0])
        self.assertNotIn("answer", records[0])


if __name__ == "__main__":
    unittest.main()
