import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("build_dataset.py")
REGRESSION_SEED = Path(__file__).with_name("data") / "regression_seed.jsonl"


def load_builder():
    spec = importlib.util.spec_from_file_location("build_dataset", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class BuildDatasetTest(unittest.TestCase):
    def test_builds_balanced_group_isolated_corpora(self):
        builder = load_builder()
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            summary = builder.build_dataset(output, examples_per_task=300)

            self.assertEqual(300, summary["answerability"])
            self.assertEqual(300, summary["groundedness"])
            rows = []
            for path in sorted(output.glob("*.jsonl")):
                rows.extend(json.loads(line) for line in path.read_text(encoding="utf-8").splitlines())

            self.assertEqual(600, len(rows))
            for task in ("answerability", "groundedness"):
                task_rows = [row for row in rows if row["task"] == task]
                labels = {row["label"] for row in task_rows}
                expected = (
                    {"SUPPORTED", "PARTIAL", "UNSUPPORTED"}
                    if task == "answerability"
                    else {"GROUNDED", "PARTIAL", "UNGROUNDED"}
                )
                self.assertEqual(expected, labels)

            split_by_document = {}
            for row in rows:
                split_by_document.setdefault(row["document_id"], set()).add(row["split"])
                self.assertNotRegex(row["question"] + row["evidence"] + row["answer"], r"1[3-9]\d{9}")
            self.assertTrue(all(len(splits) == 1 for splits in split_by_document.values()))

    def test_regression_seed_covers_bypass_and_false_citation_cases(self):
        rows = [
            json.loads(line)
            for line in REGRESSION_SEED.read_text(encoding="utf-8").splitlines()
        ]

        self.assertGreaterEqual(len(rows), 12)
        self.assertTrue(any(row["hard_negative_type"] == "BYPASS_INSTRUCTION" for row in rows))
        self.assertTrue(any(row["hard_negative_type"] == "FALSE_CITATION" for row in rows))
        self.assertEqual(len(rows), len({row["id"] for row in rows}))
        self.assertTrue(all(row["split"] == "test" for row in rows))


if __name__ == "__main__":
    unittest.main()
