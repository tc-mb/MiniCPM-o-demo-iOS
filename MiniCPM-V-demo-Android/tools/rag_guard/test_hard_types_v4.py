import unittest


class HardTypesV4Test(unittest.TestCase):
    def test_release_contradiction_types_cover_every_generated_family(self) -> None:
        from tools.rag_guard.hard_types_v4 import RELEASE_CONTRADICTION_TYPES

        self.assertEqual(
            {
                "CONTRACT_CONTRADICTION",
                "MULTI_HOP_CONTRADICTION",
                "NEGATION_FLIP",
                "SCOPE_FLIP",
                "WRONG_AMOUNT",
                "WRONG_DATE",
                "WRONG_ENTITY",
                "WRONG_UNIT",
            },
            set(RELEASE_CONTRADICTION_TYPES),
        )

    def test_pair_groups_rotate_all_contradicted_siblings_across_epochs(self) -> None:
        from tools.rag_guard.hard_types_v4 import build_pair_groups, select_pair_members

        groups = build_pair_groups(
            pair_ids=[7, 7, 7, 9, 9],
            pair_roles=[1, -1, -1, 1, -1],
        )

        self.assertEqual(((0, (1, 2)), (3, (4,))), groups)
        self.assertEqual(((0, 1), (3, 4)), select_pair_members(groups, epoch=0))
        self.assertEqual(((0, 2), (3, 4)), select_pair_members(groups, epoch=1))
        self.assertEqual(((0, 1), (3, 4)), select_pair_members(groups, epoch=2))

    def test_pair_groups_reject_duplicate_grounded_siblings(self) -> None:
        from tools.rag_guard.hard_types_v4 import build_pair_groups

        with self.assertRaisesRegex(ValueError, "exactly one grounded sibling"):
            build_pair_groups(pair_ids=[4, 4, 4], pair_roles=[1, 1, -1])


if __name__ == "__main__":
    unittest.main()
