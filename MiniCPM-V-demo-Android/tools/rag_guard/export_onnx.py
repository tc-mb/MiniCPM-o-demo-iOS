"""Export, dynamically quantize, and verify the dual-head RAG guard model."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
from pathlib import Path
from typing import Mapping, Sequence

from tools.rag_guard.training_data import (
    LABELS_BY_TASK_V4,
    expected_calibration_error,
    format_model_pair_v4,
    load_jsonl_v4,
    macro_f1,
)


TASK_IDS = {"answerability": 0, "groundedness": 1}
EVALUATED_SPLITS = ("calibration",)
TEST_EVALUATED = False
EVALUATION_BATCH_SIZE = 128
QUANTIZED_OP_TYPES = ("MatMul", "Gemm", "Gather")
PER_CHANNEL_QUANTIZATION = False
SAFE_FILE_NAME = re.compile(r"[A-Za-z0-9._-]{1,128}")
SHA256 = re.compile(r"[0-9a-f]{64}")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.resolve().open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def build_artifact_manifest(
    *,
    model_path: Path,
    tokenizer_sha256: str,
    metrics: Mapping[str, object],
    max_tokens: int,
) -> dict[str, object]:
    model_path = model_path.resolve()
    if not model_path.is_file() or not SAFE_FILE_NAME.fullmatch(model_path.name):
        raise ValueError("model_path must be a safe, existing file")
    if not SHA256.fullmatch(tokenizer_sha256):
        raise ValueError("tokenizer_sha256 must be lowercase SHA-256")
    if not 1 <= max_tokens <= 256:
        raise ValueError("max_tokens must be between 1 and 256")
    return {
        "schema_version": 1,
        "architecture": "shared_encoder_three_plus_four_heads",
        "max_tokens": max_tokens,
        "task_ids": TASK_IDS,
        "labels_by_task": LABELS_BY_TASK_V4,
        "inputs": {
            "input_ids": "int64[batch,sequence]",
            "attention_mask": "int64[batch,sequence]",
            "task_ids": "int64[batch]",
        },
        "output": {
            "logits": "float32[batch,4]",
            "answerability_padding_logit": -10000.0,
        },
        "external_tokenizer_sha256": tokenizer_sha256,
        "evaluated_splits": list(EVALUATED_SPLITS),
        "test_evaluated": TEST_EVALUATED,
        "test": None,
        "files": {
            model_path.name: {
                "bytes": model_path.stat().st_size,
                "sha256": _sha256(model_path),
            }
        },
        "quality": dict(metrics),
    }


def build_production_manifest(
    *,
    model_path: Path,
    tokenizer_sha256: str,
    metrics: Mapping[str, object],
    max_tokens: int,
) -> dict[str, object]:
    if metrics.get("test_evaluated") is not False or metrics.get("test") is not None:
        raise ValueError("production manifest requires an unopened frozen test split")
    manifest = build_artifact_manifest(
        model_path=model_path,
        tokenizer_sha256=tokenizer_sha256,
        metrics=metrics,
        max_tokens=max_tokens,
    )
    manifest["deployment"] = {
        "channel": "production",
        "selection_basis": "recorded_metrics",
    }
    return manifest


def _write_json(path: Path, value: object) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def reusable_export_paths(output_dir: Path) -> bool:
    resolved = output_dir.resolve()
    return all(
        path.is_file() and path.stat().st_size > 0
        for path in (resolved / "model.fp32.onnx", resolved / "model.int8.onnx")
    )


def _load_evaluation_rows(data_dir: Path) -> dict[str, list[dict[str, object]]]:
    result: dict[str, list[dict[str, object]]] = {}
    for split in EVALUATED_SPLITS:
        rows: list[dict[str, object]] = []
        for task in TASK_IDS:
            rows.extend(
                load_jsonl_v4(
                    data_dir / f"{task}_{split}.jsonl",
                    expected_task=task,
                    expected_split=split,
                )
            )
        result[split] = rows
    return result


def _load_trained_model(checkpoint_dir: Path, base_model: Path):
    import torch
    from safetensors.torch import load_file
    from transformers import AutoModel, AutoTokenizer

    from tools.rag_guard.model import DualHeadRagGuard

    tokenizer = AutoTokenizer.from_pretrained(base_model, local_files_only=True, use_fast=True)
    encoder = AutoModel.from_pretrained(base_model, local_files_only=True)
    model = DualHeadRagGuard(encoder, hidden_size=int(encoder.config.hidden_size), dropout=0.0)
    model.load_state_dict(load_file(str(checkpoint_dir / "model.safetensors"), device="cpu"))
    model.eval()
    return torch, tokenizer, model


def _export_fp32(torch: object, model: object, output_path: Path, max_tokens: int) -> None:
    sample_ids = torch.ones((2, min(max_tokens, 16)), dtype=torch.long)
    sample_mask = torch.ones_like(sample_ids)
    sample_tasks = torch.tensor([0, 1], dtype=torch.long)
    torch.onnx.export(
        model,
        (sample_ids, sample_mask, sample_tasks),
        str(output_path),
        input_names=["input_ids", "attention_mask", "task_ids"],
        output_names=["logits"],
        dynamic_axes={
            "input_ids": {0: "batch", 1: "sequence"},
            "attention_mask": {0: "batch", 1: "sequence"},
            "task_ids": {0: "batch"},
            "logits": {0: "batch"},
        },
        opset_version=17,
        do_constant_folding=True,
    )


def _quantize(fp32_path: Path, int8_path: Path) -> None:
    from onnxruntime.quantization import QuantType, quantize_dynamic

    quantize_dynamic(
        model_input=str(fp32_path),
        model_output=str(int8_path),
        per_channel=PER_CHANNEL_QUANTIZATION,
        reduce_range=False,
        weight_type=QuantType.QInt8,
        op_types_to_quantize=list(QUANTIZED_OP_TYPES),
        extra_options={"MatMulConstBOnly": True},
    )


def _validate_onnx(path: Path) -> None:
    import onnx
    import onnxruntime as ort

    model = onnx.load(str(path), load_external_data=True)
    onnx.checker.check_model(model, full_check=True)
    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    inputs = {item.name: item.type for item in session.get_inputs()}
    outputs = {item.name: item.type for item in session.get_outputs()}
    if inputs != {
        "input_ids": "tensor(int64)",
        "attention_mask": "tensor(int64)",
        "task_ids": "tensor(int64)",
    }:
        raise RuntimeError(f"unexpected ONNX inputs: {inputs}")
    if outputs != {"logits": "tensor(float)"}:
        raise RuntimeError(f"unexpected ONNX outputs: {outputs}")


def _encoded_batch(
    tokenizer: object,
    rows: Sequence[Mapping[str, object]],
    max_tokens: int,
    *,
    return_tensors: str,
):
    pairs = [format_model_pair_v4(row) for row in rows]
    return tokenizer(
        [pair[0] for pair in pairs],
        [pair[1] for pair in pairs],
        add_special_tokens=True,
        truncation="only_second",
        max_length=max_tokens,
        padding=True,
        return_tensors=return_tensors,
    )


def _session_logits(
    session: object,
    tokenizer: object,
    rows: Sequence[Mapping[str, object]],
    max_tokens: int,
):
    import numpy as np

    all_logits: list[object] = []
    for start in range(0, len(rows), EVALUATION_BATCH_SIZE):
        batch = rows[start : start + EVALUATION_BATCH_SIZE]
        encoded = _encoded_batch(tokenizer, batch, max_tokens, return_tensors="np")
        logits = session.run(
            ["logits"],
            {
                "input_ids": encoded["input_ids"].astype(np.int64, copy=False),
                "attention_mask": encoded["attention_mask"].astype(np.int64, copy=False),
                "task_ids": np.asarray([TASK_IDS[row["task"]] for row in batch], dtype=np.int64),
            },
        )[0]
        all_logits.append(logits)
        completed = min(start + len(batch), len(rows))
        if completed == len(rows) or completed % (EVALUATION_BATCH_SIZE * 10) == 0:
            print(f"progress=inference rows={completed}/{len(rows)}", flush=True)
    return np.concatenate(all_logits, axis=0)


def _pytorch_logits(
    torch: object,
    model: object,
    tokenizer: object,
    rows: Sequence[Mapping[str, object]],
    max_tokens: int,
):
    import numpy as np

    all_logits: list[object] = []
    for start in range(0, len(rows), EVALUATION_BATCH_SIZE):
        batch = rows[start : start + EVALUATION_BATCH_SIZE]
        encoded = _encoded_batch(tokenizer, batch, max_tokens, return_tensors="pt")
        task_ids = torch.tensor([TASK_IDS[row["task"]] for row in batch], dtype=torch.long)
        with torch.no_grad():
            logits = model(encoded["input_ids"], encoded["attention_mask"], task_ids)
        all_logits.append(np.asarray(logits.cpu(), dtype=np.float32))
    return np.concatenate(all_logits, axis=0)


def _softmax(logits):
    import numpy as np

    shifted = logits - logits.max(axis=1, keepdims=True)
    values = np.exp(shifted)
    return values / values.sum(axis=1, keepdims=True)


def _task_metrics(rows: Sequence[Mapping[str, object]], logits) -> dict[str, dict[str, float]]:
    import numpy as np

    result: dict[str, dict[str, float]] = {}
    for task in TASK_IDS:
        indices = [index for index, row in enumerate(rows) if row["task"] == task]
        if not indices:
            continue
        class_count = len(LABELS_BY_TASK_V4[task])
        targets = [LABELS_BY_TASK_V4[task].index(str(rows[index]["label"])) for index in indices]
        selected = _softmax(logits[np.asarray(indices), :class_count])
        predictions = selected.argmax(axis=1).tolist()
        result[task] = {
            "count": float(len(indices)),
            "accuracy": sum(a == b for a, b in zip(targets, predictions)) / len(targets),
            "macro_f1": macro_f1(targets, predictions, class_count),
            "ece": expected_calibration_error(selected.tolist(), targets, bins=10),
        }
    return result


def run_export(arguments: argparse.Namespace) -> dict[str, object]:
    import numpy as np
    import onnxruntime as ort

    checkpoint_dir = arguments.checkpoint_dir.resolve()
    base_model = arguments.base_model.resolve()
    output_dir = arguments.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    print("stage=load_checkpoint", flush=True)
    torch, tokenizer, model = _load_trained_model(checkpoint_dir, base_model)
    fp32_path = output_dir / "model.fp32.onnx"
    int8_path = output_dir / "model.int8.onnx"
    if arguments.reuse_existing:
        if not reusable_export_paths(output_dir):
            raise ValueError("both existing FP32 and INT8 models are required for reuse")
        print("stage=validate_reused_models", flush=True)
        _validate_onnx(fp32_path)
        _validate_onnx(int8_path)
    else:
        print("stage=export_fp32", flush=True)
        _export_fp32(torch, model, fp32_path, arguments.max_tokens)
        _validate_onnx(fp32_path)
        print("stage=quantize_int8", flush=True)
        _quantize(fp32_path, int8_path)
        _validate_onnx(int8_path)

    fp32_session = ort.InferenceSession(str(fp32_path), providers=["CPUExecutionProvider"])
    int8_session = ort.InferenceSession(str(int8_path), providers=["CPUExecutionProvider"])
    evaluation_rows = _load_evaluation_rows(arguments.data_dir.resolve())
    all_fp32: list[object] = []
    all_int8: list[object] = []
    split_metrics: dict[str, object] = {}
    largest_macro_f1_drop = 0.0
    for split, rows in evaluation_rows.items():
        print(f"stage=evaluate_fp32 split={split} rows={len(rows)}", flush=True)
        fp32_logits = _session_logits(fp32_session, tokenizer, rows, arguments.max_tokens)
        print(f"stage=evaluate_int8 split={split} rows={len(rows)}", flush=True)
        int8_logits = _session_logits(int8_session, tokenizer, rows, arguments.max_tokens)
        fp32_metrics = _task_metrics(rows, fp32_logits)
        int8_metrics = _task_metrics(rows, int8_logits)
        for task in TASK_IDS:
            largest_macro_f1_drop = max(
                largest_macro_f1_drop,
                fp32_metrics[task]["macro_f1"] - int8_metrics[task]["macro_f1"],
            )
        split_metrics[split] = {"fp32": fp32_metrics, "int8": int8_metrics}
        all_fp32.append(fp32_logits)
        all_int8.append(int8_logits)

    fp32_logits = np.concatenate(all_fp32, axis=0)
    int8_logits = np.concatenate(all_int8, axis=0)
    label_agreement = float((fp32_logits.argmax(axis=1) == int8_logits.argmax(axis=1)).mean())
    logit_delta = np.abs(fp32_logits - int8_logits)
    calibration_rows = evaluation_rows["calibration"]
    parity_rows: list[dict[str, object]] = []
    for task in TASK_IDS:
        parity_rows.extend([row for row in calibration_rows if row["task"] == task][:128])
    print(f"stage=verify_pytorch_parity rows={len(parity_rows)}", flush=True)
    pytorch_logits = _pytorch_logits(torch, model, tokenizer, parity_rows, arguments.max_tokens)
    parity_fp32 = _session_logits(fp32_session, tokenizer, parity_rows, arguments.max_tokens)
    fp32_pytorch_max_abs = float(np.abs(pytorch_logits - parity_fp32).max())
    metrics = {
        "fp32_pytorch_max_abs": fp32_pytorch_max_abs,
        "int8_fp32_label_agreement": label_agreement,
        "int8_fp32_max_abs_logit_delta": float(logit_delta.max()),
        "int8_fp32_mean_abs_logit_delta": float(logit_delta.mean()),
        "largest_macro_f1_drop": largest_macro_f1_drop,
        "fp32_bytes": fp32_path.stat().st_size,
        "int8_bytes": int8_path.stat().st_size,
        "compression_ratio": int8_path.stat().st_size / fp32_path.stat().st_size,
        "evaluated_splits": list(EVALUATED_SPLITS),
        "test_evaluated": TEST_EVALUATED,
        "test": None,
        "splits": split_metrics,
        "versions": {
            "torch": torch.__version__,
            "onnxruntime": ort.__version__,
        },
    }
    _write_json(output_dir / "quantization_metrics.json", metrics)
    print("stage=record_metrics", flush=True)
    manifest = build_production_manifest(
        model_path=int8_path,
        tokenizer_sha256=arguments.tokenizer_sha256,
        metrics=metrics,
        max_tokens=arguments.max_tokens,
    )
    _write_json(output_dir / "manifest.json", manifest)
    print("stage=complete", flush=True)
    return metrics


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint-dir", type=Path, required=True)
    parser.add_argument("--base-model", type=Path, required=True)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--tokenizer-sha256", required=True)
    parser.add_argument("--max-tokens", type=int, default=256)
    parser.add_argument("--reuse-existing", action="store_true")
    arguments = parser.parse_args()
    if not 1 <= arguments.max_tokens <= 256:
        parser.error("max-tokens must be between 1 and 256")
    if not SHA256.fullmatch(arguments.tokenizer_sha256):
        parser.error("tokenizer-sha256 must be lowercase SHA-256")
    return arguments


if __name__ == "__main__":
    result = run_export(parse_args())
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
