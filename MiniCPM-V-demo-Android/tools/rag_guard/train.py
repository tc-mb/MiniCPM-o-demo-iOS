"""Train a shared multilingual encoder with answerability and groundedness heads."""

from __future__ import annotations

import argparse
import json
import math
import random
from contextlib import nullcontext
from pathlib import Path
from typing import Iterable, Mapping, Sequence

import torch
from safetensors.torch import load_file, save_file
from torch import nn
from torch.utils.data import DataLoader, Dataset, Sampler
from transformers import AutoModel, AutoTokenizer, get_linear_schedule_with_warmup

from tools.rag_guard.model import DualHeadRagGuard
from tools.rag_guard.evaluate_slices import checkpoint_selection_rank, eligible_checkpoint, per_class_metrics
from tools.rag_guard.hard_types_v4 import (
    RELEASE_CONTRADICTION_TYPES,
    build_pair_groups,
    select_pair_members,
)
from tools.rag_guard.training_data import (
    LABELS_BY_TASK_V4,
    encode_model_pairs_v4,
    expected_calibration_error,
    load_jsonl_v4,
    macro_f1,
)
from tools.rag_guard.training_dynamics_v4 import TrainingDynamicsRecorder, select_review_rows
from tools.rag_guard.training_protocol import evaluation_split_names


TASK_IDS = {"answerability": 0, "groundedness": 1}


def is_better_checkpoint(
    *, score: float, ece: float, best_score: float, best_ece: float, tolerance: float = 1e-12
) -> bool:
    if score > best_score + tolerance:
        return True
    return abs(score - best_score) <= tolerance and ece < best_ece


PAIR_HARD_NEGATIVE_TYPES = set(RELEASE_CONTRADICTION_TYPES)
HARD_SLICE_IDS = {name: index for index, name in enumerate(sorted(PAIR_HARD_NEGATIVE_TYPES))}


class EncodedRows(Dataset[dict[str, object]]):
    def __init__(self, rows: Sequence[Mapping[str, object]], tokenizer: object, max_length: int) -> None:
        if not 32 <= max_length <= 1024:
            raise ValueError("max_length must be between 32 and 1024")
        self.encodings = encode_model_pairs_v4(rows, tokenizer=tokenizer, max_length=max_length)
        self.task_ids = [TASK_IDS[row["task"]] for row in rows]
        self.labels = [LABELS_BY_TASK_V4[str(row["task"])].index(str(row["label"])) for row in rows]
        families: dict[str, set[str]] = {}
        for row in rows:
            if row["task"] == "groundedness" and (
                row["label"] == "GROUNDED" or row.get("hard_negative_type") in PAIR_HARD_NEGATIVE_TYPES
            ):
                families.setdefault(str(row["mutation_family_id"]), set()).add(str(row["label"]))
        eligible = sorted(
            family for family, labels in families.items() if {"GROUNDED", "CONTRADICTED"} <= labels
        )
        family_ids = {family: index for index, family in enumerate(eligible)}
        self.pair_ids = [family_ids.get(str(row["mutation_family_id"]), -1) for row in rows]
        self.pair_roles = [
            1 if pair_id >= 0 and row["label"] == "GROUNDED" else -1 if pair_id >= 0 and row["label"] == "CONTRADICTED" else 0
            for row, pair_id in zip(rows, self.pair_ids)
        ]
        self.slice_ids = [HARD_SLICE_IDS.get(str(row.get("hard_negative_type")), -1) for row in rows]
        self.row_indices = list(range(len(rows)))

    def __len__(self) -> int:
        return len(self.labels)

    def __getitem__(self, index: int) -> dict[str, object]:
        return {
            "input_ids": self.encodings["input_ids"][index],
            "attention_mask": self.encodings["attention_mask"][index],
            "task_ids": self.task_ids[index],
            "labels": self.labels[index],
            "pair_ids": self.pair_ids[index],
            "pair_roles": self.pair_roles[index],
            "slice_ids": self.slice_ids[index],
            "row_indices": self.row_indices[index],
        }


