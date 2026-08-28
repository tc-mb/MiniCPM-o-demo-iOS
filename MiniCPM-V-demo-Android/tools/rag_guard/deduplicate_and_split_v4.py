"""Deterministic family-level splitting with bounded MinHash-style deduplication."""

from __future__ import annotations

import argparse
import hashlib
import heapq
import json
import re
from collections import defaultdict
from pathlib import Path
from typing import Iterable, Mapping, Sequence


_WHITESPACE = re.compile(r"\s+")
_SIGNATURE_SIZE = 8
_BAND_SIZE = 2
_WORD = re.compile(r"\w+", re.UNICODE)


class _UnionFind:
    def __init__(self, size: int) -> None:
        self.parents = list(range(size))

    def find(self, item: int) -> int:
        while self.parents[item] != item:
            self.parents[item] = self.parents[self.parents[item]]
            item = self.parents[item]
        return item

    def union(self, left: int, right: int) -> None:
        left_root, right_root = self.find(left), self.find(right)
        if left_root != right_root:
            self.parents[max(left_root, right_root)] = min(left_root, right_root)


def _normalize(value: str) -> str:
    return _WHITESPACE.sub(" ", value.casefold()).strip()


def _row_text(row: Mapping[str, object]) -> str:
    evidence = row.get("evidence")
    evidence_text = " ".join(
        str(item.get("text", "")) for item in evidence if isinstance(item, dict)
    ) if isinstance(evidence, list) else ""
    return _normalize(
        "\n".join((str(row.get("question", "")), evidence_text, str(row.get("answer", ""))))
    )


def _signature(text: str) -> tuple[int, ...]:
    tokens = _WORD.findall(text)
    if not tokens:
        return ()
    if len(tokens) < 3:
        shingles = {" ".join(tokens)}
    else:
        shingles = {" ".join(tokens[index : index + 3]) for index in range(len(tokens) - 2)}
    hashes = {
        int.from_bytes(hashlib.blake2b(shingle.encode("utf-8"), digest_size=8).digest(), "big")
        for shingle in shingles
    }
    return tuple(heapq.nsmallest(_SIGNATURE_SIZE, hashes))


def _signature_similarity(left: tuple[int, ...], right: tuple[int, ...]) -> float:
    if not left or not right:
        return 0.0
    return len(set(left) & set(right)) / min(len(left), len(right))


def _bands(signature: tuple[int, ...]) -> list[tuple[int, tuple[int, ...]]]:
    if not signature:
        return []
    if len(signature) < _BAND_SIZE:
        return [(0, signature)]
    return [
        (band_start, signature[band_start : band_start + _BAND_SIZE])
        for band_start in range(0, len(signature), _BAND_SIZE)
        if len(signature[band_start : band_start + _BAND_SIZE]) == _BAND_SIZE
    ]


def _union_family_keys(rows: Sequence[Mapping[str, object]], groups: _UnionFind) -> None:
    observed: dict[tuple[str, str], int] = {}
    keys = (
        "document_id",
        "conversation_id",
        "mutation_family_id",
        "translation_family_id",
        "near_duplicate_cluster_id",
    )
    for index, row in enumerate(rows):
        for key in keys:
            value = row.get(key)
            if not isinstance(value, str) or not value.strip():
                continue
            identity = (key, value)
            if identity in observed:
                groups.union(index, observed[identity])
            else:
                observed[identity] = index


def _union_near_duplicates(
    rows: Sequence[Mapping[str, object]], groups: _UnionFind, threshold: float
) -> None:
    signatures: list[tuple[int, ...]] = []
    buckets: dict[tuple[int, tuple[int, ...]], list[int]] = defaultdict(list)
    for index, row in enumerate(rows):
        signature = _signature(_row_text(row))
        signatures.append(signature)
        candidates: set[int] = set()
        for key in _bands(signature):
            candidates.update(buckets[key])
        for peer in candidates:
            if _signature_similarity(signatures[peer], signature) >= threshold:
                groups.union(peer, index)
        for key in _bands(signature):
            buckets[key].append(index)


def split_rows(
    rows: Sequence[Mapping[str, object]],
    *,
    seed: str,
    near_duplicate_threshold: float = 0.88,
) -> list[dict[str, object]]:
    if not rows or not seed:
        raise ValueError("rows and seed must be non-empty")
    if not 0.0 < near_duplicate_threshold <= 1.0:
        raise ValueError("near_duplicate_threshold must be in (0, 1]")
    groups = _UnionFind(len(rows))
    _union_family_keys(rows, groups)
    _union_near_duplicates(rows, groups, near_duplicate_threshold)
    members: dict[int, list[int]] = defaultdict(list)
    for index in range(len(rows)):
        members[groups.find(index)].append(index)
    split_by_root: dict[int, str] = {}
    cluster_by_root: dict[int, str] = {}
    for root, indices in members.items():
        identities = sorted(str(rows[index].get("id", "")) for index in indices)
        digest = hashlib.sha256((seed + "\0" + "\0".join(identities)).encode("utf-8")).hexdigest()
        bucket = int(digest[:8], 16) % 100
        split_by_root[root] = "train" if bucket < 90 else "calibration" if bucket < 95 else "test"
        cluster_by_root[root] = "near-" + digest[:24]
    result: list[dict[str, object]] = []
    for index, row in enumerate(rows):
        root = groups.find(index)
        updated = dict(row)
        updated["split"] = split_by_root[root]
        updated["near_duplicate_cluster_id"] = cluster_by_root[root]
        result.append(updated)
    return result


