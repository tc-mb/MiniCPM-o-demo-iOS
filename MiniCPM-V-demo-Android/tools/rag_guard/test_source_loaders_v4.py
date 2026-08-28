import json
import sqlite3
import tempfile
import unittest
import unicodedata
import zipfile
from pathlib import Path

from tools.rag_guard.source_loaders_v4 import (
    HoVerEvidenceStore,
    load_contract_nli_zip,
    load_hover_json,
)


class SourceLoadersV4Test(unittest.TestCase):
    def test_contract_loader_preserves_choice_and_evidence_spans(self) -> None:
        payload = {
            "labels": {
                "nda-1": {"hypothesis": "The agreement renews automatically."},
                "nda-2": {"hypothesis": "The agreement permits assignment."},
            },
            "documents": [
                {
                    "id": 7,
                    "text": "The agreement does not renew automatically. Assignment is not discussed.",
                    "spans": [[0, 43], [44, 73]],
                    "annotation_sets": [
                        {
                            "annotations": {
                                "nda-1": {"choice": "Contradiction", "spans": [0]},
                                "nda-2": {"choice": "NotMentioned", "spans": []},
                            }
                        }
                    ],
                }
            ],
        }
        with tempfile.TemporaryDirectory() as temporary:
            archive_path = Path(temporary) / "contract-nli.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                for split in ("train", "dev", "test"):
                    archive.writestr(f"contract-nli/{split}.json", json.dumps(payload))

            records = load_contract_nli_zip(archive_path)

        train = [record for record in records if record.split == "train"]
        self.assertEqual(["Contradiction", "NotMentioned"], [record.choice for record in train])
        self.assertEqual("The agreement does not renew automatically.", train[0].evidence)
        self.assertIn("Assignment is not discussed", train[1].evidence)

    def test_contract_loader_rejects_path_traversal(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            archive_path = Path(temporary) / "unsafe.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr("../train.json", "{}")
            with self.assertRaisesRegex(ValueError, "unsafe archive member"):
                load_contract_nli_zip(archive_path)

    def test_hover_store_matches_unicode_normalized_titles(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "wiki.db"
            connection = sqlite3.connect(database)
            connection.execute("CREATE TABLE documents (id PRIMARY KEY, text)")
            connection.execute(
                "INSERT INTO documents(id, text) VALUES (?, ?)",
                (unicodedata.normalize("NFD", "Aarón Galindo"), "Aarón Galindo is a footballer."),
            )
            connection.commit()
            connection.close()

            with HoVerEvidenceStore(database) as store:
                self.assertEqual(
                    "Aarón Galindo is a footballer.",
                    store.get("Aarón Galindo"),
                )

    def test_hover_loader_validates_unique_uids_and_labels(self) -> None:
        rows = [
            {
                "uid": "uid-1",
                "claim": "A supported claim.",
                "supporting_facts": [["Document", 0]],
                "label": "SUPPORTED",
                "num_hops": 2,
                "hpqa_id": "hpqa-1",
            }
        ]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "hover.json"
            path.write_text(json.dumps(rows), encoding="utf-8")
            records = load_hover_json(path, split="train")

        self.assertEqual("uid-1", records[0].uid)
        self.assertEqual("SUPPORTED", records[0].label)


if __name__ == "__main__":
    unittest.main()
