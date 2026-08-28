import unittest

from tools.rag_guard import evaluate_slices
from tools.rag_guard.evaluate_slices import checkpoint_rank, eligible_checkpoint, per_class_metrics


def metrics_fixture(
    *,
    answerability_f1: float = 0.96,
    groundedness_f1: float = 0.90,
    contradicted_precision: float = 0.99,
    hard_recalls: tuple[float, ...] = (0.92, 0.93),
    ece: float = 0.04,
) -> dict[str, object]:
    return {
        "answerability": {"macro_f1": answerability_f1},
        "groundedness": {
            "macro_f1": groundedness_f1,
            "ece": ece,
            "per_class": {"CONTRADICTED": {"precision": contradicted_precision}},
        },
        "hard_slices": {f"slice-{index}": {"recall": value} for index, value in enumerate(hard_recalls)},
    }


class EvaluateSlicesTest(unittest.TestCase):
    def test_checkpoint_rejects_weak_groundedness(self) -> None:
        self.assertFalse(eligible_checkpoint(metrics_fixture(groundedness_f1=0.81)))

    def test_checkpoint_rejects_weak_contradicted_precision(self) -> None:
        self.assertFalse(eligible_checkpoint(metrics_fixture(contradicted_precision=0.97)))

    def test_eligible_checkpoints_rank_by_worst_slice_then_f1_then_ece(self) -> None:
        stronger_slice = metrics_fixture(hard_recalls=(0.94, 0.95), ece=0.05)
        weaker_slice = metrics_fixture(hard_recalls=(0.90, 0.99), ece=0.01)
        self.assertGreater(checkpoint_rank(stronger_slice), checkpoint_rank(weaker_slice))

    def test_ineligible_checkpoint_still_has_a_diagnostic_selection_rank(self) -> None:
        self.assertTrue(hasattr(evaluate_slices, "checkpoint_selection_rank"))
        checkpoint_selection_rank = evaluate_slices.checkpoint_selection_rank
        first_epoch = metrics_fixture(
            answerability_f1=0.8660,
            groundedness_f1=0.7998,
            contradicted_precision=0.9428,
            hard_recalls=(0.7236, 0.2278),
        )
        second_epoch = metrics_fixture(
            answerability_f1=0.8751,
            groundedness_f1=0.8028,
            contradicted_precision=0.9196,
            hard_recalls=(0.7276, 0.3354),
        )

        self.assertFalse(eligible_checkpoint(first_epoch))
        self.assertFalse(eligible_checkpoint(second_epoch))
        self.assertGreater(
            checkpoint_selection_rank(second_epoch),
            checkpoint_selection_rank(first_epoch),
        )

    def test_release_eligible_checkpoint_always_outranks_diagnostic_checkpoint(self) -> None:
        self.assertTrue(hasattr(evaluate_slices, "checkpoint_selection_rank"))
        checkpoint_selection_rank = evaluate_slices.checkpoint_selection_rank
        eligible = metrics_fixture()
        diagnostic = metrics_fixture(
            answerability_f1=0.94,
            groundedness_f1=0.99,
            contradicted_precision=0.99,
            hard_recalls=(0.99, 0.99),
            ece=0.0,
        )

        self.assertGreater(
            checkpoint_selection_rank(eligible),
            checkpoint_selection_rank(diagnostic),
        )

    def test_missing_required_metrics_are_not_eligible(self) -> None:
        self.assertFalse(eligible_checkpoint({}))

    def test_per_class_metrics_report_precision_and_recall(self) -> None:
        metrics = per_class_metrics(
            [0, 3, 3, 3],
            [0, 3, 2, 3],
            ("GROUNDED", "PARTIAL", "UNSUPPORTED", "CONTRADICTED"),
        )
        self.assertAlmostEqual(1.0, metrics["CONTRADICTED"]["precision"])
        self.assertAlmostEqual(2 / 3, metrics["CONTRADICTED"]["recall"])


if __name__ == "__main__":
    unittest.main()