def split_rows_with_frozen_test(
    rows: Sequence[Mapping[str, object]],
    frozen_test_rows: Sequence[Mapping[str, object]],
    *,
    seed: str,
    near_duplicate_threshold: float = 0.88,
) -> list[dict[str, object]]:
    if not rows or not frozen_test_rows or not seed:
        raise ValueError("candidate rows, frozen test rows, and seed must be non-empty")
    if not 0.0 < near_duplicate_threshold <= 1.0:
        raise ValueError("near_duplicate_threshold must be in (0, 1]")

    frozen_ids: set[str] = set()
    frozen: list[Mapping[str, object]] = []
    for row in frozen_test_rows:
        row_id = row.get("id")
        if not isinstance(row_id, str) or not row_id or row_id in frozen_ids:
            raise ValueError("frozen test row IDs must be unique non-empty strings")
        if row.get("split") != "test":
            raise ValueError("frozen test rows must retain split=test")
        frozen_ids.add(row_id)
        frozen.append(row)

    candidates: list[Mapping[str, object]] = []
    observed_candidate_ids: set[str] = set()
    for row in rows:
        row_id = row.get("id")
        if not isinstance(row_id, str) or not row_id:
            raise ValueError("candidate row IDs must be non-empty strings")
        if row_id in frozen_ids:
            continue
        if row_id in observed_candidate_ids:
            raise ValueError("candidate row IDs must be unique")
        observed_candidate_ids.add(row_id)
        candidates.append(row)

    combined = [*frozen, *candidates]
    groups = _UnionFind(len(combined))
    _union_family_keys(combined, groups)
    _union_near_duplicates(combined, groups, near_duplicate_threshold)
    frozen_roots = {groups.find(index) for index in range(len(frozen))}

    candidate_members: dict[int, list[int]] = defaultdict(list)
    for index in range(len(frozen), len(combined)):
        root = groups.find(index)
        if root not in frozen_roots:
            candidate_members[root].append(index)

    split_by_root: dict[int, str] = {}
    cluster_by_root: dict[int, str] = {}
    for root, indices in candidate_members.items():
        identities = sorted(str(combined[index]["id"]) for index in indices)
        digest = hashlib.sha256((seed + "\0" + "\0".join(identities)).encode("utf-8")).hexdigest()
        bucket = int(digest[:8], 16) % 100
        split_by_root[root] = "train" if bucket < 95 else "calibration"
        cluster_by_root[root] = "near-" + digest[:24]

    result = [dict(row) for row in frozen]
    for index in range(len(frozen), len(combined)):
        root = groups.find(index)
        if root in frozen_roots:
            continue
        updated = dict(combined[index])
        updated["split"] = split_by_root[root]
        updated["near_duplicate_cluster_id"] = cluster_by_root[root]
        result.append(updated)
    return result


def _read_jsonl(path: Path) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    with path.resolve(strict=True).open("r", encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            value = json.loads(line)
            if not isinstance(value, dict):
                raise ValueError(f"line {line_number} must be an object")
            rows.append(value)
    return rows


def _read_jsonl_directory(directory: Path) -> list[dict[str, object]]:
    resolved = directory.resolve(strict=True)
    if not resolved.is_dir():
        raise ValueError("input directory is not a directory")
    rows: list[dict[str, object]] = []
    for path in sorted(resolved.glob("*.jsonl")):
        if path.is_symlink():
            raise ValueError("symbolic-link datasets are not allowed")
        rows.extend(_read_jsonl(path))
    if not rows:
        raise ValueError("input directory contains no JSONL rows")
    return rows


def _read_frozen_test_directory(directory: Path) -> list[dict[str, object]]:
    resolved = directory.resolve(strict=True)
    aggregate = resolved / "all_test.jsonl"
    if not aggregate.is_file() or aggregate.is_symlink():
        raise ValueError("frozen test directory requires a regular all_test.jsonl")
    return _read_jsonl(aggregate)


def _write_jsonl(path: Path, rows: Iterable[Mapping[str, object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as output:
        for row in rows:
            output.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    temporary.replace(path)


def main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--input", type=Path)
    inputs.add_argument("--input-dir", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--frozen-test-dir", type=Path)
    parser.add_argument("--seed", default="minicpm-rag-guard-v4")
    parsed = parser.parse_args(arguments)
    rows = _read_jsonl(parsed.input) if parsed.input is not None else _read_jsonl_directory(parsed.input_dir)
    split = (
        split_rows_with_frozen_test(
            rows,
            _read_frozen_test_directory(parsed.frozen_test_dir),
            seed=parsed.seed,
        )
        if parsed.frozen_test_dir is not None
        else split_rows(rows, seed=parsed.seed)
    )
    tasks = sorted({str(row["task"]) for row in split})
    for name in ("train", "calibration", "test"):
        _write_jsonl(
            parsed.output_dir.resolve() / f"all_{name}.jsonl",
            (row for row in split if row["split"] == name),
        )
        for task in tasks:
            _write_jsonl(
                parsed.output_dir.resolve() / f"{task}_{name}.jsonl",
                (row for row in split if row["split"] == name and row["task"] == task),
            )
    print(json.dumps({name: sum(row["split"] == name for row in split) for name in ("train", "calibration", "test")}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