class HardPairBatchSampler(Sampler[list[int]]):
    """Build deterministic batches that each include a grounded/contradicted family pair."""

    def __init__(self, dataset: EncodedRows, *, batch_size: int, seed: int) -> None:
        if batch_size < 2:
            raise ValueError("batch_size must be at least two")
        self.pair_groups = build_pair_groups(dataset.pair_ids, dataset.pair_roles)
        if not self.pair_groups:
            raise ValueError("training data must contain at least one eligible hard pair")
        self.size = len(dataset)
        self.batch_size = batch_size
        self.seed = seed
        self.epoch = 0

    def _batches(self, epoch: int) -> list[list[int]]:
        rng = random.Random(self.seed + epoch)
        remaining = list(range(self.size))
        rng.shuffle(remaining)
        remaining_set = set(remaining)
        pairs = list(select_pair_members(self.pair_groups, epoch=epoch))
        rng.shuffle(pairs)
        result: list[list[int]] = []
        pair_index = 0
        while remaining_set:
            pair = pairs[pair_index % len(pairs)]
            pair_index += 1
            batch: list[int] = []
            for index in pair:
                batch.append(index)
                remaining_set.discard(index)
            while remaining and len(batch) < self.batch_size:
                index = remaining.pop()
                if index in remaining_set:
                    remaining_set.remove(index)
                    batch.append(index)
            result.append(batch)
        return result

    def __iter__(self):
        batches = self._batches(self.epoch)
        self.epoch += 1
        return iter(batches)

    def __len__(self) -> int:
        return len(self._batches(self.epoch))


def make_collator(tokenizer: object):
    def collate(rows: Sequence[Mapping[str, object]]) -> dict[str, torch.Tensor]:
        encoded = tokenizer.pad(
            {
                "input_ids": [row["input_ids"] for row in rows],
                "attention_mask": [row["attention_mask"] for row in rows],
            },
            padding=True,
            return_tensors="pt",
        )
        encoded["task_ids"] = torch.tensor([row["task_ids"] for row in rows], dtype=torch.long)
        encoded["labels"] = torch.tensor([row["labels"] for row in rows], dtype=torch.long)
        encoded["pair_ids"] = torch.tensor([row["pair_ids"] for row in rows], dtype=torch.long)
        encoded["pair_roles"] = torch.tensor([row["pair_roles"] for row in rows], dtype=torch.long)
        encoded["slice_ids"] = torch.tensor([row["slice_ids"] for row in rows], dtype=torch.long)
        encoded["row_indices"] = torch.tensor([row["row_indices"] for row in rows], dtype=torch.long)
        return encoded

    return collate


def joint_guard_loss(
    logits: torch.Tensor,
    task_ids: torch.Tensor,
    labels: torch.Tensor,
    pair_ids: torch.Tensor,
    pair_roles: torch.Tensor,
    *,
    answerability_weight: float = 1.0,
    groundedness_weight: float = 1.5,
    pair_weight: float = 0.25,
    pair_margin: float = 1.0,
) -> torch.Tensor:
    losses: list[torch.Tensor] = []
    answer_mask = task_ids.eq(TASK_IDS["answerability"])
    ground_mask = task_ids.eq(TASK_IDS["groundedness"])
    if answer_mask.any():
        losses.append(answerability_weight * torch.nn.functional.cross_entropy(logits[answer_mask, :3], labels[answer_mask]))
    if ground_mask.any():
        losses.append(groundedness_weight * torch.nn.functional.cross_entropy(logits[ground_mask, :4], labels[ground_mask]))
    if not losses:
        raise ValueError("batch contains no supported task")
    pair_losses: list[torch.Tensor] = []
    score = logits[:, 0] - logits[:, 3]
    for pair_id in pair_ids[pair_ids.ge(0)].unique():
        positive = pair_ids.eq(pair_id) & pair_roles.eq(1)
        negative = pair_ids.eq(pair_id) & pair_roles.eq(-1)
        if positive.any() and negative.any():
            pair_losses.append(torch.relu(pair_margin - score[positive][0] + score[negative][0]))
    if pair_losses:
        losses.append(pair_weight * torch.stack(pair_losses).mean())
    return torch.stack(losses).sum()


