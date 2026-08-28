import importlib
import importlib.util
import unittest


def balanced_rows() -> list[dict[str, object]]:
    hard_types = (
        ["NEGATION_FLIP"] * 6
        + ["WRONG_ENTITY"] * 4
        + ["WRONG_AMOUNT"] * 4
        + ["SCOPE_FLIP"] * 3
        + ["MULTI_HOP_CONTRADICTION"] * 3
    )
    rows: list[dict[str, object]] = []
    for index, hard_type in enumerate(hard_types):
        family = f"family-{index}"
        language = "zh" if index < 5 else "en"
        source = "source-a" if index % 2 == 0 else "source-b"
        rows.extend(
            [
                {
                    "task": "groundedness",
                    "label": "GROUNDED",
                    "hard_negative_type": "NONE",
                    "source_dataset": source,
                    "language": language,
                    "mutation_family_id": family,
                },
                {
                    "task": "groundedness",
                    "label": "CONTRADICTED",
                    "hard_negative_type": hard_type,
                    "source_dataset": source,
                    "language": language,
                    "mutation_family_id": family,
                },
            ]
        )
    return rows


class DatasetBalanceV4Test(unittest.TestCase):
    def setUp(self) -> None:
        spec = importlib.util.find_spec("tools.rag_guard.dataset_balance_v4")
        self.assertIsNotNone(spec, "dataset_balance_v4 module must exist")
        self.module = importlib.import_module("tools.rag_guard.dataset_balance_v4")
        self.assertTrue(hasattr(self.module, "summarize_groundedness"))
        self.assertTrue(hasattr(self.module, "validate_groundedness_balance"))
        self.assertTrue(hasattr(self.module, "RELEASE_POLICY"))

    def validate(self, rows: list[dict[str, object]]) -> dict[str, object]:
        summary = self.module.summarize_groundedness(rows)
        return self.module.validate_groundedness_balance(summary, self.module.RELEASE_POLICY)

    def test_balanced_contrast_families_pass(self) -> None:
        report = self.validate(balanced_rows())
        self.assertEqual(20, report["contradicted_rows"])
        self.assertAlmostEqual(0.30, report["negation_share"])
        self.assertAlmostEqual(0.50, report["max_source_share"])
        self.assertAlmostEqual(0.25, report["zh_share"])
        self.assertAlmostEqual(1.0, report["paired_contradicted_share"])

    def test_release_gate_rejects_excessive_negation_share(self) -> None:
        rows = balanced_rows()
        for row in rows:
            if row["label"] == "CONTRADICTED":
                row["hard_negative_type"] = "NEGATION_FLIP"
        with self.assertRaisesRegex(ValueError, "negation share"):
            self.validate(rows)

    def test_release_gate_rejects_single_source_dominance(self) -> None:
        rows = balanced_rows()
        for row in rows:
            row["source_dataset"] = "dominant-source"
        with self.assertRaisesRegex(ValueError, "source share"):
            self.validate(rows)

    def test_release_gate_rejects_low_chinese_coverage(self) -> None:
        rows = balanced_rows()
        for row in rows:
            row["language"] = "en"
        with self.assertRaisesRegex(ValueError, "Chinese share"):
            self.validate(rows)

    def test_release_gate_rejects_unpaired_contradictions(self) -> None:
        rows = balanced_rows()
        for row in rows:
            if row["label"] == "GROUNDED" and int(str(row["mutation_family_id"]).split("-")[-1]) >= 10:
                row["mutation_family_id"] = "unrelated-" + str(row["mutation_family_id"])
        with self.assertRaisesRegex(ValueError, "paired contradiction share"):
            self.validate(rows)


if __name__ == "__main__":
    unittest.main()
