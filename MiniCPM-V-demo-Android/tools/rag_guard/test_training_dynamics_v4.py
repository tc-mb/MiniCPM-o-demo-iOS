import importlib
import importlib.util
import unittest


class TrainingDynamicsV4Test(unittest.TestCase):
    def setUp(self) -> None:
        spec = importlib.util.find_spec("tools.rag_guard.training_dynamics_v4")
        self.assertIsNotNone(spec, "training dynamics module must exist")
        self.module = importlib.import_module("tools.rag_guard.training_dynamics_v4")
        self.assertTrue(hasattr(self.module, "TrainingDynamicsRecorder"))
        self.assertTrue(hasattr(self.module, "select_review_rows"))

    def test_recorder_summarizes_confidence_variability_and_flips_without_text(self) -> None:
        recorder = self.module.TrainingDynamicsRecorder()
        recorder.record("row-1", task="groundedness", epoch=1, gold_label=3, predicted_label=3, gold_probability=0.90)
        recorder.record("row-1", task="groundedness", epoch=2, gold_label=3, predicted_label=0, gold_probability=0.40)
        recorder.record("row-1", task="groundedness", epoch=3, gold_label=3, predicted_label=3, gold_probability=0.80)

        summary = recorder.summarize()["row-1"]
        self.assertAlmostEqual(0.70, summary["mean_gold_probability"])
        self.assertEqual(2, summary["prediction_flip_count"])
        self.assertEqual(3, summary["observations"])
        self.assertEqual({"row_id", "task", "gold_label", "observations", "mean_gold_probability", "variability", "prediction_flip_count"}, set(summary))

    def test_review_selection_uses_only_training_dynamics_thresholds(self) -> None:
        recorder = self.module.TrainingDynamicsRecorder()
        for epoch, probability, prediction in ((1, 0.9, 3), (2, 0.2, 0), (3, 0.8, 3)):
            recorder.record("unstable", task="groundedness", epoch=epoch, gold_label=3, predicted_label=prediction, gold_probability=probability)
        for epoch in (1, 2, 3):
            recorder.record("stable", task="groundedness", epoch=epoch, gold_label=3, predicted_label=3, gold_probability=0.95)

        review = self.module.select_review_rows(
            recorder.summarize(),
            max_mean_gold_probability=0.75,
            min_variability=0.20,
            min_prediction_flips=2,
        )
        self.assertEqual(["unstable"], [row["row_id"] for row in review])

    def test_duplicate_epoch_observation_is_rejected(self) -> None:
        recorder = self.module.TrainingDynamicsRecorder()
        recorder.record("row-1", task="answerability", epoch=1, gold_label=0, predicted_label=0, gold_probability=0.8)
        with self.assertRaisesRegex(ValueError, "duplicate epoch"):
            recorder.record("row-1", task="answerability", epoch=1, gold_label=0, predicted_label=1, gold_probability=0.3)


if __name__ == "__main__":
    unittest.main()
