import hashlib
import tempfile
import unittest
from pathlib import Path

import numpy as np

from tools.rag_guard.export_onnx import (
    EVALUATION_BATCH_SIZE,
    EVALUATED_SPLITS,
    PER_CHANNEL_QUANTIZATION,
    QUANTIZED_OP_TYPES,
    TEST_EVALUATED,
    _task_metrics,
    build_artifact_manifest,
    build_production_manifest,
    reusable_export_paths,
)


class ExportOnnxTest(unittest.TestCase):
    def test_quantization_uses_the_regression_safe_per_tensor_mode(self) -> None:
        self.assertFalse(PER_CHANNEL_QUANTIZATION)

    def test_quantization_includes_the_large_token_embedding_gather(self) -> None:
        self.assertIn("Gather", QUANTIZED_OP_TYPES)

    def test_manifest_pins_model_contract_size_and_sha256(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            model = Path(directory) / "model.int8.onnx"
            model.write_bytes(b"quantized-model")
            manifest = build_artifact_manifest(
                model_path=model,
                tokenizer_sha256="a" * 64,
                metrics={"agreement": 1.0},
                max_tokens=256,
            )

        self.assertEqual(manifest["files"]["model.int8.onnx"]["bytes"], 15)
        self.assertEqual(
            manifest["files"]["model.int8.onnx"]["sha256"],
            hashlib.sha256(b"quantized-model").hexdigest(),
        )
        self.assertEqual(
            manifest["inputs"],
            {
                "input_ids": "int64[batch,sequence]",
                "attention_mask": "int64[batch,sequence]",
                "task_ids": "int64[batch]",
            },
        )
        self.assertEqual(manifest["architecture"], "shared_encoder_three_plus_four_heads")
        self.assertEqual(
            manifest["labels_by_task"],
            {
                "answerability": ("SUPPORTED", "PARTIAL", "UNSUPPORTED"),
                "groundedness": ("GROUNDED", "PARTIAL", "UNSUPPORTED", "CONTRADICTED"),
            },
        )
        self.assertEqual(
            manifest["output"],
            {
                "logits": "float32[batch,4]",
                "answerability_padding_logit": -10000.0,
            },
        )

    def test_export_boundary_is_calibration_only(self) -> None:
        self.assertEqual(("calibration",), EVALUATED_SPLITS)
        self.assertFalse(TEST_EVALUATED)
        self.assertEqual(128, EVALUATION_BATCH_SIZE)

    def test_existing_export_is_reusable_only_when_both_models_exist(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertFalse(reusable_export_paths(root))
            (root / "model.fp32.onnx").write_bytes(b"fp32")
            self.assertFalse(reusable_export_paths(root))
            (root / "model.int8.onnx").write_bytes(b"int8")
            self.assertTrue(reusable_export_paths(root))

    def test_production_manifest_records_metrics_without_a_performance_gate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            model = Path(directory) / "model.int8.onnx"
            model.write_bytes(b"failed-gate-model")

            manifest = build_production_manifest(
                model_path=model,
                tokenizer_sha256="a" * 64,
                metrics={
                    "int8_fp32_label_agreement": 0.969,
                    "largest_macro_f1_drop": 0.0108,
                    "test_evaluated": False,
                    "test": None,
                },
                max_tokens=256,
            )

        self.assertEqual(0.969, manifest["quality"]["int8_fp32_label_agreement"])
        self.assertEqual("production", manifest["deployment"]["channel"])
        self.assertEqual("recorded_metrics", manifest["deployment"]["selection_basis"])
        self.assertNotIn("approval", manifest["deployment"])
        self.assertNotIn("quality_gate_passed", manifest["deployment"])

    def test_production_manifest_still_rejects_test_evaluation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            model = Path(directory) / "model.int8.onnx"
            model.write_bytes(b"model")
            with self.assertRaises(ValueError):
                build_production_manifest(
                    model_path=model,
                    tokenizer_sha256="a" * 64,
                    metrics={"test_evaluated": True, "test": {"accuracy": 1.0}},
                    max_tokens=256,
                )

    def test_groundedness_metrics_use_all_four_labels(self) -> None:
        rows = [
            {"task": "groundedness", "label": "GROUNDED"},
            {"task": "groundedness", "label": "PARTIAL"},
            {"task": "groundedness", "label": "UNSUPPORTED"},
            {"task": "groundedness", "label": "CONTRADICTED"},
        ]
        logits = np.asarray(
            [
                [9.0, 0.0, 0.0, 0.0],
                [0.0, 9.0, 0.0, 0.0],
                [0.0, 0.0, 9.0, 0.0],
                [0.0, 0.0, 0.0, 9.0],
            ],
            dtype=np.float32,
        )

        metrics = _task_metrics(rows, logits)

        self.assertEqual(1.0, metrics["groundedness"]["macro_f1"])

if __name__ == "__main__":
    unittest.main()
