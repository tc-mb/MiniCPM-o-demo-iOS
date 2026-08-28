"""Safe, read-only loaders for licensed RAG Guard v4 source corpora."""

from __future__ import annotations

import html
import json
import pathlib
import sqlite3
import stat
import unicodedata
import zipfile
from dataclasses import dataclass
from pathlib import Path


MAX_SOURCE_BYTES = 512 * 1024 * 1024
MAX_ARCHIVE_ENTRIES = 10_000
MAX_ARCHIVE_UNCOMPRESSED = 1024 * 1024 * 1024
MAX_COMPRESSION_RATIO = 100.0


@dataclass(frozen=True)
class ContractNliRecord:
    split: str
    document_id: str
    hypothesis_id: str
    hypothesis: str
    choice: str
    evidence: str
    full_document: str


@dataclass(frozen=True)
class HoVerRecord:
    split: str
    uid: str
    claim: str
    supporting_facts: tuple[tuple[str, int], ...]
    label: str
    num_hops: int
    hpqa_id: str


def _validate_archive(archive: zipfile.ZipFile) -> None:
    infos = archive.infolist()
    if len(infos) > MAX_ARCHIVE_ENTRIES:
        raise ValueError("archive has too many members")
    seen: set[str] = set()
    total = 0
    for info in infos:
        name = info.filename
        pure = pathlib.PurePosixPath(name)
        if pure.is_absolute() or ".." in pure.parts or "\\" in name:
            raise ValueError("unsafe archive member")
        if name in seen:
            raise ValueError("duplicate archive member")
        seen.add(name)
        mode = (info.external_attr >> 16) & 0o170000
        if mode == stat.S_IFLNK:
            raise ValueError("symbolic links are not allowed")
        total += info.file_size
        if total > MAX_ARCHIVE_UNCOMPRESSED:
            raise ValueError("archive expands beyond safety limit")
        if info.file_size / max(info.compress_size, 1) > MAX_COMPRESSION_RATIO:
            raise ValueError("archive compression ratio exceeds safety limit")


def _required_string(value: object, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field} must be a non-empty string")
    return value.strip()


def load_contract_nli_zip(path: Path) -> list[ContractNliRecord]:
    resolved = path.resolve(strict=True)
    if not resolved.is_file() or resolved.stat().st_size > MAX_SOURCE_BYTES:
        raise ValueError("ContractNLI archive is missing or too large")
    records: list[ContractNliRecord] = []
    with zipfile.ZipFile(resolved) as archive:
        _validate_archive(archive)
        if archive.testzip() is not None:
            raise ValueError("ContractNLI archive failed CRC validation")
        for split in ("train", "dev", "test"):
            member = f"contract-nli/{split}.json"
            try:
                payload = archive.read(member)
            except KeyError as error:
                raise ValueError(f"ContractNLI archive is missing {member}") from error
            value = json.loads(payload.decode("utf-8"))
            if not isinstance(value, dict) or not isinstance(value.get("labels"), dict) or not isinstance(value.get("documents"), list):
                raise ValueError("invalid ContractNLI split payload")
            labels = value["labels"]
            for document in value["documents"]:
                if not isinstance(document, dict):
                    raise ValueError("invalid ContractNLI document")
                document_id = str(document.get("id"))
                text = _required_string(document.get("text"), "ContractNLI text")
                spans = document.get("spans")
                annotation_sets = document.get("annotation_sets")
                if not isinstance(spans, list) or not isinstance(annotation_sets, list) or len(annotation_sets) != 1:
                    raise ValueError("invalid ContractNLI spans or annotation sets")
                annotations = annotation_sets[0].get("annotations") if isinstance(annotation_sets[0], dict) else None
                if not isinstance(annotations, dict):
                    raise ValueError("invalid ContractNLI annotations")
                for hypothesis_id, annotation in annotations.items():
                    label = labels.get(hypothesis_id)
                    if not isinstance(label, dict) or not isinstance(annotation, dict):
                        raise ValueError("ContractNLI annotation lacks label metadata")
                    hypothesis = _required_string(label.get("hypothesis"), "ContractNLI hypothesis")
                    choice = annotation.get("choice")
                    if choice not in {"Entailment", "Contradiction", "NotMentioned"}:
                        raise ValueError("invalid ContractNLI choice")
                    selected: list[str] = []
                    span_indices = annotation.get("spans")
                    if not isinstance(span_indices, list):
                        raise ValueError("invalid ContractNLI evidence span list")
                    for span_index in span_indices:
                        if not isinstance(span_index, int) or not 0 <= span_index < len(spans):
                            raise ValueError("ContractNLI evidence span index is invalid")
                        bounds = spans[span_index]
                        if not isinstance(bounds, list) or len(bounds) != 2 or not all(isinstance(item, int) for item in bounds):
                            raise ValueError("ContractNLI evidence bounds are invalid")
                        start, end = bounds
                        if not 0 <= start <= end <= len(text):
                            raise ValueError("ContractNLI evidence bounds exceed document")
                        selected.append(text[start:end].strip())
                    evidence = " ".join(item for item in selected if item) if selected else text
                    records.append(
                        ContractNliRecord(
                            split=split,
                            document_id=f"contract_nli:{document_id}",
                            hypothesis_id=str(hypothesis_id),
                            hypothesis=hypothesis,
                            choice=str(choice),
                            evidence=evidence,
                            full_document=text,
                        )
                    )
    if not records:
        raise ValueError("ContractNLI archive produced no records")
    return records


