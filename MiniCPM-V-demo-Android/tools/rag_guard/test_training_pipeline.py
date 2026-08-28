import math
import unittest

try:
    import torch
    from transformers import AutoModel, BertConfig
except ImportError:
    torch = None


@unittest.skipIf(torch is None, "training dependencies are not installed")
class TrainingPipelineTest(unittest.TestCase):
    def test_evaluate_records_text_free_training_dynamics(self) -> None:
        from tools.rag_guard.model import DualHeadRagGuard
        from tools.rag_guard.train import evaluate
        from tools.rag_guard.training_dynamics_v4 import TrainingDynamicsRecorder

        config = BertConfig(
            vocab_size=64,
            hidden_size=16,
            num_hidden_layers=1,
            num_attention_heads=2,
            intermediate_size=32,
        )
        model = DualHeadRagGuard(AutoModel.from_config(config), hidden_size=16, dropout=0.0)
        batches = [
            {
                "input_ids": torch.randint(0, config.vocab_size, (2, 8)),
                "attention_mask": torch.ones((2, 8), dtype=torch.long),
                "task_ids": torch.tensor([0, 1], dtype=torch.long),
                "labels": torch.tensor([0, 3], dtype=torch.long),
                "pair_ids": torch.tensor([-1, -1], dtype=torch.long),
                "pair_roles": torch.tensor([0, 0], dtype=torch.long),
                "slice_ids": torch.tensor([-1, 1], dtype=torch.long),
                "row_indices": torch.tensor([0, 1], dtype=torch.long),
            }
        ]
        recorder = TrainingDynamicsRecorder()
        try:
            evaluate(
                model,
                batches,
                torch.device("cpu"),
                row_ids=("answer-row", "ground-row"),
                dynamics=recorder,
                dynamics_epoch=1,
            )
        except TypeError as error:
            self.fail(f"evaluate must support text-free training dynamics: {error}")

        summaries = recorder.summarize()
        self.assertEqual({"answer-row", "ground-row"}, set(summaries))
        self.assertEqual("answerability", summaries["answer-row"]["task"])
        self.assertEqual("groundedness", summaries["ground-row"]["task"])

    def test_default_loss_preserves_the_frozen_baseline_weights(self) -> None:
        from tools.rag_guard.train import joint_guard_loss

        logits = torch.zeros((3, 4), dtype=torch.float32)
        loss = joint_guard_loss(
            logits,
            torch.tensor([0, 1, 1]),
            torch.tensor([0, 0, 3]),
            torch.tensor([-1, 7, 7]),
            torch.tensor([0, 1, -1]),
        )
        expected = math.log(3.0) + 1.5 * math.log(4.0) + 0.25

        self.assertAlmostEqual(expected, loss.item(), places=5)

    def test_dual_head_emits_padded_four_logits(self) -> None:
        from tools.rag_guard.model import DualHeadRagGuard

        config = BertConfig(
            vocab_size=64,
            hidden_size=16,
            num_hidden_layers=1,
            num_attention_heads=2,
            intermediate_size=32,
        )
        model = DualHeadRagGuard(AutoModel.from_config(config), hidden_size=16, dropout=0.0)
        input_ids = torch.randint(0, config.vocab_size, (2, 8))
        attention_mask = torch.ones_like(input_ids)
        logits = model(input_ids, attention_mask, torch.tensor([0, 1]))

        self.assertEqual((2, 4), tuple(logits.shape))
        self.assertLessEqual(logits[0, 3].item(), -1000.0)

    def test_checkpoint_tie_is_broken_by_lower_calibration_error(self) -> None:
        from tools.rag_guard.train import is_better_checkpoint

        self.assertTrue(
            is_better_checkpoint(score=1.0, ece=0.06, best_score=1.0, best_ece=0.10)
        )
        self.assertFalse(
            is_better_checkpoint(score=0.99, ece=0.01, best_score=1.0, best_ece=0.10)
        )

    def test_one_epoch_updates_the_shared_model_with_finite_loss(self) -> None:
        from tools.rag_guard.model import DualHeadRagGuard
        from tools.rag_guard.train import train_epoch

        config = BertConfig(
            vocab_size=64,
            hidden_size=16,
            num_hidden_layers=1,
            num_attention_heads=2,
            intermediate_size=32,
        )
        model = DualHeadRagGuard(AutoModel.from_config(config), hidden_size=16, dropout=0.0)
        batches = [
            {
                "input_ids": torch.randint(0, config.vocab_size, (2, 8)),
                "attention_mask": torch.ones((2, 8), dtype=torch.long),
                "task_ids": torch.tensor([0, 1], dtype=torch.long),
                "labels": torch.tensor([0, 3], dtype=torch.long),
                "pair_ids": torch.tensor([-1, -1], dtype=torch.long),
                "pair_roles": torch.tensor([0, 0], dtype=torch.long),
            }
        ]
        optimizer = torch.optim.AdamW(model.parameters(), lr=1e-3)
        original = model.answerability_head.weight.detach().clone()

        loss = train_epoch(
            model=model,
            batches=batches,
            optimizer=optimizer,
            scheduler=None,
            device=torch.device("cpu"),
            gradient_accumulation=1,
            use_bf16=False,
        )

        self.assertTrue(math.isfinite(loss))
        self.assertFalse(torch.equal(original, model.answerability_head.weight.detach()))


if __name__ == "__main__":
    unittest.main()
