import json
import tempfile
import unittest
from pathlib import Path

from tools.rag_guard.quality_gate import (
    QualityGateRequirements,
    assert_document_isolation,
    evaluate_quality_gate,
    load_scored_jsonl,
    select_answerability_threshold,
    select_groundedness_threshold,
    validate_redacted_text,
)

MODEL_SHA = "45d42125648c169a19697ce8b64f6883e63c2d8a45fd666c73bf163a3c59e097"
TOKENIZER_SHA = "3396f311d68a8ee4351c0949ab2626543334c5566d7f8ea17b026952ac14d0fe"


def scored_row(
    *,
    row_id: str,
    task: str,
    label: str,
    probabilities: list[float],
    document_id: str,
) -> dict[str, object]:
    return {
        "id": row_id,
        "task": task,
        "label": label,
        "probabilities": probabilities,
        "document_id": document_id,
        "distribution": "real_office_redacted",
        "redaction_status": "reviewed",
        "model_sha256": MODEL_SHA,
        "tokenizer_sha256": TOKENIZER_SHA,
        "question": "脱敏后的办公问题",
        "evidence": "脱敏后的制度证据",
        "answer": "脱敏后的回答" if task == "groundedness" else "",
    }


class QualityGateTest(unittest.TestCase):
    def test_public_distribution_requires_explicit_prequalification_mode(self) -> None:
        calibration = [
            scored_row(
                row_id="public-cal-a",
                task="answerability",
                label="SUPPORTED",
                probabilities=[0.98, 0.01, 0.01],
                document_id="public-cal-doc",
            )
        ]
        calibration.append(
            scored_row(
                row_id="public-cal-g",
                task="groundedness",
                label="GROUNDED",
                probabilities=[0.98, 0.01, 0.01],
                document_id="public-cal-g-doc",
            )
        )
        test = [
            scored_row(
                row_id="public-test-a",
                task="answerability",
                label="SUPPORTED",
                probabilities=[0.98, 0.01, 0.01],
                document_id="public-test-a-doc",
            ),
            scored_row(
                row_id="public-test-g",
                task="groundedness",
                label="GROUNDED",
                probabilities=[0.98, 0.01, 0.01],
                document_id="public-test-g-doc",
            ),
        ]
        for row in calibration + test:
            row["distribution"] = "public_office_licensed"
            row["redaction_status"] = "public_source_reviewed"

        report = evaluate_quality_gate(
            calibration,
            test,
            training_document_ids=set(),
            classifier_sha256=MODEL_SHA,
            tokenizer_sha256=TOKENIZER_SHA,
            requirements=QualityGateRequirements(minimum_examples_per_task=1),
            expected_distribution="public_office_licensed",
        )

        self.assertEqual(report.distribution, "public_office_licensed")
        self.assertEqual(report.qualification_scope, "public_prequalification_only")
        with self.assertRaisesRegex(ValueError, "unapproved evaluation distribution"):
            evaluate_quality_gate(
                calibration,
                test,
                training_document_ids=set(),
                classifier_sha256=MODEL_SHA,
                tokenizer_sha256=TOKENIZER_SHA,
                requirements=QualityGateRequirements(minimum_examples_per_task=1),
            )

    def test_loads_scored_jsonl_without_logging_the_content(self) -> None:
        row = scored_row(
            row_id="load-1",
            task="answerability",
            label="SUPPORTED",
            probabilities=[0.98, 0.01, 0.01],
            document_id="load-doc-1",
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "office.jsonl"
            path.write_text(json.dumps(row, ensure_ascii=False) + "\n", encoding="utf-8")

            self.assertEqual(load_scored_jsonl(path), [row])

    def test_selects_highest_recall_threshold_that_meets_precision(self) -> None:
        rows = [
            scored_row(
                row_id="a1",
                task="answerability",
                label="SUPPORTED",
                probabilities=[0.92, 0.05, 0.03],
                document_id="cal-1",
            ),
            scored_row(
                row_id="a2",
                task="answerability",
                label="SUPPORTED",
                probabilities=[0.78, 0.12, 0.10],
                document_id="cal-2",
            ),
            scored_row(
                row_id="a3",
                task="answerability",
                label="UNSUPPORTED",
                probabilities=[0.74, 0.06, 0.20],
                document_id="cal-3",
            ),
        ]

        selection = select_answerability_threshold(rows, minimum_precision=1.0)

        self.assertAlmostEqual(selection.threshold, 0.78)
        self.assertAlmostEqual(selection.precision, 1.0)
        self.assertAlmostEqual(selection.recall, 1.0)

    def test_selects_groundedness_threshold_only_from_calibration_rows(self) -> None:
        rows = [
            scored_row(
                row_id="g1",
                task="groundedness",
                label="GROUNDED",
                probabilities=[0.93, 0.04, 0.03],
                document_id="g-cal-1",
            ),
            scored_row(
                row_id="g2",
                task="groundedness",
                label="GROUNDED",
                probabilities=[0.81, 0.10, 0.09],
                document_id="g-cal-2",
            ),
            scored_row(
                row_id="g3",
                task="groundedness",
                label="PARTIAL",
                probabilities=[0.79, 0.20, 0.01],
                document_id="g-cal-3",
            ),
        ]

        selection = select_groundedness_threshold(rows, minimum_precision=1.0)

        self.assertAlmostEqual(selection.threshold, 0.81)
        self.assertAlmostEqual(selection.precision, 1.0)
        self.assertAlmostEqual(selection.recall, 1.0)

    def test_rejects_document_leakage_between_all_splits(self) -> None:
        with self.assertRaisesRegex(ValueError, "document leakage"):
            assert_document_isolation(
                {
                    "training": {"doc-train", "doc-shared"},
                    "office_calibration": {"doc-cal"},
                    "office_test": {"doc-shared"},
                }
            )

    def test_rejects_unredacted_phone_and_identity_number(self) -> None:
        for value in ("请联系 13812345678", "身份证号 11010519491231002X"):
            with self.subTest(value=value):
                with self.assertRaisesRegex(ValueError, "sensitive identifier"):
                    validate_redacted_text(value)

    def test_quality_gate_requires_both_tasks_and_never_self_calibrates_on_test(self) -> None:
        calibration = [
            scored_row(
                row_id=f"cal-a-{index}",
                task="answerability",
                label=label,
                probabilities=probabilities,
                document_id=f"cal-a-doc-{index}",
            )
            for index, (label, probabilities) in enumerate(
                [
                    ("SUPPORTED", [0.95, 0.03, 0.02]),
                    ("SUPPORTED", [0.90, 0.06, 0.04]),
                    ("UNSUPPORTED", [0.10, 0.10, 0.80]),
                ]
            )
        ]
        calibration.extend(
            scored_row(
                row_id=f"cal-g-{index}",
                task="groundedness",
                label=label,
                probabilities=probabilities,
                document_id=f"cal-g-doc-{index}",
            )
            for index, (label, probabilities) in enumerate(
                [
                    ("GROUNDED", [0.96, 0.02, 0.02]),
                    ("GROUNDED", [0.91, 0.05, 0.04]),
                    ("PARTIAL", [0.04, 0.92, 0.04]),
                ]
            )
        )
        test = [
            scored_row(
                row_id=f"test-a-{index}",
                task="answerability",
                label=label,
                probabilities=probabilities,
                document_id=f"test-a-doc-{index}",
            )
            for index, (label, probabilities) in enumerate(
                [
                    ("SUPPORTED", [0.96, 0.02, 0.02]),
                    ("SUPPORTED", [0.91, 0.05, 0.04]),
                    ("UNSUPPORTED", [0.04, 0.06, 0.90]),
                ]
            )
        ]
        test.extend(
            scored_row(
                row_id=f"test-g-{index}",
                task="groundedness",
                label=label,
                probabilities=probabilities,
                document_id=f"test-g-doc-{index}",
            )
            for index, (label, probabilities) in enumerate(
                [
                    ("GROUNDED", [0.98, 0.01, 0.01]),
                    ("PARTIAL", [0.01, 0.98, 0.01]),
                    ("UNGROUNDED", [0.01, 0.01, 0.98]),
                ]
            )
        )

        report = evaluate_quality_gate(
            calibration,
            test,
            training_document_ids={"train-only"},
            classifier_sha256=MODEL_SHA,
            tokenizer_sha256=TOKENIZER_SHA,
            requirements=QualityGateRequirements(minimum_examples_per_task=2),
        )

        self.assertTrue(report.passed)
        self.assertAlmostEqual(report.answerability_threshold, 0.90)
        self.assertAlmostEqual(report.groundedness_threshold, 0.91)

    def test_quality_gate_rejects_scores_from_a_different_model(self) -> None:
        row = scored_row(
            row_id="mismatch",
            task="answerability",
            label="SUPPORTED",
            probabilities=[0.98, 0.01, 0.01],
            document_id="cal-model-mismatch",
        )
        row["model_sha256"] = "0" * 64

        with self.assertRaisesRegex(ValueError, "model SHA-256 mismatch"):
            evaluate_quality_gate(
                [row],
                [
                    scored_row(
                        row_id="test-a",
                        task="answerability",
                        label="SUPPORTED",
                        probabilities=[0.98, 0.01, 0.01],
                        document_id="test-a-model-mismatch",
                    ),
                    scored_row(
                        row_id="test-g",
                        task="groundedness",
                        label="GROUNDED",
                        probabilities=[0.98, 0.01, 0.01],
                        document_id="test-g-model-mismatch",
                    ),
                ],
                training_document_ids=set(),
                classifier_sha256=MODEL_SHA,
                tokenizer_sha256=TOKENIZER_SHA,
                requirements=QualityGateRequirements(minimum_examples_per_task=1),
            )

    def test_rejects_non_string_task_as_invalid_input(self) -> None:
        row = scored_row(
            row_id="bad-task",
            task="answerability",
            label="SUPPORTED",
            probabilities=[0.98, 0.01, 0.01],
            document_id="bad-task-doc",
        )
        row["task"] = ["answerability"]

        with self.assertRaisesRegex(ValueError, "invalid task or label"):
            select_answerability_threshold([row], minimum_precision=0.95)


if __name__ == "__main__":
    unittest.main()
