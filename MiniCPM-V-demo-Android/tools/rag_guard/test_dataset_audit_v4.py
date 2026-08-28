import unittest
import json
import tempfile
from pathlib import Path

from tools.rag_guard import deduplicate_and_split_v4
from tools.rag_guard.deduplicate_and_split_v4 import main as split_main, split_rows
from tools.rag_guard import audit_dataset_v4
from tools.rag_guard.audit_dataset_v4 import _read_jsonl_files, audit_rows, validate_registry
from tools.rag_guard.test_dataset_balance_v4 import balanced_rows
from tools.rag_guard.test_dataset_schema_v2 import groundedness_row


class DatasetAuditV4Test(unittest.TestCase):
    def test_frozen_test_is_preserved_and_related_new_rows_are_excluded(self) -> None:
        self.assertTrue(hasattr(deduplicate_and_split_v4, "split_rows_with_frozen_test"))
        frozen = groundedness_row(
            id="frozen-row",
            split="test",
            mutation_family_id="frozen-family",
            near_duplicate_cluster_id="frozen-near",
            question="住宿报销上限是多少？",
        )
        same_id_candidate = groundedness_row(
            id="frozen-row",
            split="train",
            mutation_family_id="frozen-family",
            near_duplicate_cluster_id="candidate-near",
        )
        sibling = groundedness_row(
            id="new-sibling",
            mutation_family_id="frozen-family",
            document_id="new-doc",
        )
        near_duplicate = groundedness_row(
            id="near-duplicate",
            mutation_family_id="other-family",
            document_id="other-doc",
            question="住宿报销上限是多少？",
        )
        independent = groundedness_row(
            id="independent",
            mutation_family_id="independent-family",
            document_id="independent-doc",
            question="审批期限是几个工作日？",
            answer="审批期限为三个工作日。",
        )

        result = deduplicate_and_split_v4.split_rows_with_frozen_test(
            [same_id_candidate, sibling, near_duplicate, independent],
            [frozen],
            seed="stable-frozen-test",
        )

        observed = {row["id"]: row for row in result}
        self.assertEqual(frozen, observed["frozen-row"])
        self.assertNotIn("new-sibling", observed)
        self.assertNotIn("near-duplicate", observed)
        self.assertIn(observed["independent"]["split"], {"train", "calibration"})
        self.assertNotIn("test", {row["split"] for row in result if row["id"] != "frozen-row"})

    def test_release_audit_enforces_groundedness_slice_balance(self) -> None:
        self.assertTrue(hasattr(audit_dataset_v4, "audit_release_balance"))
        rows = balanced_rows()
        for row in rows:
            if row["label"] == "CONTRADICTED":
                row["hard_negative_type"] = "NEGATION_FLIP"
        with self.assertRaisesRegex(ValueError, "negation share"):
            audit_dataset_v4.audit_release_balance(rows)

    def test_mutation_family_and_near_duplicates_stay_in_one_split(self) -> None:
        first = groundedness_row(
            id="row-1",
            mutation_family_id="family-shared",
            question="住宿报销上限是多少？",
        )
        second = groundedness_row(
            id="row-2",
            mutation_family_id="family-shared",
            question="住宿报销上限具体是多少？",
        )
        third = groundedness_row(
            id="row-3",
            mutation_family_id="family-other",
            document_id="doc-3",
            question="住宿报销上限是多少？",
        )

        split = split_rows([first, second, third], seed="fixed-v4")

        observed = {row["id"]: row["split"] for row in split}
        self.assertEqual(observed["row-1"], observed["row-2"])
        self.assertEqual(observed["row-1"], observed["row-3"])

    def test_audit_rejects_a_family_crossing_splits(self) -> None:
        first = groundedness_row(id="row-1", document_id="doc-1", split="train")
        second = groundedness_row(id="row-2", document_id="doc-2", split="test")
        with self.assertRaisesRegex(ValueError, "mutation_family_id leakage"):
            audit_rows([first, second])

    def test_audit_rejects_sensitive_phone_number(self) -> None:
        with self.assertRaisesRegex(ValueError, "sensitive data"):
            audit_rows([groundedness_row(question="请联系13812345678")])

    def test_audit_does_not_treat_generated_identifiers_as_phone_content(self) -> None:
        row = groundedness_row(
            id="v4-groundedness-13812345678abcdef",
            mutation_family_id="family-13812345678",
            document_id="hover:13812345678",
        )
        report = audit_rows([row])
        self.assertTrue(report["passed"])

    def test_registry_rejects_review_required_source_selected_for_training(self) -> None:
        registry = {
            "sources": [
                {"id": "unsafe", "license_status": "review_required", "enabled": True}
            ]
        }
        with self.assertRaisesRegex(ValueError, "license"):
            validate_registry(registry)

    def test_split_cli_accepts_input_directory_and_writes_task_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            input_dir = root / "generated"
            output_dir = root / "splits"
            input_dir.mkdir()
            rows = [groundedness_row(id=f"row-{index}", document_id=f"doc-{index}") for index in range(30)]
            (input_dir / "groundedness.jsonl").write_text(
                "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
                encoding="utf-8",
            )

            self.assertEqual(0, split_main(["--input-dir", str(input_dir), "--output-dir", str(output_dir)]))

            for split in ("train", "calibration", "test"):
                self.assertTrue((output_dir / f"groundedness_{split}.jsonl").exists())

    def test_audit_reader_can_select_only_all_split_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            row = groundedness_row()
            (root / "all_train.jsonl").write_text(json.dumps(row) + "\n", encoding="utf-8")
            (root / "groundedness_train.jsonl").write_text(json.dumps(row) + "\n", encoding="utf-8")
            selected = _read_jsonl_files(root, pattern="all_*.jsonl")
            self.assertEqual(1, len(selected))


if __name__ == "__main__":
    unittest.main()
