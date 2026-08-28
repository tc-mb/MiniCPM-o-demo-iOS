"""Score redacted office holdout rows with the pinned ONNX guard package."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
from typing import Callable, Mapping, Sequence

from tools.rag_guard.quality_gate import validate_redacted_text
from tools.rag_guard.training_data import LABELS_BY_TASK, format_model_input


TASK_IDS = {"answerability": 0, "groundedness": 1}
APPROVED_PROVENANCE = {
    "real_office_redacted": "reviewed",
    "public_office_licensed": "public_source_reviewed",
}
SHA256_LENGTH = 64
OUTPUT_FIELDS = (
    "id",
    "task",
    "label",
    "document_id",
    "distribution",
    "redaction_status",
    "question",
    "evidence",
    "answer",
)


def _valid_sha256(value: str) -> bool:
    return len(value) == SHA256_LENGTH and all(character in "0123456789abcdef" for character in value)


def _softmax(logits: Sequence[float]) -> list[float]:
    if len(logits) != 3 or any(not math.isfinite(value) for value in logits):
        raise ValueError("guard logits must contain three finite values")
    maximum = max(logits)
    exponentials = [math.exp(value - maximum) for value in logits]
    denominator = sum(exponentials)
    return [value / denominator for value in exponentials]


def _validate_office_row(
    row: Mapping[str, object], *, expected_distribution: str
) -> None:
    row_id = row.get("id")
    task = row.get("task")
    label = row.get("label")
    document_id = row.get("document_id")
    if not isinstance(row_id, str) or not row_id:
        raise ValueError("office row ID must be a non-empty string")
    if (
        not isinstance(task, str)
        or not isinstance(label, str)
        or task not in LABELS_BY_TASK
        or label not in LABELS_BY_TASK[task]
    ):
        raise ValueError(f"invalid task or label for {row_id}")
    if not isinstance(document_id, str) or not document_id:
        raise ValueError(f"missing document_id for {row_id}")
    if expected_distribution not in APPROVED_PROVENANCE:
        raise ValueError("unsupported expected office distribution")
    if row.get("distribution") != expected_distribution:
        raise ValueError(f"unapproved office distribution for {row_id}")
    if row.get("redaction_status") != APPROVED_PROVENANCE[expected_distribution]:
        raise ValueError(f"office row has not been reviewed for redaction: {row_id}")
    for field in ("question", "evidence", "answer"):
        value = row.get(field)
        if not isinstance(value, str):
            raise ValueError(f"missing text field {field} for {row_id}")
        validate_redacted_text(value)
    format_model_input(row)  # type: ignore[arg-type]


def score_rows(
    rows: Sequence[Mapping[str, object]],
    *,
    tokenize: Callable[[str], Sequence[int]],
    infer: Callable[[list[int], list[int], int], Sequence[float]],
    model_sha256: str,
    tokenizer_sha256: str,
    max_tokens: int = 256,
    expected_distribution: str = "real_office_redacted",
) -> list[dict[str, object]]:
    if not rows:
        raise ValueError("office holdout must not be empty")
    if not _valid_sha256(model_sha256) or not _valid_sha256(tokenizer_sha256):
        raise ValueError("model and tokenizer hashes must be lowercase SHA-256")
    if not 2 <= max_tokens <= 256:
        raise ValueError("max_tokens must be between 2 and 256")
    result: list[dict[str, object]] = []
    seen_ids: set[str] = set()
    for row in rows:
        _validate_office_row(row, expected_distribution=expected_distribution)
        row_id = str(row["id"])
        if row_id in seen_ids:
            raise ValueError("office row IDs must be unique")
        text = format_model_input(row)  # type: ignore[arg-type]
        token_ids = list(tokenize(text))
        if not token_ids or any(not isinstance(value, int) or isinstance(value, bool) for value in token_ids):
            raise ValueError(f"tokenizer returned invalid IDs for {row_id}")
        if len(token_ids) > max_tokens:
            end_token = token_ids[-1]
            token_ids = token_ids[:max_tokens]
            token_ids[-1] = end_token
        attention_mask = [1] * len(token_ids)
        probabilities = _softmax(
            [
                float(value)
                for value in infer(token_ids, attention_mask, TASK_IDS[str(row["task"])])
            ]
        )
        scored = {field: row[field] for field in OUTPUT_FIELDS}
        scored["probabilities"] = probabilities
        scored["model_sha256"] = model_sha256
        scored["tokenizer_sha256"] = tokenizer_sha256
        result.append(scored)
        seen_ids.add(row_id)
    return result


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.resolve().open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _load_jsonl(path: Path) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    with path.resolve().open("r", encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            try:
                value = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"invalid JSON on line {line_number}") from error
            if not isinstance(value, dict):
                raise ValueError(f"line {line_number} must contain an object")
            rows.append(value)
    return rows


def _write_jsonl(path: Path, rows: Sequence[Mapping[str, object]]) -> None:
    path = path.resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as output:
        for row in rows:
            output.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    temporary.replace(path)


def _load_manifest(path: Path, model_path: Path, tokenizer_path: Path) -> tuple[str, str, int]:
    value = json.loads(path.resolve().read_text(encoding="utf-8"))
    if not isinstance(value, dict) or value.get("schema_version") != 1:
        raise ValueError("unsupported guard manifest")
    files = value.get("files")
    if not isinstance(files, dict) or set(files) != {"model.int8.onnx"}:
        raise ValueError("guard manifest must pin model.int8.onnx")
    model_spec = files["model.int8.onnx"]
    if not isinstance(model_spec, dict):
        raise ValueError("invalid guard model manifest entry")
    model_sha256 = _sha256(model_path)
    tokenizer_sha256 = _sha256(tokenizer_path)
    if model_path.name != "model.int8.onnx" or model_spec.get("sha256") != model_sha256:
        raise ValueError("guard model SHA-256 mismatch")
    if model_spec.get("bytes") != model_path.stat().st_size:
        raise ValueError("guard model length mismatch")
    if tokenizer_path.name != "tokenizer.onnx" or value.get("external_tokenizer_sha256") != tokenizer_sha256:
        raise ValueError("tokenizer SHA-256 mismatch")
    max_tokens = value.get("max_tokens")
    if not isinstance(max_tokens, int) or isinstance(max_tokens, bool) or not 2 <= max_tokens <= 256:
        raise ValueError("invalid max_tokens in guard manifest")
    return model_sha256, tokenizer_sha256, max_tokens


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Score a redacted office holdout with pinned ONNX files.")
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--tokenizer", type=Path, required=True)
    parser.add_argument(
        "--distribution",
        choices=tuple(APPROVED_PROVENANCE),
        default="real_office_redacted",
    )
    return parser.parse_args()


def main() -> int:
    import numpy as np
    import onnxruntime as ort
    from onnxruntime_extensions import get_library_path

    arguments = _parse_args()
    if arguments.input.resolve() == arguments.output.resolve():
        raise ValueError("input and output paths must be different")
    model_path = arguments.model.resolve()
    tokenizer_path = arguments.tokenizer.resolve()
    model_sha256, tokenizer_sha256, max_tokens = _load_manifest(
        arguments.manifest, model_path, tokenizer_path
    )
    tokenizer_options = ort.SessionOptions()
    tokenizer_options.register_custom_ops_library(get_library_path())
    tokenizer_session = ort.InferenceSession(
        str(tokenizer_path), sess_options=tokenizer_options, providers=["CPUExecutionProvider"]
    )
    model_options = ort.SessionOptions()
    model_options.intra_op_num_threads = 2
    model_session = ort.InferenceSession(
        str(model_path), sess_options=model_options, providers=["CPUExecutionProvider"]
    )

    def tokenize(text: str) -> list[int]:
        outputs = tokenizer_session.run(None, {"inputs": np.asarray([text], dtype=object)})
        return np.asarray(outputs[0], dtype=np.int64).reshape(-1).tolist()

    def infer(ids: list[int], mask: list[int], task_id: int) -> list[float]:
        logits = model_session.run(
            ["logits"],
            {
                "input_ids": np.asarray([ids], dtype=np.int64),
                "attention_mask": np.asarray([mask], dtype=np.int64),
                "task_ids": np.asarray([task_id], dtype=np.int64),
            },
        )[0]
        return np.asarray(logits, dtype=np.float32).reshape(-1).tolist()

    scored = score_rows(
        _load_jsonl(arguments.input),
        tokenize=tokenize,
        infer=infer,
        model_sha256=model_sha256,
        tokenizer_sha256=tokenizer_sha256,
        max_tokens=max_tokens,
        expected_distribution=arguments.distribution,
    )
    _write_jsonl(arguments.output, scored)
    print(
        json.dumps(
            {
                "count": len(scored),
                "model_sha256": model_sha256,
                "tokenizer_sha256": tokenizer_sha256,
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
