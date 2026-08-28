"""Audit a v4 checkpoint on calibration slices without opening frozen test data."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Mapping, Sequence

from tools.rag_guard.evaluate_slices import per_class_metrics
from tools.rag_guard.training_data import LABELS_BY_TASK_V4, macro_f1


def _task_report(
    rows: Sequence[Mapping[str, object]],
    predictions: Sequence[int],
) -> dict[str, dict[str, object]]:
    result: dict[str, dict[str, object]] = {}
    for task, labels in LABELS_BY_TASK_V4.items():
        indices = [index for index, row in enumerate(rows) if row.get("task") == task]
        if not indices:
            continue
        targets = [labels.index(str(rows[index]["label"])) for index in indices]
        observed = [predictions[index] for index in indices]
        result[task] = {
            "count": len(indices),
            "accuracy": sum(target == prediction for target, prediction in zip(targets, observed))
            / len(indices),
            "macro_f1": macro_f1(targets, observed, len(labels)),
            "per_class": per_class_metrics(targets, observed, labels),
        }
    return result


def _grouped_report(
    rows: Sequence[Mapping[str, object]],
    predictions: Sequence[int],
    field: str,
    *,
    excluded_values: frozenset[str] = frozenset(),
) -> dict[str, dict[str, dict[str, object]]]:
    values = sorted(
        {
            str(row[field])
            for row in rows
            if isinstance(row.get(field), str)
            and str(row[field]).strip()
            and str(row[field]) not in excluded_values
        }
    )
    result: dict[str, dict[str, dict[str, object]]] = {}
    for value in values:
        indices = [index for index, row in enumerate(rows) if row.get(field) == value]
        selected_rows = [rows[index] for index in indices]
        selected_predictions = [predictions[index] for index in indices]
        result[value] = _task_report(selected_rows, selected_predictions)
    return result


def summarize_classification_slices(
    rows: Sequence[Mapping[str, object]],
    predictions: Sequence[int],
) -> dict[str, object]:
    """Summarize aligned predictions by task, language, source, and hard type."""

    if not rows or len(rows) != len(predictions):
        raise ValueError("rows and predictions must be aligned and non-empty")
    for row, prediction in zip(rows, predictions):
        task = row.get("task")
        label = row.get("label")
        if task not in LABELS_BY_TASK_V4 or label not in LABELS_BY_TASK_V4[str(task)]:
            raise ValueError("row has an unsupported task or label")
        if (
            not isinstance(prediction, int)
            or isinstance(prediction, bool)
            or not 0 <= prediction < len(LABELS_BY_TASK_V4[str(task)])
        ):
            raise ValueError("prediction is outside the task label space")
    return {
        "overall": _task_report(rows, predictions),
        "by_language": _grouped_report(rows, predictions, "language"),
        "by_source_dataset": _grouped_report(rows, predictions, "source_dataset"),
        "by_hard_negative_type": _grouped_report(
            rows,
            predictions,
            "hard_negative_type",
            excluded_values=frozenset({"", "NONE"}),
        ),
    }


def build_misclassification_records(
    rows: Sequence[Mapping[str, object]],
    predictions: Sequence[int],
) -> list[dict[str, object]]:
    """Return text-free error metadata suitable for sharing and aggregation."""

    summarize_classification_slices(rows, predictions)
    result: list[dict[str, object]] = []
    metadata_fields = (
        "id",
        "task",
        "language",
        "source_dataset",
        "hard_negative_type",
        "mutation_family_id",
        "document_id",
        "source_record_id",
    )
    for row, prediction in zip(rows, predictions):
        task = str(row["task"])
        labels = LABELS_BY_TASK_V4[task]
        gold_label = str(row["label"])
        predicted_label = labels[prediction]
        if predicted_label == gold_label:
            continue
        record = {field: row[field] for field in metadata_fields if field in row}
        record.update(
            {
                "gold_label": gold_label,
                "predicted_label": predicted_label,
                "question_chars": len(str(row.get("question", ""))),
                "evidence_chars": len(str(row.get("evidence", ""))),
                "answer_chars": len(str(row.get("answer", ""))),
            }
        )
        result.append(record)
    return result


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def run_audit(arguments: argparse.Namespace) -> dict[str, object]:
    import torch
    from safetensors.torch import load_file
    from torch.utils.data import DataLoader
    from transformers import AutoModel, AutoTokenizer

    from tools.rag_guard.model import DualHeadRagGuard
    from tools.rag_guard.train import EncodedRows, _load_split, make_collator

    checkpoint_dir = arguments.checkpoint_dir.resolve()
    checkpoint_path = checkpoint_dir / "model.safetensors"
    base_model = arguments.base_model.resolve()
    data_dir = arguments.data_dir.resolve()
    output = arguments.output.resolve()
    if not checkpoint_path.is_file():
        raise ValueError("checkpoint model.safetensors is missing")
    if not base_model.is_dir() or not data_dir.is_dir():
        raise ValueError("base model and data directory must exist")
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    if device.type != "cuda" and not arguments.allow_cpu:
        raise RuntimeError("CUDA is required unless --allow-cpu is explicitly set")

    rows = _load_split(data_dir, "calibration")
    tokenizer = AutoTokenizer.from_pretrained(base_model, local_files_only=True, use_fast=True)
    encoder = AutoModel.from_pretrained(base_model, local_files_only=True)
    model = DualHeadRagGuard(
        encoder,
        hidden_size=int(encoder.config.hidden_size),
        dropout=0.0,
    ).to(device)
    model.load_state_dict(load_file(str(checkpoint_path), device=str(device)))
    model.eval()
    loader = DataLoader(
        EncodedRows(rows, tokenizer, arguments.max_length),
        batch_size=arguments.batch_size,
        shuffle=False,
        collate_fn=make_collator(tokenizer),
        pin_memory=device.type == "cuda",
    )
    predictions: list[int] = []
    with torch.no_grad():
        for batch in loader:
            input_ids = batch["input_ids"].to(device, non_blocking=True)
            attention_mask = batch["attention_mask"].to(device, non_blocking=True)
            task_ids = batch["task_ids"].to(device, non_blocking=True)
            logits = model(input_ids, attention_mask, task_ids).cpu()
            for index, task_id in enumerate(batch["task_ids"].tolist()):
                task = "answerability" if task_id == 0 else "groundedness"
                class_count = len(LABELS_BY_TASK_V4[task])
                predictions.append(int(logits[index, :class_count].argmax().item()))

    report = summarize_classification_slices(rows, predictions)
    report.update(
        {
            "schema_version": 1,
            "evaluated_split": "calibration",
            "test_evaluated": False,
            "checkpoint_sha256": _sha256(checkpoint_path),
            "row_count": len(rows),
            "max_length": arguments.max_length,
        }
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(output)
    errors_output_value = getattr(arguments, "errors_output", None)
    if errors_output_value is not None:
        errors_output = errors_output_value.resolve()
        if errors_output == output:
            raise ValueError("audit and error outputs must be different files")
        errors_output.parent.mkdir(parents=True, exist_ok=True)
        errors_temporary = errors_output.with_suffix(errors_output.suffix + ".tmp")
        with errors_temporary.open("w", encoding="utf-8", newline="\n") as destination:
            for record in build_misclassification_records(rows, predictions):
                destination.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")
        errors_temporary.replace(errors_output)
    return report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint-dir", type=Path, required=True)
    parser.add_argument("--base-model", type=Path, required=True)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--errors-output", type=Path)
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--max-length", type=int, default=256)
    parser.add_argument("--allow-cpu", action="store_true")
    arguments = parser.parse_args()
    if arguments.batch_size < 1 or not 32 <= arguments.max_length <= 1024:
        parser.error("batch size or max length is invalid")
    return arguments


if __name__ == "__main__":
    run_audit(parse_args())
