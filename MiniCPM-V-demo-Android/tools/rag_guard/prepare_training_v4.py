"""Fail-closed preflight for licensed RAG Guard v4 training inputs."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePath
from typing import Mapping, Sequence


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def audit_training_inputs(registry: Mapping[str, object], raw_root: Path) -> dict[str, object]:
    sources = registry.get("sources")
    if not isinstance(sources, list):
        raise ValueError("registry sources must be a list")
    resolved_root = raw_root.resolve(strict=True)
    blockers: list[str] = []
    verified: list[dict[str, object]] = []
    for source in sources:
        if not isinstance(source, dict) or source.get("required_for_v4") is not True:
            continue
        source_id = source.get("id")
        if not isinstance(source_id, str) or not source_id or PurePath(source_id).name != source_id:
            raise ValueError("required source id is unsafe")
        if source.get("license_status") != "approved":
            blockers.append(f"{source_id}: license is not approved")
            continue
        acquisition_status = source.get("acquisition_status")
        if acquisition_status != "ready":
            blockers.append(f"{source_id}: acquisition status is {acquisition_status}")
            continue
        files = source.get("official_files")
        if not isinstance(files, list) or not files:
            blockers.append(f"{source_id}: official file manifest is missing")
            continue
        for item in files:
            if not isinstance(item, dict):
                raise ValueError("official file entry must be an object")
            name = item.get("name")
            expected_bytes = item.get("bytes")
            expected_hash = item.get("sha256")
            if not isinstance(name, str) or PurePath(name).name != name:
                raise ValueError("official file name is unsafe")
            if not isinstance(expected_bytes, int) or expected_bytes <= 0:
                blockers.append(f"{source_id}/{name}: byte size is not frozen")
                continue
            if not isinstance(expected_hash, str) or len(expected_hash) != 64:
                blockers.append(f"{source_id}/{name}: SHA-256 is not frozen")
                continue
            path = (resolved_root / source_id / name).resolve()
            if path.parent != (resolved_root / source_id).resolve() or not path.is_file():
                blockers.append(f"{source_id}/{name}: file is missing")
                continue
            actual_size = path.stat().st_size
            actual_hash = _sha256(path)
            if actual_size != expected_bytes or actual_hash != expected_hash:
                blockers.append(f"{source_id}/{name}: size or SHA-256 mismatch")
                continue
            verified.append(
                {"source": source_id, "name": name, "bytes": actual_size, "sha256": actual_hash}
            )
    return {
        "ready_for_dataset_build": not blockers,
        "blockers": blockers,
        "verified_files": verified,
    }


def main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--registry", type=Path, required=True)
    parser.add_argument("--raw-root", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    parsed = parser.parse_args(arguments)
    registry = json.loads(parsed.registry.resolve(strict=True).read_text(encoding="utf-8"))
    if not isinstance(registry, dict):
        raise ValueError("registry must be an object")
    report = audit_training_inputs(registry, parsed.raw_root)
    if parsed.report is not None:
        output = parsed.report.resolve()
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = output.with_suffix(output.suffix + ".tmp")
        temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        temporary.replace(output)
    print(json.dumps(report, ensure_ascii=False, sort_keys=True))
    return 0 if report["ready_for_dataset_build"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
