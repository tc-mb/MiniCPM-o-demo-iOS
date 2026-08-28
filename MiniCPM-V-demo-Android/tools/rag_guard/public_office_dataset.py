"""Build a deterministic public-office RAG Guard holdout from licensed archives.

The generated rows are intentionally marked ``public_office_licensed``.  They
are useful for independent pre-qualification, but must not be represented as a
redacted sample of a private production distribution.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import stat
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Iterable, Mapping, Sequence


DOC2DIAL_SHA256 = "94499fa5259f69018d2458cb948552e7a05f424711a95a562bb9e816a515dc23"
CUAD_SHA256 = "f8161d18bea4e9c05e78fa6dda61c19c846fb8087ea969c172753bc2f45b999a"
MAX_ARCHIVE_BYTES = 512 * 1024 * 1024
MAX_ARCHIVE_ENTRIES = 20_000
MAX_MEMBER_BYTES = 256 * 1024 * 1024
MAX_TOTAL_UNCOMPRESSED_BYTES = 2 * 1024 * 1024 * 1024
MAX_COMPRESSION_RATIO = 500


class ArchiveValidationError(ValueError):
    """Raised when an input archive violates provenance or safety rules."""


@dataclass(frozen=True)
class SourceArchive:
    name: str
    path: Path
    expected_sha256: str | None
    required_members: tuple[str, ...]


@dataclass(frozen=True)
class GoldExample:
    source: str
    source_document_id: str
    domain: str
    question: str
    evidence: str
    answer: str

    @property
    def document_id(self) -> str:
        return f"public-{self.source}:{self.source_document_id}"


@dataclass(frozen=True)
class HoldoutBundle:
    calibration_rows: tuple[dict[str, str], ...]
    test_rows: tuple[dict[str, str], ...]
    manifest: dict[str, object]


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _is_safe_member(name: str) -> bool:
    normalized = name.replace("\\", "/")
    path = PurePosixPath(normalized)
    return (
        bool(normalized)
        and not normalized.startswith("/")
        and not path.is_absolute()
        and ".." not in path.parts
        and not any(":" in part for part in path.parts)
    )


def validate_archive(source: SourceArchive) -> str:
    path = source.path.resolve()
    if not path.is_file():
        raise ArchiveValidationError(f"missing source archive: {source.name}")
    if path.stat().st_size > MAX_ARCHIVE_BYTES:
        raise ArchiveValidationError(f"source archive is too large: {source.name}")
    digest = _sha256(path)
    if source.expected_sha256 is not None and digest != source.expected_sha256.lower():
        raise ArchiveValidationError(f"SHA-256 mismatch for {source.name}")
    try:
        with zipfile.ZipFile(path) as archive:
            entries = archive.infolist()
            if len(entries) > MAX_ARCHIVE_ENTRIES:
                raise ArchiveValidationError(f"too many archive members in {source.name}")
            total_size = 0
            names: set[str] = set()
            for entry in entries:
                if not _is_safe_member(entry.filename):
                    raise ArchiveValidationError(
                        f"unsafe archive member in {source.name}: {entry.filename}"
                    )
                mode = entry.external_attr >> 16
                if stat.S_ISLNK(mode):
                    raise ArchiveValidationError(
                        f"symbolic link archive member in {source.name}: {entry.filename}"
                    )
                if entry.file_size > MAX_MEMBER_BYTES:
                    raise ArchiveValidationError(f"oversized archive member in {source.name}")
                total_size += entry.file_size
                compressed = max(entry.compress_size, 1)
                if entry.file_size / compressed > MAX_COMPRESSION_RATIO:
                    raise ArchiveValidationError(f"unsafe compression ratio in {source.name}")
                names.add(entry.filename)
            if total_size > MAX_TOTAL_UNCOMPRESSED_BYTES:
                raise ArchiveValidationError(f"archive expands beyond safety limit: {source.name}")
            missing = set(source.required_members).difference(names)
            if missing:
                raise ArchiveValidationError(
                    f"missing required member(s) in {source.name}: {', '.join(sorted(missing))}"
                )
    except zipfile.BadZipFile as error:
        raise ArchiveValidationError(f"invalid ZIP archive: {source.name}") from error
    return digest


def _read_json_member(archive: zipfile.ZipFile, name: str) -> Mapping[str, object]:
    with archive.open(name) as source:
        value = json.load(source)
    if not isinstance(value, dict):
        raise ValueError(f"archive member must contain a JSON object: {name}")
    return value


def _clean_text(value: object) -> str:
    if not isinstance(value, str):
        return ""
    return " ".join(value.replace("\u00a0", " ").split())


def _iter_nested_documents(value: object) -> Iterable[tuple[str, Mapping[str, object]]]:
    if not isinstance(value, dict):
        return
    for domain, documents in value.items():
        if not isinstance(domain, str) or not isinstance(documents, dict):
            continue
        for document in documents.values():
            if isinstance(document, dict):
                yield domain, document


def _load_doc2dial(path: Path, expected_sha256: str | None) -> tuple[list[GoldExample], str]:
    required = (
        "doc2dial_doc.json",
        "doc2dial_dial_train.json",
        "doc2dial_dial_validation.json",
    )
    archive_spec = SourceArchive("doc2dial-v1.0.1", path, expected_sha256, required)
    digest = validate_archive(archive_spec)
    with zipfile.ZipFile(path.resolve()) as archive:
        document_payload = _read_json_member(archive, "doc2dial_doc.json")
        documents: dict[str, Mapping[str, object]] = {}
        for _, document in _iter_nested_documents(document_payload.get("doc_data")):
            doc_id = document.get("doc_id")
            if isinstance(doc_id, str):
                documents[doc_id] = document

        dialogues_by_document: dict[str, list[Mapping[str, object]]] = {}
        for member in ("doc2dial_dial_train.json", "doc2dial_dial_validation.json"):
            payload = _read_json_member(archive, member)
            dial_data = payload.get("dial_data")
            if not isinstance(dial_data, dict):
                continue
            for domain_dialogues in dial_data.values():
                if not isinstance(domain_dialogues, dict):
                    continue
                for doc_id, dialogues in domain_dialogues.items():
                    if isinstance(doc_id, str) and isinstance(dialogues, list):
                        dialogues_by_document.setdefault(doc_id, []).extend(
                            item for item in dialogues if isinstance(item, dict)
                        )

    examples: list[GoldExample] = []
    for doc_id in sorted(dialogues_by_document):
        document = documents.get(doc_id)
        if document is None or not isinstance(document.get("spans"), dict):
            continue
        spans = document["spans"]
        assert isinstance(spans, dict)
        chosen: GoldExample | None = None
        for dialogue in dialogues_by_document[doc_id]:
            turns = dialogue.get("turns")
            if not isinstance(turns, list):
                continue
            for user_turn, agent_turn in zip(turns, turns[1:]):
                if not isinstance(user_turn, dict) or not isinstance(agent_turn, dict):
                    continue
                if user_turn.get("role") != "user" or agent_turn.get("role") != "agent":
                    continue
                references = agent_turn.get("references")
                if not isinstance(references, list):
                    continue
                evidence_parts: list[str] = []
                for reference in references:
                    if not isinstance(reference, dict):
                        continue
                    span = spans.get(str(reference.get("sp_id")))
                    if isinstance(span, dict):
                        text = _clean_text(span.get("text_sp"))
                        if text and text not in evidence_parts:
                            evidence_parts.append(text)
                question = _clean_text(user_turn.get("utterance"))
                answer = _clean_text(agent_turn.get("utterance"))
                evidence = " ".join(evidence_parts)
                if min(len(question), len(evidence), len(answer)) >= 12:
                    chosen = GoldExample(
                        "doc2dial",
                        doc_id,
                        _clean_text(document.get("domain")) or "public-service",
                        question,
                        evidence,
                        answer,
                    )
                    break
            if chosen is not None:
                break
        if chosen is not None:
            examples.append(chosen)
    return examples, digest


def _evidence_window(context: str, answer_start: int, answer: str, radius: int = 320) -> str:
    start = max(0, answer_start - radius)
    end = min(len(context), answer_start + len(answer) + radius)
    return _clean_text(context[start:end])


def _load_cuad(path: Path, expected_sha256: str | None) -> tuple[list[GoldExample], str]:
    archive_spec = SourceArchive("cuad-v1", path, expected_sha256, ("CUADv1.json",))
    digest = validate_archive(archive_spec)
    with zipfile.ZipFile(path.resolve()) as archive:
        payload = _read_json_member(archive, "CUADv1.json")
    data = payload.get("data")
    if not isinstance(data, list):
        raise ValueError("CUADv1.json is missing data")
    examples: list[GoldExample] = []
    for document in data:
        if not isinstance(document, dict):
            continue
        title = _clean_text(document.get("title"))
        paragraphs = document.get("paragraphs")
        if not title or not isinstance(paragraphs, list):
            continue
        chosen: GoldExample | None = None
        for paragraph in paragraphs:
            if not isinstance(paragraph, dict):
                continue
            context = paragraph.get("context")
            questions = paragraph.get("qas")
            if not isinstance(context, str) or not isinstance(questions, list):
                continue
            for qa in questions:
                if not isinstance(qa, dict) or qa.get("is_impossible") is True:
                    continue
                answers = qa.get("answers")
                if not isinstance(answers, list) or not answers or not isinstance(answers[0], dict):
                    continue
                answer = _clean_text(answers[0].get("text"))
                answer_start = answers[0].get("answer_start")
                question = _clean_text(qa.get("question"))
                if (
                    not isinstance(answer_start, int)
                    or len(answer) < 12
                    or len(question) < 12
                ):
                    continue
                evidence = _evidence_window(context, answer_start, answer)
                if answer not in evidence:
                    continue
                chosen = GoldExample("cuad", title, "contract", question, evidence, answer)
                break
            if chosen is not None:
                break
        if chosen is not None:
            examples.append(chosen)
    return examples, digest


def _rank(example: GoldExample, seed: str) -> str:
    value = f"{seed}\0{example.source}\0{example.source_document_id}".encode("utf-8")
    return hashlib.sha256(value).hexdigest()


def _split_source(
    examples: Sequence[GoldExample], calibration_count: int, test_count: int, seed: str
) -> tuple[list[GoldExample], list[GoldExample]]:
    ordered = sorted(examples, key=lambda item: (_rank(item, seed), item.source_document_id))
    required = calibration_count + test_count
    if len(ordered) < required:
        source = ordered[0].source if ordered else "source"
        raise ValueError(
            f"not enough eligible {source} documents: required {required}, found {len(ordered)}"
        )
    return ordered[:calibration_count], ordered[calibration_count:required]


def _row(
    example: GoldExample,
    *,
    split: str,
    task: str,
    label: str,
    suffix: str,
    evidence: str,
    answer: str,
    construction: str,
    question: str | None = None,
) -> dict[str, str]:
    return {
        "id": f"public-{split}-{example.source}-{_rank(example, 'row-id')[:16]}-{suffix}",
        "task": task,
        "label": label,
        "document_id": example.document_id,
        "distribution": "public_office_licensed",
        "redaction_status": "public_source_reviewed",
        "question": example.question if question is None else question,
        "evidence": evidence,
        "answer": answer,
        "split": split,
        "language": "en",
        "source_dataset": example.source,
        "source_document_id": example.source_document_id,
        "source_domain": example.domain,
        "construction": construction,
    }


def _build_rows(examples: Sequence[GoldExample], split: str) -> tuple[dict[str, str], ...]:
    if len(examples) < 2:
        raise ValueError("each split requires at least two documents for hard negatives")
    rows: list[dict[str, str]] = []
    for index, example in enumerate(examples):
        distractor = examples[(index + 1) % len(examples)]
        rows.extend(
            (
                _row(
                    example,
                    split=split,
                    task="answerability",
                    label="SUPPORTED",
                    suffix="a-supported",
                    evidence=example.evidence,
                    answer="",
                    construction="gold_question_gold_evidence",
                ),
                _row(
                    example,
                    split=split,
                    task="answerability",
                    label="PARTIAL",
                    suffix="a-partial",
                    evidence=example.evidence,
                    answer="",
                    construction="gold_question_plus_unanswerable_subquestion",
                    question=(
                        f"{example.question} Also answer this separate question: "
                        f"{distractor.question}"
                    ),
                ),
                _row(
                    example,
                    split=split,
                    task="answerability",
                    label="UNSUPPORTED",
                    suffix="a-unsupported",
                    evidence=example.evidence,
                    answer="",
                    construction="same_split_distractor_question_gold_evidence",
                    question=distractor.question,
                ),
                _row(
                    example,
                    split=split,
                    task="groundedness",
                    label="GROUNDED",
                    suffix="g-grounded",
                    evidence=example.evidence,
                    answer=example.answer,
                    construction="gold_question_evidence_answer",
                ),
                _row(
                    example,
                    split=split,
                    task="groundedness",
                    label="PARTIAL",
                    suffix="g-partial",
                    evidence=example.evidence,
                    answer=(
                        f"{example.answer} An additional condition is: {distractor.answer}"
                    ),
                    construction="gold_answer_plus_unsupported_clause",
                ),
                _row(
                    example,
                    split=split,
                    task="groundedness",
                    label="UNGROUNDED",
                    suffix="g-ungrounded",
                    evidence=example.evidence,
                    answer=distractor.answer,
                    construction="same_split_distractor_answer",
                ),
            )
        )
    return tuple(rows)


def build_public_holdout(
    doc2dial_zip: Path,
    cuad_zip: Path,
    *,
    calibration_documents_per_source: int = 20,
    test_documents_per_source: int = 20,
    split_seed: str = "minicpm-rag-guard-public-office-v1",
    doc2dial_sha256: str | None = None,
    cuad_sha256: str | None = None,
) -> HoldoutBundle:
    if calibration_documents_per_source < 1 or test_documents_per_source < 1:
        raise ValueError("document counts must be positive")
    doc2dial, doc2dial_digest = _load_doc2dial(doc2dial_zip, doc2dial_sha256)
    cuad, cuad_digest = _load_cuad(cuad_zip, cuad_sha256)
    calibration: list[GoldExample] = []
    test: list[GoldExample] = []
    for examples in (doc2dial, cuad):
        source_calibration, source_test = _split_source(
            examples,
            calibration_documents_per_source,
            test_documents_per_source,
            split_seed,
        )
        calibration.extend(source_calibration)
        test.extend(source_test)
    calibration_rows = _build_rows(calibration, "calibration")
    test_rows = _build_rows(test, "test")
    manifest: dict[str, object] = {
        "schema_version": 1,
        "distribution": "public_office_licensed",
        "qualification_scope": "public_prequalification_only",
        "split_seed": split_seed,
        "documents": {
            "calibration": sorted({row["document_id"] for row in calibration_rows}),
            "test": sorted({row["document_id"] for row in test_rows}),
        },
        "row_counts": {
            "calibration": len(calibration_rows),
            "test": len(test_rows),
        },
        "sources": {
            "doc2dial": {
                "version": "1.0.1",
                "sha256": doc2dial_digest,
                "license": "CC BY 3.0 (dataset card); Apache-2.0 applies to repository code",
                "homepage": "https://doc2dial.github.io/",
                "download_url": "https://doc2dial.github.io/file/doc2dial_v1.0.1.zip",
                "license_url": "https://huggingface.co/datasets/IBM/doc2dial",
            },
            "cuad": {
                "version": "1.0",
                "sha256": cuad_digest,
                "license": "CC BY 4.0",
                "homepage": "https://www.atticusprojectai.org/cuad",
                "download_url": "https://github.com/TheAtticusProject/cuad/raw/main/data.zip",
                "license_url": "https://www.atticusprojectai.org/legal/",
            },
        },
    }
    return HoldoutBundle(calibration_rows, test_rows, manifest)


def _write_jsonl(path: Path, rows: Sequence[Mapping[str, str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as output:
        for row in rows:
            output.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    temporary.replace(path)


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build a deterministic licensed public-office RAG Guard holdout."
    )
    parser.add_argument("--doc2dial", type=Path, required=True)
    parser.add_argument("--cuad", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--calibration-documents-per-source", type=int, default=20)
    parser.add_argument("--test-documents-per-source", type=int, default=20)
    parser.add_argument("--split-seed", default="minicpm-rag-guard-public-office-v1")
    parser.add_argument("--doc2dial-sha256", default=DOC2DIAL_SHA256)
    parser.add_argument("--cuad-sha256", default=CUAD_SHA256)
    return parser.parse_args()


def main() -> int:
    arguments = _parse_args()
    output_dir = arguments.output_dir.resolve()
    bundle = build_public_holdout(
        arguments.doc2dial,
        arguments.cuad,
        calibration_documents_per_source=arguments.calibration_documents_per_source,
        test_documents_per_source=arguments.test_documents_per_source,
        split_seed=arguments.split_seed,
        doc2dial_sha256=arguments.doc2dial_sha256,
        cuad_sha256=arguments.cuad_sha256,
    )
    calibration_path = output_dir / "public_office_calibration_unscored.jsonl"
    test_path = output_dir / "public_office_test_unscored.jsonl"
    _write_jsonl(calibration_path, bundle.calibration_rows)
    _write_jsonl(test_path, bundle.test_rows)
    manifest = dict(bundle.manifest)
    manifest["outputs"] = {
        calibration_path.name: {
            "rows": len(bundle.calibration_rows),
            "sha256": _sha256(calibration_path),
        },
        test_path.name: {
            "rows": len(bundle.test_rows),
            "sha256": _sha256(test_path),
        },
    }
    manifest_path = output_dir / "public_office_manifest.json"
    temporary = manifest_path.with_suffix(".json.tmp")
    temporary.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(manifest_path)
    print(json.dumps(manifest["row_counts"], sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
