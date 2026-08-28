import unittest

from tools.rag_guard.score_office_holdout import score_rows


MODEL_SHA = "45d42125648c169a19697ce8b64f6883e63c2d8a45fd666c73bf163a3c59e097"
TOKENIZER_SHA = "3396f311d68a8ee4351c0949ab2626543334c5566d7f8ea17b026952ac14d0fe"


def office_row(*, task: str = "answerability") -> dict[str, object]:
    return {
        "id": f"office-{task}-1",
        "task": task,
        "label": "SUPPORTED" if task == "answerability" else "GROUNDED",
        "document_id": f"office-{task}-doc-1",
        "distribution": "real_office_redacted",
        "redaction_status": "reviewed",
        "question": "报销上限是多少？",
        "evidence": "差旅报销上限为八百元。",
        "answer": "报销上限为八百元。" if task == "groundedness" else "",
    }


class ScoreOfficeHoldoutTest(unittest.TestCase):
    def test_scores_explicit_licensed_public_distribution_without_weakening_default(self) -> None:
        row = office_row()
        row["distribution"] = "public_office_licensed"
        row["redaction_status"] = "public_source_reviewed"

        scored = score_rows(
            [row],
            tokenize=lambda _: [101, 102],
            infer=lambda *_: [1.0, 0.0, -1.0],
            model_sha256=MODEL_SHA,
            tokenizer_sha256=TOKENIZER_SHA,
            expected_distribution="public_office_licensed",
        )

        self.assertEqual(scored[0]["distribution"], "public_office_licensed")
        with self.assertRaisesRegex(ValueError, "unapproved office distribution"):
            score_rows(
                [row],
                tokenize=lambda _: [101, 102],
                infer=lambda *_: [1.0, 0.0, -1.0],
                model_sha256=MODEL_SHA,
                tokenizer_sha256=TOKENIZER_SHA,
            )

    def test_scores_with_android_equivalent_end_token_preserving_truncation(self) -> None:
        captured: list[tuple[list[int], list[int], int]] = []

        scored = score_rows(
            [{**office_row(), "private_note": "must not be copied"}],
            tokenize=lambda _: [101, 11, 12, 13, 102],
            infer=lambda ids, mask, task_id: captured.append((ids, mask, task_id)) or [4.0, 1.0, -1.0],
            model_sha256=MODEL_SHA,
            tokenizer_sha256=TOKENIZER_SHA,
            max_tokens=4,
        )

        self.assertEqual(captured, [([101, 11, 12, 102], [1, 1, 1, 1], 0)])
        self.assertEqual(scored[0]["model_sha256"], MODEL_SHA)
        self.assertEqual(scored[0]["tokenizer_sha256"], TOKENIZER_SHA)
        self.assertAlmostEqual(sum(scored[0]["probabilities"]), 1.0)
        self.assertNotIn("private_note", scored[0])

    def test_routes_groundedness_to_second_head_and_includes_answer(self) -> None:
        texts: list[str] = []
        tasks: list[int] = []

        score_rows(
            [office_row(task="groundedness")],
            tokenize=lambda text: texts.append(text) or [101, 102],
            infer=lambda _ids, _mask, task_id: tasks.append(task_id) or [1.0, 0.0, -1.0],
            model_sha256=MODEL_SHA,
            tokenizer_sha256=TOKENIZER_SHA,
        )

        self.assertEqual(tasks, [1])
        self.assertIn("answer: 报销上限为八百元。", texts[0])

    def test_rejects_unreviewed_or_sensitive_office_rows_before_inference(self) -> None:
        for mutation in (
            {"redaction_status": "pending"},
            {"question": "联系 13812345678"},
        ):
            row = office_row()
            row.update(mutation)
            with self.subTest(mutation=mutation):
                with self.assertRaises(ValueError):
                    score_rows(
                        [row],
                        tokenize=lambda _: [101, 102],
                        infer=lambda *_: [1.0, 0.0, -1.0],
                        model_sha256=MODEL_SHA,
                        tokenizer_sha256=TOKENIZER_SHA,
                    )


if __name__ == "__main__":
    unittest.main()