def train_epoch(
    *,
    model: nn.Module,
    batches: Iterable[Mapping[str, torch.Tensor]],
    optimizer: torch.optim.Optimizer,
    scheduler: object | None,
    device: torch.device,
    gradient_accumulation: int,
    use_bf16: bool,
) -> float:
    if gradient_accumulation < 1:
        raise ValueError("gradient_accumulation must be positive")
    model.train()
    optimizer.zero_grad(set_to_none=True)
    total_loss = 0.0
    batch_count = len(batches)  # type: ignore[arg-type]
    for batch_index, batch in enumerate(batches):
        moved = {key: value.to(device, non_blocking=True) for key, value in batch.items()}
        autocast = (
            torch.autocast(device_type="cuda", dtype=torch.bfloat16)
            if use_bf16 and device.type == "cuda"
            else nullcontext()
        )
        with autocast:
            logits = model(moved["input_ids"], moved["attention_mask"], moved["task_ids"])
            loss = joint_guard_loss(
                logits,
                moved["task_ids"],
                moved["labels"],
                moved["pair_ids"],
                moved["pair_roles"],
            )
        total_loss += float(loss.detach().cpu())
        (loss / gradient_accumulation).backward()
        should_step = (batch_index + 1) % gradient_accumulation == 0 or batch_index + 1 == batch_count
        if should_step:
            torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
            optimizer.step()
            if scheduler is not None:
                scheduler.step()
            optimizer.zero_grad(set_to_none=True)
    if batch_count == 0:
        raise ValueError("training batches must be non-empty")
    return total_loss / batch_count


@torch.no_grad()
def evaluate(
    model: nn.Module,
    batches: Iterable[Mapping[str, torch.Tensor]],
    device: torch.device,
    *,
    row_ids: Sequence[str] | None = None,
    dynamics: TrainingDynamicsRecorder | None = None,
    dynamics_epoch: int | None = None,
) -> dict[str, object]:
    dynamics_enabled = any(value is not None for value in (row_ids, dynamics, dynamics_epoch))
    if dynamics_enabled and (row_ids is None or dynamics is None or dynamics_epoch is None):
        raise ValueError("row IDs, recorder, and dynamics epoch must be provided together")
    model.eval()
    collected: dict[int, dict[str, list[object]]] = {
        0: {"targets": [], "predictions": [], "probabilities": [], "slice_ids": []},
        1: {"targets": [], "predictions": [], "probabilities": [], "slice_ids": []},
    }
    for batch in batches:
        moved = {key: value.to(device, non_blocking=True) for key, value in batch.items()}
        logits = model(moved["input_ids"], moved["attention_mask"], moved["task_ids"]).cpu()
        targets = moved["labels"].cpu()
        task_ids = moved["task_ids"].cpu()
        slice_ids = moved["slice_ids"].cpu()
        row_indices = moved.get("row_indices")
        if dynamics_enabled and row_indices is None:
            raise ValueError("evaluation batches require row_indices for training dynamics")
        observed_indices = row_indices.cpu() if row_indices is not None else None
        for task_id in (0, 1):
            mask = task_ids.eq(task_id)
            if mask.any():
                class_count = len(LABELS_BY_TASK_V4["answerability" if task_id == 0 else "groundedness"])
                selected = torch.softmax(logits[mask, :class_count], dim=-1).cpu()
                selected_probabilities = selected.tolist()
                selected_targets = targets[mask].tolist()
                selected_predictions = selected.argmax(dim=-1).tolist()
                collected[task_id]["probabilities"].extend(selected_probabilities)
                collected[task_id]["targets"].extend(selected_targets)
                collected[task_id]["predictions"].extend(selected_predictions)
                collected[task_id]["slice_ids"].extend(slice_ids[mask].tolist())
                if dynamics_enabled:
                    assert row_ids is not None and dynamics is not None and dynamics_epoch is not None
                    assert observed_indices is not None
                    selected_indices = observed_indices[mask].tolist()
                    task_name = "answerability" if task_id == 0 else "groundedness"
                    for row_index, target, prediction, probabilities in zip(
                        selected_indices,
                        selected_targets,
                        selected_predictions,
                        selected_probabilities,
                    ):
                        if not 0 <= row_index < len(row_ids):
                            raise ValueError("evaluation row index is outside row ID table")
                        dynamics.record(
                            row_ids[row_index],
                            task=task_name,
                            epoch=dynamics_epoch,
                            gold_label=target,
                            predicted_label=prediction,
                            gold_probability=probabilities[target],
                        )
    result: dict[str, object] = {}
    for task, task_id in TASK_IDS.items():
        targets = collected[task_id]["targets"]
        predictions = collected[task_id]["predictions"]
        probabilities = collected[task_id]["probabilities"]
        if not targets:
            raise ValueError(f"evaluation has no rows for {task}")
        accuracy = sum(t == p for t, p in zip(targets, predictions)) / len(targets)
        result[task] = {
            "accuracy": accuracy,
            "macro_f1": macro_f1(targets, predictions, len(LABELS_BY_TASK_V4[task])),
            "ece": expected_calibration_error(probabilities, targets, bins=10),
            "count": float(len(targets)),
            "per_class": per_class_metrics(targets, predictions, LABELS_BY_TASK_V4[task]),
        }
    grounded_targets = collected[TASK_IDS["groundedness"]]["targets"]
    grounded_predictions = collected[TASK_IDS["groundedness"]]["predictions"]
    grounded_slices = collected[TASK_IDS["groundedness"]]["slice_ids"]
    contradicted_index = LABELS_BY_TASK_V4["groundedness"].index("CONTRADICTED")
    hard_slices: dict[str, dict[str, float]] = {}
    for name, slice_id in HARD_SLICE_IDS.items():
        indices = [
            index
            for index, (target, observed_slice) in enumerate(zip(grounded_targets, grounded_slices))
            if target == contradicted_index and observed_slice == slice_id
        ]
        if indices:
            hard_slices[name] = {
                "recall": sum(grounded_predictions[index] == contradicted_index for index in indices) / len(indices),
                "count": float(len(indices)),
            }
    result["hard_slices"] = hard_slices
    return result


