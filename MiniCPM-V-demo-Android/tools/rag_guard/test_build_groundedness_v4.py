import unittest

from tools.rag_guard.build_groundedness_v4 import (
    GroundednessSourceRecord,
    build_groundedness_family,
    contract_nli_groundedness_label,
)
from tools.rag_guard.claim_labeling import aggregate_claim_support
from tools.rag_guard.mutations import amount_date
from tools.rag_guard.mutations.amount_date import replace_exact_fact
from tools.rag_guard.mutations.citation_injection import replace_citation
from tools.rag_guard.mutations import entity_scope
from tools.rag_guard.mutations.entity_scope import replace_exact_entity


class BuildGroundednessV4Test(unittest.TestCase):
    def test_numeric_mutation_changes_one_bounded_number(self) -> None:
        self.assertTrue(hasattr(amount_date, "mutate_single_number"))
        self.assertEqual("期限为11天。", amount_date.mutate_single_number("期限为10天。"))
        self.assertIsNone(amount_date.mutate_single_number("区间为10至20天。"))

    def test_unit_mutation_changes_one_known_unit(self) -> None:
        from importlib import import_module, util

        spec = util.find_spec("tools.rag_guard.mutations.unit_scope")
        self.assertIsNotNone(spec, "unit_scope mutation module must exist")
        module = import_module("tools.rag_guard.mutations.unit_scope")
        self.assertEqual("The deadline is 10 months.", module.mutate_single_unit("The deadline is 10 days."))
        self.assertEqual("期限为10个月。", module.mutate_single_unit("期限为10天。"))

    def test_scope_mutation_flips_one_explicit_modal(self) -> None:
        self.assertTrue(hasattr(entity_scope, "mutate_single_scope"))
        self.assertEqual(
            "Assignment is prohibited.",
            entity_scope.mutate_single_scope("Assignment is permitted."),
        )
        self.assertEqual("员工不得申请。", entity_scope.mutate_single_scope("员工可以申请。"))
        self.assertIsNone(entity_scope.mutate_single_scope("Employees may apply and may appeal."))

    def test_claim_aggregation_uses_contradiction_as_highest_severity(self) -> None:
        cases = [
            (["entailed", "entailed"], "GROUNDED"),
            (["entailed", "missing"], "PARTIAL"),
            (["missing", "missing"], "UNSUPPORTED"),
            (["entailed", "contradicted"], "CONTRADICTED"),
            (["missing", "contradicted"], "CONTRADICTED"),
        ]
        for claims, expected in cases:
            with self.subTest(claims=claims):
                self.assertEqual(expected, aggregate_claim_support(claims))

    def test_family_generates_four_labels_in_one_mutation_family(self) -> None:
        source = GroundednessSourceRecord(
            source_dataset="fixture",
            source_version="1",
            source_license="CC-BY-4.0",
            source_record_id="record-1",
            document_id="doc-1",
            language="zh",
            domain="travel",
            question="住宿上限是多少？",
            evidence="差旅制度规定住宿上限为800元。",
            grounded_answer="住宿上限为800元。",
        )
        rows = build_groundedness_family(
            source,
            missing_claim="审批期限为三个工作日。",
            unsupported_answer="该制度同时规定了年终奖比例。",
            contradicted_answer="住宿上限为1500元。",
            contradiction_type="WRONG_AMOUNT",
        )

        self.assertEqual(
            ["GROUNDED", "PARTIAL", "UNSUPPORTED", "CONTRADICTED"],
            [row["label"] for row in rows],
        )
        self.assertEqual(1, len({row["mutation_family_id"] for row in rows}))
        self.assertEqual(
            "contradicted",
            rows[-1]["atomic_claims"][0]["support"],
        )

    def test_exact_fact_replacement_changes_only_requested_occurrence(self) -> None:
        text = "住宿上限为800元，联系电话13812345678，引用[S1]。"
        mutated = replace_exact_fact(text, original="800元", replacement="1500元")
        self.assertEqual("住宿上限为1500元，联系电话13812345678，引用[S1]。", mutated)

    def test_contract_nli_mapping_keeps_not_mentioned_separate_from_contradiction(self) -> None:
        self.assertEqual("GROUNDED", contract_nli_groundedness_label("Entailment"))
        self.assertEqual("UNSUPPORTED", contract_nli_groundedness_label("NotMentioned"))
        self.assertEqual("CONTRADICTED", contract_nli_groundedness_label("Contradiction"))

    def test_entity_and_citation_mutations_are_literal_and_bounded(self) -> None:
        self.assertEqual(
            "财务部负责审批。",
            replace_exact_entity("行政部负责审批。", "行政部", "财务部"),
        )
        self.assertEqual(
            "住宿上限为800元。[S2]",
            replace_citation("住宿上限为800元。[S1]", "S1", "S2"),
        )


if __name__ == "__main__":
    unittest.main()
