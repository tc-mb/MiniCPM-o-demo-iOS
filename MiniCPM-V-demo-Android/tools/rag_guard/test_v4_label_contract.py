import unittest
import json
import tempfile
from pathlib import Path

from tools.rag_guard.test_dataset_schema_v2 import groundedness_row
from tools.rag_guard.training_data import (
    LABELS_BY_TASK,
    LABELS_BY_TASK_V3,
    LABELS_BY_TASK_V4,
    format_model_input_v4,
    load_jsonl_v4,
)


class V4LabelContractTest(unittest.TestCase):
    def test_v4_labels_are_three_plus_four(self) -> None:
        self.assertEqual(
            LABELS_BY_TASK_V4["answerability"],
            ("SUPPORTED", "PARTIAL", "UNSUPPORTED"),
        )
        self.assertEqual(
            LABELS_BY_TASK_V4["groundedness"],
            ("GROUNDED", "PARTIAL", "UNSUPPORTED", "CONTRADICTED"),
        )

    def test_v4_formatter_uses_numbered_evidence_and_answer(self) -> None:
        text = format_model_input_v4(groundedness_row())
        self.assertIn("evidence [S1]:", text)
        self.assertIn("answer:", text)

    def test_v4_loader_validates_schema_and_split(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "groundedness_train.jsonl"
            path.write_text(json.dumps(groundedness_row(), ensure_ascii=False) + "\n", encoding="utf-8")
            rows = load_jsonl_v4(path, expected_task="groundedness", expected_split="train")
            self.assertEqual(1, len(rows))

    def test_legacy_v3_contract_remains_available_to_current_model(self) -> None:
        self.assertIs(LABELS_BY_TASK, LABELS_BY_TASK_V3)
        self.assertEqual(
            LABELS_BY_TASK_V3["groundedness"],
            ("GROUNDED", "PARTIAL", "UNGROUNDED"),
        )
        self.assertEqual(
            LABELS_BY_TASK_V4["groundedness"],
            ("GROUNDED", "PARTIAL", "UNSUPPORTED", "CONTRADICTED"),
        )


if __name__ == "__main__":
    unittest.main()