def _load_split(data_dir: Path, split: str) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    for task in TASK_IDS:
        rows.extend(
            load_jsonl_v4(
                data_dir / f"{task}_{split}.jsonl",
                expected_task=task,
                expected_split=split,
            )
        )
    return rows


def _write_json(path: Path, value: object) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def _write_jsonl(path: Path, rows: Sequence[Mapping[str, object]]) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as output:
        for row in rows:
            output.write(json.dumps(dict(row), ensure_ascii=False, sort_keys=True) + "\n")
    temporary.replace(path)


def _state_dict_on_cpu(model: nn.Module) -> dict[str, torch.Tensor]:
    return {name: tensor.detach().cpu().contiguous() for name, tensor in model.state_dict().items()}


def run_training(arguments: argparse.Namespace) -> dict[str, object]:
    random.seed(arguments.seed)
    torch.manual_seed(arguments.seed)
    torch.cuda.manual_seed_all(arguments.seed)
    torch.backends.cuda.matmul.allow_tf32 = True
    torch.backends.cudnn.allow_tf32 = True

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    if device.type != "cuda" and not arguments.allow_cpu:
        raise RuntimeError("CUDA is required unless --allow-cpu is explicitly set")
    data_dir = arguments.data_dir.resolve()
    output_dir = arguments.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    train_rows = _load_split(data_dir, "train")
    evaluate_test = bool(getattr(arguments, "evaluate_test", False))
    evaluation_rows = {
        split: _load_split(data_dir, split)
        for split in evaluation_split_names(evaluate_test=evaluate_test)
    }
    calibration_rows = evaluation_rows["calibration"]
    tokenizer = AutoTokenizer.from_pretrained(arguments.model, use_fast=True)
    encoder = AutoModel.from_pretrained(arguments.model)
    hidden_size = int(encoder.config.hidden_size)
    model = DualHeadRagGuard(encoder, hidden_size=hidden_size, dropout=arguments.dropout).to(device)

    collator = make_collator(tokenizer)
    train_dataset = EncodedRows(train_rows, tokenizer, arguments.max_length)
    train_loader = DataLoader(
        train_dataset,
        batch_sampler=HardPairBatchSampler(train_dataset, batch_size=arguments.batch_size, seed=arguments.seed),
        collate_fn=collator,
        pin_memory=device.type == "cuda",
    )
    calibration_dataset = EncodedRows(calibration_rows, tokenizer, arguments.max_length)
    calibration_loader = DataLoader(
        calibration_dataset,
        batch_size=arguments.eval_batch_size,
        shuffle=False,
        collate_fn=collator,
        pin_memory=device.type == "cuda",
    )
    test_loader = None
    if evaluate_test:
        test_loader = DataLoader(
            EncodedRows(evaluation_rows["test"], tokenizer, arguments.max_length),
            batch_size=arguments.eval_batch_size,
            shuffle=False,
            collate_fn=collator,
            pin_memory=device.type == "cuda",
        )
    optimizer = torch.optim.AdamW(
        model.parameters(), lr=arguments.learning_rate, weight_decay=arguments.weight_decay
    )
    optimizer_steps_per_epoch = math.ceil(len(train_loader) / arguments.gradient_accumulation)
    total_steps = optimizer_steps_per_epoch * arguments.epochs
    warmup_steps = int(total_steps * arguments.warmup_ratio)
    scheduler = get_linear_schedule_with_warmup(optimizer, warmup_steps, total_steps)

    best_rank: tuple[float, float, float, float, float, float] | None = None
    history: list[dict[str, object]] = []
    calibration_row_ids = tuple(str(row["id"]) for row in calibration_rows)
    dynamics = TrainingDynamicsRecorder()
    checkpoint_path = output_dir / "model.safetensors"
    for epoch in range(1, arguments.epochs + 1):
        loss = train_epoch(
            model=model,
            batches=train_loader,
            optimizer=optimizer,
            scheduler=scheduler,
            device=device,
            gradient_accumulation=arguments.gradient_accumulation,
            use_bf16=arguments.bf16,
        )
        calibration = evaluate(
            model,
            calibration_loader,
            device,
            row_ids=calibration_row_ids,
            dynamics=dynamics,
            dynamics_epoch=epoch,
        )
        eligible = eligible_checkpoint(calibration)
        rank = checkpoint_selection_rank(calibration)
        epoch_result = {
            "epoch": epoch,
            "train_loss": loss,
            "calibration": calibration,
            "eligible": eligible,
            "checkpoint_selection_rank": rank,
        }
        history.append(epoch_result)
        print(json.dumps(epoch_result, ensure_ascii=False, sort_keys=True), flush=True)
        if best_rank is None or rank > best_rank:
            best_rank = rank
            temporary_checkpoint = checkpoint_path.with_suffix(".safetensors.tmp")
            save_file(_state_dict_on_cpu(model), str(temporary_checkpoint))
            temporary_checkpoint.replace(checkpoint_path)

    if best_rank is None or not checkpoint_path.exists():
        raise RuntimeError("training completed without a diagnostic checkpoint")
    model.load_state_dict(load_file(str(checkpoint_path), device=str(device)))
    final_calibration = evaluate(model, calibration_loader, device)
    final_metrics = {
        "best_checkpoint_selection_rank": list(best_rank),
        "release_eligible": eligible_checkpoint(final_calibration),
        "calibration": final_calibration,
        "test": evaluate(model, test_loader, device) if test_loader is not None else None,
        "test_evaluated": evaluate_test,
        "history": history,
    }
    tokenizer.save_pretrained(output_dir / "tokenizer")
    encoder.config.save_pretrained(output_dir / "encoder_config")
    manifest = {
        "schema_version": 2,
        "architecture": "shared_encoder_three_plus_four_heads",
        "base_model": arguments.model,
        "labels_by_task": LABELS_BY_TASK_V4,
        "output": {"logits": "float32[batch,4]", "answerability_padding_logit": -10000.0},
        "task_ids": TASK_IDS,
        "max_length": arguments.max_length,
        "hidden_size": hidden_size,
        "seed": arguments.seed,
        "test_evaluated": evaluate_test,
        "release_eligible": final_metrics["release_eligible"],
        "versions": {
            "torch": torch.__version__,
            "transformers": __import__("transformers").__version__,
        },
    }
    _write_json(output_dir / "manifest.json", manifest)
    _write_json(output_dir / "metrics.json", final_metrics)
    dynamics_summary = dynamics.summarize()
    review_rows = select_review_rows(
        dynamics_summary,
        max_mean_gold_probability=0.55,
        min_variability=0.20,
        min_prediction_flips=2,
    )
    _write_json(output_dir / "training-dynamics.json", dynamics_summary)
    _write_jsonl(output_dir / "review.jsonl", review_rows)
    return final_metrics


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="intfloat/multilingual-e5-small")
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--epochs", type=int, default=4)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--eval-batch-size", type=int, default=32)
    parser.add_argument("--gradient-accumulation", type=int, default=2)
    parser.add_argument("--max-length", type=int, default=256)
    parser.add_argument("--learning-rate", type=float, default=2e-5)
    parser.add_argument("--weight-decay", type=float, default=0.01)
    parser.add_argument("--warmup-ratio", type=float, default=0.1)
    parser.add_argument("--dropout", type=float, default=0.1)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--bf16", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--allow-cpu", action="store_true")
    parser.add_argument(
        "--evaluate-test",
        action="store_true",
        help="evaluate the frozen test split after model selection; disabled by default",
    )
    arguments = parser.parse_args()
    if arguments.epochs < 1 or arguments.batch_size < 1 or arguments.eval_batch_size < 1:
        parser.error("epochs and batch sizes must be positive")
    if arguments.gradient_accumulation < 1 or not 0.0 <= arguments.warmup_ratio < 1.0:
        parser.error("invalid gradient accumulation or warmup ratio")
    return arguments


if __name__ == "__main__":
    run_training(parse_args())