class HoVerEvidenceStore:
    def __init__(self, path: Path) -> None:
        self.path = path.resolve(strict=True)
        if not self.path.is_file():
            raise ValueError("HoVer evidence database is missing")
        self.connection: sqlite3.Connection | None = None

    def __enter__(self) -> "HoVerEvidenceStore":
        self.connection = sqlite3.connect(
            self.path.as_uri() + "?mode=ro&immutable=1",
            uri=True,
            timeout=30,
        )
        schema = self.connection.execute("PRAGMA table_info(documents)").fetchall()
        if [row[1] for row in schema] != ["id", "text"]:
            self.connection.close()
            self.connection = None
            raise ValueError("HoVer evidence database schema is invalid")
        return self

    def __exit__(self, _type: object, _value: object, _traceback: object) -> None:
        if self.connection is not None:
            self.connection.close()
            self.connection = None

    def get(self, title: str) -> str:
        if self.connection is None:
            raise RuntimeError("HoVerEvidenceStore must be used as a context manager")
        clean = _required_string(title, "HoVer title")
        variants = (
            clean,
            unicodedata.normalize("NFD", clean),
            unicodedata.normalize("NFD", html.unescape(clean)),
        )
        for variant in dict.fromkeys(variants):
            row = self.connection.execute(
                "SELECT text FROM documents WHERE id = ?",
                (variant,),
            ).fetchone()
            if row is not None and isinstance(row[0], str) and row[0].strip():
                return row[0].strip()
        raise KeyError(clean)


def load_hover_json(path: Path, *, split: str) -> list[HoVerRecord]:
    if split not in {"train", "dev", "test"}:
        raise ValueError("invalid HoVer split")
    resolved = path.resolve(strict=True)
    if not resolved.is_file() or resolved.stat().st_size > MAX_SOURCE_BYTES:
        raise ValueError("HoVer JSON is missing or too large")
    value = json.loads(resolved.read_text(encoding="utf-8"))
    if not isinstance(value, list) or not value:
        raise ValueError("HoVer JSON root must be a non-empty list")
    records: list[HoVerRecord] = []
    seen: set[str] = set()
    for row in value:
        if not isinstance(row, dict):
            raise ValueError("HoVer row must be an object")
        uid = _required_string(row.get("uid"), "HoVer uid")
        if uid in seen:
            raise ValueError("duplicate HoVer uid")
        seen.add(uid)
        claim = _required_string(row.get("claim"), "HoVer claim")
        label = row.get("label")
        if split != "test" and label not in {"SUPPORTED", "NOT_SUPPORTED"}:
            raise ValueError("invalid HoVer label")
        facts_value = row.get("supporting_facts")
        if not isinstance(facts_value, list):
            raise ValueError("HoVer supporting_facts must be a list")
        facts: list[tuple[str, int]] = []
        for fact in facts_value:
            if not isinstance(fact, list) or len(fact) != 2 or not isinstance(fact[1], int) or fact[1] < 0:
                raise ValueError("invalid HoVer supporting fact")
            facts.append((_required_string(fact[0], "HoVer supporting title"), fact[1]))
        if split != "test" and not facts:
            raise ValueError("HoVer labeled row has no supporting facts")
        num_hops = row.get("num_hops")
        if split != "test" and num_hops not in {2, 3, 4}:
            raise ValueError("invalid HoVer hop count")
        records.append(
            HoVerRecord(
                split=split,
                uid=uid,
                claim=claim,
                supporting_facts=tuple(facts),
                label=str(label),
                num_hops=int(num_hops) if isinstance(num_hops, int) else -1,
                hpqa_id=_required_string(row.get("hpqa_id"), "HoVer hpqa_id"),
            )
        )
    return records
