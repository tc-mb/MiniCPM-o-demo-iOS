import json
import tempfile
import unittest
from pathlib import Path

from tools.rag_guard.training_data import (
    LABELS_BY_TASK,
    expected_calibration_error,
    format_model_input,
    load_jsonl,
    macro_f1,
)


class TrainingDataTest(unittest.TestCase):
    @staticmethod
    def _v4_groundedness_row() -> dict[str, object]:
        return {
            "id": "v4-groundedness-protected-input",
            "task": "groundedness",
            "label": "GROUNDED",
            "question": "差旅上限是多少？",
            "evidence": [
                {
                    "source_id": "S1",
                    "document_id": "doc-1",
                    "text": "很长的制度正文。" * 200,
                }
            ],
            "answer": "差旅报销上限为 800 元。",
            "atomic_claims": [
                {
                    "text": "差旅报销上限为 800 元。",
                    "support": "entailed",
                    "material": True,
                    "source_ids": ["S1"],
                }
            ],
            "conversation_id": "",
            "document_id": "doc-1",
            "domain": "office",
            "hard_negative_type": "NONE",
            "mutation_family_id": "family-1",
            "split": "train",
            "language": "zh",
            "distribution": "public_licensed",
            "redaction_status": "public_source_redacted",
            "source_dataset": "fixture",
            "source_version": "1",
            "source_record_id": "fixture-1",
            "source_license": "MIT",
            "license_status": "approved",
            "provenance": {
                "raw_sha256": "a" * 64,
                "transform_version": "rag-guard-v4.1",
                "generator_commit": "b" * 40,
            },
        }

    def test_v4_pair_protects_query_and_candidate_answer_from_evidence_truncation(self) -> None:
        from tools.rag_guard.training_data import format_model_pair_v4

        protected, evidence = format_model_pair_v4(self._v4_groundedness_row())

        self.assertEqual(
            "query: 差旅上限是多少？\nanswer: 差旅报销上限为 800 元。",
            protected,
        )
        self.assertTrue(evidence.startswith("evidence [S1]: 很长的制度正文。"))
        self.assertNotIn("answer:", evidence)

    def test_v4_encoder_truncates_only_evidence(self) -> None:
        from tools.rag_guard.training_data import encode_model_pairs_v4

        class RecordingTokenizer:
            def __init__(self) -> None:
                self.calls: list[tuple[object, object, dict[str, object]]] = []

            def __call__(self, first: object, second: object = None, **kwargs: object):
                self.calls.append((first, second, dict(kwargs)))
                batch_size = len(first) if isinstance(first, list) else 1
                if second is not None and isinstance(second, list) and all(item == "" for item in second):
                    return {"input_ids": [[1, 2, 3, 4] for _ in range(batch_size)]}
                return {
                    "input_ids": [[1, 2, 3] for _ in range(batch_size)],
                    "attention_mask": [[1, 1, 1] for _ in range(batch_size)],
                }

        tokenizer = RecordingTokenizer()
        encoded = encode_model_pairs_v4(
            [self._v4_groundedness_row()], tokenizer=tokenizer, max_length=256
        )

        protected, evidence, options = tokenizer.calls[-1]
        self.assertEqual(
            ["query: 差旅上限是多少？\nanswer: 差旅报销上限为 800 元。"],
            protected,
        )
        self.assertEqual(1, len(evidence))
        self.assertEqual("only_second", options["truncation"])
        self.assertEqual(256, options["max_length"])
        self.assertFalse(options["padding"])
        self.assertEqual([[1, 2, 3]], encoded["input_ids"])

    def test_formats_each_task_without_adding_an_empty_answer(self) -> None:
        answerability = {
            "task": "answerability",
            "question": "差旅上限是多少？",
            "evidence": "差旅报销上限为 800 元。",
            "answer": "",
        }
        groundedness = {
            **answerability,
            "task": "groundedness",
            "answer": "上限为 800 元。",
        }

        self.assertEqual(
            format_model_input(answerability),
            "query: 差旅上限是多少？\nevidence: 差旅报销上限为 800 元。",
        )
        self.assertEqual(
            format_model_input(groundedness),
            "query: 差旅上限是多少？\nevidence: 差旅报销上限为 800 元。\nanswer: 上限为 800 元。",
        )

    def test_loader_rejects_a_label_from_the_other_task(self) -> None:
        row = {
            "id": "bad-1",
            "task": "answerability",
            "label": "GROUNDED",
            "question": "问题",
            "evidence": "证据",
            "answer": "",
            "document_id": "doc-1",
            "split": "train",
            "language": "zh",
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad.jsonl"
            path.write_text(json.dumps(row, ensure_ascii=False) + "\n", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "invalid label"):
                load_jsonl(path, expected_task="answerability", expected_split="train")

    def test_metrics_are_macro_averaged_and_calibrated(self) -> None:
        labels = LABELS_BY_TASK["answerability"]
        self.assertAlmostEqual(macro_f1([0, 1, 2], [0, 1, 1], len(labels)), 5 / 9)
        self.assertAlmostEqual(
            expected_calibration_error(
                probabilities=[[0.8, 0.1, 0.1], [0.2, 0.7, 0.1]],
                targets=[0, 1],
                bins=2,
            ),
            0.25,
        )


if __name__ == "__main__":
    unittest.main()
