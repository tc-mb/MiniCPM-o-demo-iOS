import unittest


class FakeOffsetTokenizer:
    @staticmethod
    def _tokens(text: str) -> tuple[list[int], list[tuple[int, int]]]:
        offsets: list[tuple[int, int]] = []
        cursor = 0
        for token in text.split():
            start = text.index(token, cursor)
            end = start + len(token)
            offsets.append((start, end))
            cursor = end
        return list(range(1, len(offsets) + 1)), offsets

    def __call__(self, first: str, second: str | None = None, **options: object) -> dict[str, object]:
        first_ids, first_offsets = self._tokens(first)
        if second is None:
            result: dict[str, object] = {"input_ids": first_ids}
            if options.get("return_offsets_mapping"):
                result["offset_mapping"] = first_offsets
            return result
        second_ids, _second_offsets = self._tokens(second)
        return {"input_ids": [0, *first_ids, 0, *second_ids, 0]}


class QaRepairsV42Test(unittest.TestCase):
    def test_classifies_english_and_chinese_temporal_answers(self) -> None:
        from tools.rag_guard.qa_repairs_v4_2 import classify_numeric_hard_type

        self.assertEqual("WRONG_DATE", classify_numeric_hard_type("15 July 2007", "en"))
        self.assertEqual("WRONG_DATE", classify_numeric_hard_type("2013", "en"))
        self.assertEqual("WRONG_DATE", classify_numeric_hard_type("10 days", "en"))
        self.assertEqual("WRONG_DATE", classify_numeric_hard_type("2012年3月", "zh"))
        self.assertEqual("WRONG_AMOUNT", classify_numeric_hard_type("24", "en"))
        self.assertEqual("WRONG_AMOUNT", classify_numeric_hard_type("八百元", "zh"))

    def test_selects_only_a_distinct_type_compatible_distractor(self) -> None:
        from tools.rag_guard.qa_repairs_v4_2 import choose_type_matched_distractor

        self.assertEqual("Paris", choose_type_matched_distractor("London", ["24", "London", "Paris"]))
        self.assertEqual("2014", choose_type_matched_distractor("2013", ["Paris", "2013", "2014"]))
        self.assertIsNone(choose_type_matched_distractor("London", ["24", "2013"]))

    def test_rejects_invalid_language_and_oversized_values(self) -> None:
        from tools.rag_guard.qa_repairs_v4_2 import (
            classify_numeric_hard_type,
            choose_type_matched_distractor,
        )

        with self.assertRaisesRegex(ValueError, "language"):
            classify_numeric_hard_type("2013", "fr")
        with self.assertRaisesRegex(ValueError, "answer"):
            choose_type_matched_distractor("x" * 100_001, ["Paris"])

    def test_builds_a_bounded_window_containing_all_required_spans(self) -> None:
        from tools.rag_guard.qa_repairs_v4_2 import build_visible_evidence_window

        context = " ".join([*[f"prefix{i}" for i in range(50)], "true-answer", "distractor", *[f"suffix{i}" for i in range(50)]])
        protected = "query: What is correct? answer: distractor"
        tokenizer = FakeOffsetTokenizer()

        window = build_visible_evidence_window(
            context,
            required_texts=("true-answer", "distractor"),
            protected_text=protected,
            tokenizer=tokenizer,
            max_length=32,
            evidence_prefix="evidence [S1]: ",
        )

        self.assertIsNotNone(window)
        assert window is not None
        self.assertIn("true-answer", window)
        self.assertIn("distractor", window)
        encoded = tokenizer(protected, "evidence [S1]: " + window, add_special_tokens=True)
        self.assertLessEqual(len(encoded["input_ids"]), 32)

    def test_rejects_required_spans_that_cannot_share_the_token_budget(self) -> None:
        from tools.rag_guard.qa_repairs_v4_2 import build_visible_evidence_window

        context = "first " + " ".join(f"middle{i}" for i in range(50)) + " last"

        self.assertIsNone(
            build_visible_evidence_window(
                context,
                required_texts=("first", "last"),
                protected_text="query: q answer: a",
                tokenizer=FakeOffsetTokenizer(),
                max_length=32,
            )
        )


if __name__ == "__main__":
    unittest.main()
