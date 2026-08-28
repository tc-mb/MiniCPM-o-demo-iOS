import importlib
import importlib.util
import unittest


def fixture_rows() -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    hard_types = ["NEGATION_FLIP", "WRONG_ENTITY", "WRONG_AMOUNT", "SCOPE_FLIP"]
    for family_index in range(8):
        family = f"family-{family_index}"
        hard_type = hard_types[family_index % len(hard_types)]
        for label, suffix in (
            ("GROUNDED", "g"),
            ("PARTIAL", "p"),
            ("UNSUPPORTED", "u"),
            ("CONTRADICTED", "c"),
        ):
            rows.append(
                {
                    "id": f"{family}-{suffix}",
                    "label": label,
                    "mutation_family_id": family,
                    "hard_negative_type": hard_type if label == "CONTRADICTED" else "NONE",
                    "language": "zh" if family_index % 2 == 0 else "en",
                }
            )
    return rows


class SelectBalancedCorpusV4Test(unittest.TestCase):
    def setUp(self) -> None:
        spec = importlib.util.find_spec("tools.rag_guard.select_balanced_corpus_v4")
        self.assertIsNotNone(spec, "balanced corpus selector module must exist")
        self.module = importlib.import_module("tools.rag_guard.select_balanced_corpus_v4")
        self.assertTrue(hasattr(self.module, "select_balanced_groundedness"))

    def select(self, rows: list[dict[str, object]]) -> list[dict[str, object]]:
        return self.module.select_balanced_groundedness(
            rows,
            label_quotas={"GROUNDED": 4, "PARTIAL": 2, "UNSUPPORTED": 2, "CONTRADICTED": 4},
            contradiction_quotas={
                "NEGATION_FLIP": 1,
                "WRONG_ENTITY": 1,
                "WRONG_AMOUNT": 1,
                "SCOPE_FLIP": 1,
            },
            seed="stable-v4",
        )

    def test_selector_is_deterministic_and_meets_exact_quotas(self) -> None:
        rows = fixture_rows()
        first = self.select(rows)
        second = self.select(list(reversed(rows)))
        self.assertEqual([row["id"] for row in first], [row["id"] for row in second])
        labels = {label: sum(row["label"] == label for row in first) for label in ("GROUNDED", "PARTIAL", "UNSUPPORTED", "CONTRADICTED")}
        self.assertEqual({"GROUNDED": 4, "PARTIAL": 2, "UNSUPPORTED": 2, "CONTRADICTED": 4}, labels)
        hard_types = {
            hard_type: sum(row["label"] == "CONTRADICTED" and row["hard_negative_type"] == hard_type for row in first)
            for hard_type in ("NEGATION_FLIP", "WRONG_ENTITY", "WRONG_AMOUNT", "SCOPE_FLIP")
        }
        self.assertEqual({"NEGATION_FLIP": 1, "WRONG_ENTITY": 1, "WRONG_AMOUNT": 1, "SCOPE_FLIP": 1}, hard_types)

    def test_every_selected_contradiction_keeps_a_grounded_sibling(self) -> None:
        selected = self.select(fixture_rows())
        grounded_families = {
            row["mutation_family_id"] for row in selected if row["label"] == "GROUNDED"
        }
        for row in selected:
            if row["label"] == "CONTRADICTED":
                self.assertIn(row["mutation_family_id"], grounded_families)

    def test_selector_fails_closed_when_a_hard_slice_is_short(self) -> None:
        rows = [row for row in fixture_rows() if row["hard_negative_type"] != "SCOPE_FLIP"]
        with self.assertRaisesRegex(ValueError, "SCOPE_FLIP"):
            self.select(rows)

    def test_selector_can_freeze_language_inside_each_hard_slice(self) -> None:
        try:
            selected = self.module.select_balanced_groundedness(
                fixture_rows(),
                label_quotas={"GROUNDED": 4, "PARTIAL": 2, "UNSUPPORTED": 2, "CONTRADICTED": 4},
                contradiction_quotas={
                    ("NEGATION_FLIP", "zh"): 1,
                    ("WRONG_ENTITY", "en"): 1,
                    ("WRONG_AMOUNT", "zh"): 1,
                    ("SCOPE_FLIP", "en"): 1,
                },
                seed="stable-v4-language",
            )
        except ValueError as error:
            self.fail(f"language-sliced contradiction quotas must be supported: {error}")
        observed = {
            (row["hard_negative_type"], row["language"])
            for row in selected
            if row["label"] == "CONTRADICTED"
        }
        self.assertEqual(
            {
                ("NEGATION_FLIP", "zh"),
                ("WRONG_ENTITY", "en"),
                ("WRONG_AMOUNT", "zh"),
                ("SCOPE_FLIP", "en"),
            },
            observed,
        )


if __name__ == "__main__":
    unittest.main()
