import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from tools.rag_guard.public_office_dataset import (
    ArchiveValidationError,
    SourceArchive,
    build_public_holdout,
    validate_archive,
)


def _write_doc2dial(path: Path, document_count: int = 8) -> None:
    documents = {"dmv": {}}
    dialogues = {"dmv": {}}
    for index in range(document_count):
        doc_id = f"dmv-document-{index}"
        answer = f"The filing deadline is {index + 10} business days after approval."
        documents["dmv"][doc_id] = {
            "title": f"Procedure {index}",
            "doc_id": doc_id,
            "domain": "dmv",
            "doc_text": answer,
            "spans": {
                "1": {
                    "id_sp": "1",
                    "text_sp": answer,
                    "start_sp": 0,
                    "end_sp": len(answer),
                }
            },
        }
        dialogues["dmv"][doc_id] = [
            {
                "dial_id": f"dialogue-{index}",
                "doc_id": doc_id,
                "domain": "dmv",
                "turns": [
                    {
                        "turn_id": 1,
                        "role": "user",
                        "utterance": f"When is the filing deadline for procedure {index}?",
                        "references": [{"sp_id": "1", "label": "solution"}],
                    },
                    {
                        "turn_id": 2,
                        "role": "agent",
                        "utterance": answer,
                        "references": [{"sp_id": "1", "label": "solution"}],
                    },
                ],
            }
        ]
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("doc2dial_doc.json", json.dumps({"doc_data": documents}))
        archive.writestr("doc2dial_dial_train.json", json.dumps({"dial_data": dialogues}))
        archive.writestr("doc2dial_dial_validation.json", json.dumps({"dial_data": {}}))


def _write_cuad(path: Path, document_count: int = 8) -> None:
    data = []
    for index in range(document_count):
        answer = f"Either party may terminate with {index + 20} days written notice."
        context = f"Termination. {answer} All notices must be delivered in writing."
        data.append(
            {
                "title": f"Contract {index}",
                "paragraphs": [
                    {
                        "context": context,
                        "qas": [
                            {
                                "id": f"contract-{index}__Termination",
                                "question": f"What notice is required to terminate contract {index}?",
                                "is_impossible": False,
                                "answers": [{"text": answer, "answer_start": len("Termination. ")}],
                            }
                        ],
                    }
                ],
            }
        )
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("CUADv1.json", json.dumps({"version": "test", "data": data}))


class PublicOfficeDatasetTest(unittest.TestCase):
    def test_archive_validation_rejects_path_traversal(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "unsafe.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("../escape.json", "{}")

            with self.assertRaisesRegex(ArchiveValidationError, "unsafe archive member"):
                validate_archive(SourceArchive("unsafe", path, None, ("safe.json",)))

    def test_archive_validation_rejects_wrong_hash(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "source.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("safe.json", "{}")

            with self.assertRaisesRegex(ArchiveValidationError, "SHA-256 mismatch"):
                validate_archive(SourceArchive("source", path, "0" * 64, ("safe.json",)))

    def test_build_is_deterministic_balanced_and_document_isolated(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            doc2dial = root / "doc2dial.zip"
            cuad = root / "cuad.zip"
            _write_doc2dial(doc2dial)
            _write_cuad(cuad)

            first = build_public_holdout(
                doc2dial,
                cuad,
                calibration_documents_per_source=2,
                test_documents_per_source=2,
                split_seed="stable-v1",
            )
            second = build_public_holdout(
                doc2dial,
                cuad,
                calibration_documents_per_source=2,
                test_documents_per_source=2,
                split_seed="stable-v1",
            )

            self.assertEqual(first, second)
            self.assertEqual(len(first.calibration_rows), 24)
            self.assertEqual(len(first.test_rows), 24)
            calibration_ids = {row["document_id"] for row in first.calibration_rows}
            test_ids = {row["document_id"] for row in first.test_rows}
            self.assertTrue(calibration_ids.isdisjoint(test_ids))
            for rows in (first.calibration_rows, first.test_rows):
                self.assertEqual(
                    {row["label"] for row in rows if row["task"] == "answerability"},
                    {"SUPPORTED", "PARTIAL", "UNSUPPORTED"},
                )
                self.assertEqual(
                    {row["label"] for row in rows if row["task"] == "groundedness"},
                    {"GROUNDED", "PARTIAL", "UNGROUNDED"},
                )
                self.assertTrue(all(row["distribution"] == "public_office_licensed" for row in rows))
                self.assertTrue(all(row["redaction_status"] == "public_source_reviewed" for row in rows))
                for document_id in {row["document_id"] for row in rows}:
                    answerability = {
                        row["label"]: row
                        for row in rows
                        if row["document_id"] == document_id
                        and row["task"] == "answerability"
                    }
                    self.assertEqual(
                        answerability["SUPPORTED"]["evidence"],
                        answerability["PARTIAL"]["evidence"],
                    )
                    self.assertEqual(
                        answerability["SUPPORTED"]["evidence"],
                        answerability["UNSUPPORTED"]["evidence"],
                    )
                    self.assertNotEqual(
                        answerability["SUPPORTED"]["question"],
                        answerability["PARTIAL"]["question"],
                    )
                    self.assertNotEqual(
                        answerability["SUPPORTED"]["question"],
                        answerability["UNSUPPORTED"]["question"],
                    )

    def test_rejects_request_larger_than_available_document_pool(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            doc2dial = root / "doc2dial.zip"
            cuad = root / "cuad.zip"
            _write_doc2dial(doc2dial, document_count=2)
            _write_cuad(cuad, document_count=2)

            with self.assertRaisesRegex(ValueError, "not enough eligible"):
                build_public_holdout(
                    doc2dial,
                    cuad,
                    calibration_documents_per_source=2,
                    test_documents_per_source=2,
                )


if __name__ == "__main__":
    unittest.main()
