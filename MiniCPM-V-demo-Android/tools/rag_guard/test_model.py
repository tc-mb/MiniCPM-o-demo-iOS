import unittest

try:
    import torch
    from transformers import AutoModel, BertConfig
except ImportError:  # Local Android-only environments may not have training dependencies.
    torch = None


@unittest.skipIf(torch is None, "training dependencies are not installed")
class DualHeadRagGuardTest(unittest.TestCase):
    def test_mixed_task_batch_routes_gradients_to_both_heads(self) -> None:
        from tools.rag_guard.model import DualHeadRagGuard

        config = BertConfig(
            vocab_size=64,
            hidden_size=16,
            num_hidden_layers=1,
            num_attention_heads=2,
            intermediate_size=32,
        )
        model = DualHeadRagGuard(AutoModel.from_config(config), hidden_size=16)
        input_ids = torch.randint(0, config.vocab_size, (2, 8))
        attention_mask = torch.ones_like(input_ids)
        task_ids = torch.tensor([0, 1], dtype=torch.long)
        labels = torch.tensor([0, 2], dtype=torch.long)

        logits = model(input_ids, attention_mask, task_ids)
        loss = torch.nn.functional.cross_entropy(logits, labels)
        loss.backward()

        self.assertEqual(tuple(logits.shape), (2, 4))
        self.assertLessEqual(logits[0, 3].item(), -1000.0)
        self.assertIsNotNone(model.answerability_head.weight.grad)
        self.assertIsNotNone(model.groundedness_head.weight.grad)


if __name__ == "__main__":
    unittest.main()
