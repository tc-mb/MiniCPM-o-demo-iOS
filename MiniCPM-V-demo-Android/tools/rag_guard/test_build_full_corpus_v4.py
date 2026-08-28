import json
import sqlite3
import tempfile
import unittest
import zipfile
from collections import Counter
from pathlib import Path

from tools.rag_guard import build_full_corpus_v4
from tools.rag_guard.build_full_corpus_v4 import (
    _clean,
    build_contract_corpus,
    build_all_sources,
    build_hover_corpus,
    build_qa_corpus,
    select_by_label_language_quotas,
    select_by_label_quotas,
    write_jsonl_atomic,
)
from tools.rag_guard.source_loaders_v4 import ContractNliRecord, HoVerRecord, HoVerEvidenceStore


RAW_HASH = "a" * 64
COMMIT = "b" * 40


class WhitespaceOffsetTokenizer:
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


class BuildFullCorpusV4Test(unittest.TestCase):
    def test_release_contradiction_quotas_freeze_language_and_negation_limits(self) -> None:
        self.assertTrue(hasattr(build_full_corpus_v4, "GROUNDEDNESS_CONTRADICTION_QUOTAS"))
        quotas = build_full_corpus_v4.GROUNDEDNESS_CONTRADICTION_QUOTAS
        self.assertEqual(37_500, sum(quotas.values()))
        self.assertEqual(1_170, sum(value for (hard_type, language), value in quotas.items() if language == "zh"))
        self.assertEqual(
            10_000,
            sum(value for (hard_type, _language), value in quotas.items() if hard_type == "NEGATION_FLIP"),
        )
        self.assertEqual(700, quotas[("CONTRACT_CONTRADICTION", "en")])
        self.assertEqual(490, quotas[("WRONG_UNIT", "en")])
        self.assertEqual(3_930, quotas[("WRONG_DATE", "en")])
        self.assertEqual(4_500, quotas[("WRONG_AMOUNT", "en")])
        answerability = build_full_corpus_v4.ANSWERABILITY_LANGUAGE_QUOTAS
        self.assertEqual(600, answerability[("SUPPORTED", "zh")])
        self.assertEqual(600, answerability[("PARTIAL", "zh")])
        self.assertEqual(600, answerability[("UNSUPPORTED", "zh")])

    def test_clean_redacts_email_before_sentence_period(self) -> None:
        self.assertEqual("Contact [EMAIL].", _clean("Contact user.name@example.com."))

    def test_all_source_builder_uses_each_required_dataset(self) -> None:
        qa_payload = {
            "version": "v2.0",
            "data": [{"title": "Policy", "paragraphs": [{"context": "The term is one year.", "qas": [
                {"id": "a", "question": "What is the term?", "answers": [{"text": "one year", "answer_start": 12}]},
                {"id": "u", "question": "What is the fee?", "answers": [], "is_impossible": True},
            ]}]}],
        }
        contract_payload = {
            "labels": {"h1": {"hypothesis": "The term is one year."}, "h2": {"hypothesis": "Assignment is permitted."}},
            "documents": [{"id": 1, "text": "The term is one year.", "spans": [[0, 21]], "annotation_sets": [{"annotations": {
                "h1": {"choice": "Entailment", "spans": [0]}, "h2": {"choice": "NotMentioned", "spans": []},
            }}]}],
        }
        hover_rows = [
            {"uid": "p", "claim": "The term is one year.", "supporting_facts": [["Policy", 0]], "label": "SUPPORTED", "num_hops": 2, "hpqa_id": "hpqa"},
            {"uid": "n", "claim": "The term is two years.", "supporting_facts": [["Policy", 0]], "label": "NOT_SUPPORTED", "num_hops": 2, "hpqa_id": "hpqa"},
        ]
        with tempfile.TemporaryDirectory() as temporary:
            raw = Path(temporary)
            for directory in ("squad_2", "cmrc_2018", "contract_nli", "hover"):
                (raw / directory).mkdir()
            for name in ("train-v2.0.json", "dev-v2.0.json"):
                (raw / "squad_2" / name).write_text(json.dumps(qa_payload), encoding="utf-8")
            for name in ("cmrc2018_train.json", "cmrc2018_dev.json"):
                (raw / "cmrc_2018" / name).write_text(json.dumps(qa_payload), encoding="utf-8")
            with zipfile.ZipFile(raw / "contract_nli" / "contract-nli.zip", "w") as archive:
                for split in ("train", "dev", "test"):
                    archive.writestr(f"contract-nli/{split}.json", json.dumps(contract_payload))
            for name in ("hover_train_release_v1.1.json", "hover_dev_release_v1.1.json"):
                (raw / "hover" / name).write_text(json.dumps(hover_rows), encoding="utf-8")
            connection = sqlite3.connect(raw / "hover" / "wiki_wo_links.db")
            connection.execute("CREATE TABLE documents (id PRIMARY KEY, text)")
            connection.execute("INSERT INTO documents(id, text) VALUES (?, ?)", ("Policy", "The term is one year."))
            connection.commit()
            connection.close()

            generated = build_all_sources(raw, generator_commit=COMMIT, limit_per_source=10)

        expected = {"SQuAD 2.0", "CMRC 2018", "ContractNLI", "HoVer"}
        self.assertEqual(expected, {row["source_dataset"] for row in generated.answerability})
        self.assertEqual(expected, {row["source_dataset"] for row in generated.groundedness})

    def test_contract_choices_create_three_ground_labels_and_partial_pair(self) -> None:
        records = [
            ContractNliRecord("train", "doc-1", "h1", "The term is one year.", "Entailment", "The term is one year.", "The term is one year."),
            ContractNliRecord("train", "doc-1", "h2", "The term is two years.", "Contradiction", "The term is one year.", "The term is one year."),
            ContractNliRecord("train", "doc-1", "h3", "Assignment is permitted.", "NotMentioned", "The term is one year.", "The term is one year."),
        ]

        generated = build_contract_corpus(records, raw_sha256=RAW_HASH, generator_commit=COMMIT)

        self.assertEqual(
            {"GROUNDED", "PARTIAL", "UNSUPPORTED", "CONTRADICTED"},
            {row["label"] for row in generated.groundedness},
        )
        self.assertEqual(
            {"SUPPORTED", "PARTIAL", "UNSUPPORTED"},
            {row["label"] for row in generated.answerability},
        )
        grounded_families = {
            row["mutation_family_id"]
            for row in generated.groundedness
            if row["label"] == "GROUNDED"
        }
        for row in generated.groundedness:
            if row["label"] == "CONTRADICTED":
                self.assertIn(row["mutation_family_id"], grounded_families)

    def test_entailed_contract_scope_generates_contradicted_sibling(self) -> None:
        generated = build_contract_corpus(
            [
                ContractNliRecord(
                    "train",
                    "doc-1",
                    "h1",
                    "Assignment is permitted.",
                    "Entailment",
                    "Assignment is permitted.",
                    "Assignment is permitted.",
                )
            ],
            raw_sha256=RAW_HASH,
            generator_commit=COMMIT,
        )
        scope_rows = [
            row for row in generated.groundedness if row["hard_negative_type"] == "SCOPE_FLIP"
        ]
        self.assertEqual(1, len(scope_rows))
        self.assertEqual("CONTRADICTED", scope_rows[0]["label"])
        self.assertEqual("Assignment is prohibited.", scope_rows[0]["answer"])

    def test_qa_builder_keeps_impossible_and_builds_four_class_answer_family(self) -> None:
        payload = {
            "version": "v2.0",
            "data": [
                {
                    "title": "Policy",
                    "paragraphs": [
                        {
                            "context": "Appeals must be filed within ten days. The office is open Monday.",
                            "qas": [
                                {"id": "a", "question": "What is the deadline?", "answers": [{"text": "ten days", "answer_start": 29}], "is_impossible": False},
                                {"id": "u", "question": "What is the fee?", "answers": [], "is_impossible": True},
                            ],
                        }
                    ],
                }
            ],
        }
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "qa.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            generated = build_qa_corpus(
                path,
                source_dataset="SQuAD 2.0",
                source_version="2.0",
                source_license="CC BY-SA 4.0",
                language="en",
                raw_sha256=RAW_HASH,
                generator_commit=COMMIT,
            )

        self.assertEqual(
            {"SUPPORTED", "PARTIAL", "UNSUPPORTED"},
            {row["label"] for row in generated.answerability},
        )
        self.assertEqual(
            {"GROUNDED", "PARTIAL", "UNSUPPORTED", "CONTRADICTED"},
            {row["label"] for row in generated.groundedness},
        )
        prohibited = {
            "The document specifies a separate conclusion not requested here.",
            "The document also supplies another required field.",
            "文档明确给出了该问题未要求的另一项结论。",
            "文档还给出了另一个所需字段。",
        }
        self.assertFalse(
            any(
                phrase in str(row["answer"])
                for row in generated.groundedness
                for phrase in prohibited
            )
        )

    def test_qa_family_generates_diverse_contradiction_types(self) -> None:
        payload = {
            "version": "v2.0",
            "data": [{
                "title": "Policy",
                "paragraphs": [{
                    "context": "The deadline is 10 days. Renewal is 20 days. The office is Paris.",
                    "qas": [
                        {"id": "deadline", "question": "What is the deadline?", "answers": [{"text": "10 days", "answer_start": 16}]},
                        {"id": "renewal", "question": "What is the renewal period?", "answers": [{"text": "20 days", "answer_start": 36}]},
                        {"id": "office", "question": "Where is the office?", "answers": [{"text": "Paris", "answer_start": 59}]},
                        {"id": "missing", "question": "What is the fee?", "answers": [], "is_impossible": True},
                    ],
                }],
            }],
        }
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "qa.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            generated = build_qa_corpus(
                path,
                source_dataset="SQuAD 2.0",
                source_version="2.0",
                source_license="CC BY-SA 4.0",
                language="en",
                raw_sha256=RAW_HASH,
                generator_commit=COMMIT,
            )

        hard_types = {
            row["hard_negative_type"]
            for row in generated.groundedness
            if str(row["source_record_id"]).startswith("deadline:")
            and row["label"] == "CONTRADICTED"
        }
        self.assertTrue(
            {"NEGATION_FLIP", "WRONG_ENTITY", "WRONG_DATE", "WRONG_UNIT"} <= hard_types
        )

    def test_qa_builder_skips_punctuation_only_answers(self) -> None:
        payload = {
            "version": "v2.0",
            "data": [{
                "title": "Policy",
                "paragraphs": [{
                    "context": "The answer is clear.",
                    "qas": [
                        {"id": "bad", "question": "What is the answer?", "answers": [{"text": ".", "answer_start": 0}]},
                        {"id": "good", "question": "What is clear?", "answers": [{"text": "clear", "answer_start": 14}]},
                    ],
                }],
            }],
        }
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "qa.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            generated = build_qa_corpus(
                path,
                source_dataset="SQuAD 2.0",
                source_version="2.0",
                source_license="CC BY-SA 4.0",
                language="en",
                raw_sha256=RAW_HASH,
                generator_commit=COMMIT,
            )

        self.assertTrue(generated.answerability)
        self.assertTrue(all(not str(row["source_record_id"]).startswith("bad:") for row in generated.groundedness))

    def test_qa_relation_distractor_is_type_matched_and_family_shares_evidence(self) -> None:
        payload = {
            "version": "v2.0",
            "data": [{
                "title": "Policy",
                "paragraphs": [{
                    "context": "The old office is London. The new office is Paris. The fee is 24.",
                    "qas": [
                        {"id": "new", "question": "Where is the new office?", "answers": [{"text": "Paris", "answer_start": 48}]},
                        {"id": "old", "question": "Where was the old office?", "answers": [{"text": "London", "answer_start": 18}]},
                        {"id": "fee", "question": "What is the fee?", "answers": [{"text": "24", "answer_start": 65}]},
                    ],
                }],
            }],
        }
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "qa.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            generated = build_qa_corpus(
                path,
                source_dataset="SQuAD 2.0",
                source_version="2.0",
                source_license="CC BY-SA 4.0",
                language="en",
                raw_sha256=RAW_HASH,
                generator_commit=COMMIT,
                tokenizer=WhitespaceOffsetTokenizer(),
                max_length=32,
            )

        family = [row for row in generated.groundedness if row["mutation_family_id"] == "qa-" + build_full_corpus_v4._digest("SQuAD 2.0\0new\0SQuAD 2.0:Policy:0")[:24]]
        relation = next(row for row in family if row["hard_negative_type"] == "WRONG_ENTITY")
        self.assertIn("London", relation["answer"])
        self.assertNotIn("24", relation["answer"])
        self.assertEqual(1, len({json.dumps(row["evidence"], sort_keys=True) for row in family}))

    def test_qa_keeps_family_when_relation_distractor_is_outside_the_window(self) -> None:
        middle = " ".join(f"middle{index}" for index in range(80))
        context = f"London {middle} Paris"
        payload = {
            "version": "v2.0",
            "data": [{"title": "Policy", "paragraphs": [{
                "context": context,
                "qas": [
                    {"id": "old", "question": "Where was the old office?", "answers": [{"text": "London", "answer_start": 0}]},
                    {"id": "new", "question": "Where is the new office?", "answers": [{"text": "Paris", "answer_start": len(context) - 5}]},
                ],
            }]}],
        }
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "qa.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            generated = build_qa_corpus(
                path,
                source_dataset="SQuAD 2.0",
                source_version="2.0",
                source_license="CC BY-SA 4.0",
                language="en",
                raw_sha256=RAW_HASH,
                generator_commit=COMMIT,
                tokenizer=WhitespaceOffsetTokenizer(),
                max_length=32,
            )

        old_family = [row for row in generated.groundedness if str(row["source_record_id"]).startswith("old:")]
        self.assertIn("GROUNDED", {row["label"] for row in old_family})
        self.assertFalse(any(row["hard_negative_type"] == "WRONG_ENTITY" for row in old_family))

    def test_qa_naked_year_is_labeled_as_wrong_date(self) -> None:
        payload = {
            "version": "v2.0",
            "data": [{"title": "History", "paragraphs": [{
                "context": "The launch happened in 2013.",
                "qas": [{"id": "year", "question": "When was the launch?", "answers": [{"text": "2013", "answer_start": 23}]}],
            }]}],
        }
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "qa.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            generated = build_qa_corpus(
                path,
                source_dataset="SQuAD 2.0",
                source_version="2.0",
                source_license="CC BY-SA 4.0",
                language="en",
                raw_sha256=RAW_HASH,
                generator_commit=COMMIT,
            )

        numeric = [row for row in generated.groundedness if str(row["source_record_id"]).endswith("contradicted-number:g")]
        self.assertEqual(["WRONG_DATE"], [row["hard_negative_type"] for row in numeric])

    def test_cmrc_uses_natural_cross_document_negative_questions(self) -> None:
        payload = {
            "version": "v1.0",
            "data": [
                {"title": "差旅", "paragraphs": [{"context": "住宿上限是八百元。", "qas": [{"id": "hotel", "question": "住宿上限是多少？", "answers": [{"text": "八百元", "answer_start": 6}]}]}]},
                {"title": "交通", "paragraphs": [{"context": "高铁使用二等座。", "qas": [{"id": "rail", "question": "高铁使用什么席别？", "answers": [{"text": "二等座", "answer_start": 4}]}]}]},
            ],
        }
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "cmrc.json"
            path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            generated = build_qa_corpus(
                path,
                source_dataset="CMRC 2018",
                source_version="2018",
                source_license="CC BY-SA 4.0",
                language="zh",
                raw_sha256=RAW_HASH,
                generator_commit=COMMIT,
            )

        questions = [row["question"] for row in generated.answerability]
        self.assertTrue(any(question == "高铁使用什么席别？" for question in questions))
        self.assertFalse(any("参考编号" in question for question in questions))

    def test_qa_source_without_impossible_questions_still_builds_three_answerability_labels(self) -> None:
        payload = {
            "version": "v1.0",
            "data": [{
                "title": "差旅制度",
                "paragraphs": [{
                    "context": "住宿报销上限为八百元。",
                    "qas": [{
                        "id": "cmrc-1",
                        "question": "住宿报销上限是多少？",
                        "answers": [{"text": "八百元", "answer_start": 8}],
                    }],
                }],
            }],
        }
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "cmrc.json"
            path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            generated = build_qa_corpus(
                path,
                source_dataset="CMRC 2018",
                source_version="2018",
                source_license="CC BY-SA 4.0",
                language="zh",
                raw_sha256=RAW_HASH,
                generator_commit=COMMIT,
            )

        self.assertEqual(
            {"SUPPORTED", "PARTIAL", "UNSUPPORTED"},
            {row["label"] for row in generated.answerability},
        )

    def test_hover_not_supported_is_not_promoted_to_contradicted(self) -> None:
        records = [
            HoVerRecord("train", "supported", "The term is one year.", (("Policy", 0),), "SUPPORTED", 2, "hpqa-1"),
            HoVerRecord("train", "negative", "The term is two years.", (("Policy", 0),), "NOT_SUPPORTED", 2, "hpqa-1"),
        ]
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "wiki.db"
            connection = sqlite3.connect(database)
            connection.execute("CREATE TABLE documents (id PRIMARY KEY, text)")
            connection.execute("INSERT INTO documents(id, text) VALUES (?, ?)", ("Policy", "The term is one year."))
            connection.commit()
            connection.close()
            with HoVerEvidenceStore(database) as store:
                generated = build_hover_corpus(
                    records,
                    store,
                    raw_sha256=RAW_HASH,
                    generator_commit=COMMIT,
                )

        labels = {row["label"] for row in generated.groundedness}
        self.assertIn("GROUNDED", labels)
        self.assertIn("CONTRADICTED", labels)
        self.assertEqual(1, len({row["mutation_family_id"] for row in generated.groundedness}))
        contradicted = next(row for row in generated.groundedness if row["label"] == "CONTRADICTED")
        self.assertEqual("MULTI_HOP_CONTRADICTION", contradicted["hard_negative_type"])
        self.assertNotEqual("The term is two years.", contradicted["answer"])
        self.assertTrue(str(contradicted["source_record_id"]).startswith("supported:derived-"))
        self.assertFalse(
            any(
                str(row["source_record_id"]).startswith("negative:")
                for row in generated.groundedness
            )
        )

    def test_hover_not_supported_rows_are_not_emitted_with_multiple_positives(self) -> None:
        records = [
            HoVerRecord("train", "supported-1", "The term is one year.", (("Policy", 0),), "SUPPORTED", 2, "hpqa-1"),
            HoVerRecord("train", "supported-2", "The policy term is one year.", (("Policy", 0),), "SUPPORTED", 2, "hpqa-1"),
            HoVerRecord("train", "negative", "The term is two years.", (("Policy", 0),), "NOT_SUPPORTED", 2, "hpqa-1"),
        ]
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "wiki.db"
            connection = sqlite3.connect(database)
            connection.execute("CREATE TABLE documents (id PRIMARY KEY, text)")
            connection.execute("INSERT INTO documents(id, text) VALUES (?, ?)", ("Policy", "The term is one year."))
            connection.commit()
            connection.close()
            with HoVerEvidenceStore(database) as store:
                generated = build_hover_corpus(records, store, raw_sha256=RAW_HASH, generator_commit=COMMIT)

        self.assertFalse(
            any(
                str(row["source_record_id"]).startswith("negative:")
                for row in generated.answerability + generated.groundedness
            )
        )

    def test_quota_selection_is_deterministic_and_label_bounded(self) -> None:
        rows = [
            {"id": f"row-{index}", "label": "SUPPORTED" if index < 5 else "UNSUPPORTED"}
            for index in range(10)
        ]
        first = select_by_label_quotas(rows, {"SUPPORTED": 2, "UNSUPPORTED": 3}, seed="v4")
        second = select_by_label_quotas(list(reversed(rows)), {"SUPPORTED": 2, "UNSUPPORTED": 3}, seed="v4")
        self.assertEqual([row["id"] for row in first], [row["id"] for row in second])
        self.assertEqual(5, len(first))

    def test_answerability_selection_freezes_label_and_language_cells(self) -> None:
        rows = [
            {"id": f"{label}-{language}-{index}", "label": label, "language": language}
            for label in ("SUPPORTED", "PARTIAL", "UNSUPPORTED")
            for language in ("zh", "en")
            for index in range(4)
        ]
        quotas = {
            ("SUPPORTED", "zh"): 2,
            ("SUPPORTED", "en"): 3,
            ("PARTIAL", "zh"): 1,
            ("PARTIAL", "en"): 2,
            ("UNSUPPORTED", "zh"): 2,
            ("UNSUPPORTED", "en"): 2,
        }

        selected = select_by_label_language_quotas(rows, quotas, seed="release")

        self.assertEqual(quotas, Counter((row["label"], row["language"]) for row in selected))

    def test_answerability_selection_fails_closed_when_a_cell_is_short(self) -> None:
        with self.assertRaisesRegex(ValueError, "quota"):
            select_by_label_language_quotas(
                [{"id": "only", "label": "SUPPORTED", "language": "zh"}],
                {("SUPPORTED", "zh"): 2},
                seed="release",
            )

    def test_atomic_writer_emits_valid_jsonl(self) -> None:
        generated = build_contract_corpus(
            [ContractNliRecord("train", "doc-1", "h1", "The term is one year.", "Entailment", "The term is one year.", "The term is one year.")],
            raw_sha256=RAW_HASH,
            generator_commit=COMMIT,
        )
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "rows.jsonl"
            write_jsonl_atomic(output, generated.groundedness)
            parsed = [json.loads(line) for line in output.read_text(encoding="utf-8").splitlines()]
        self.assertEqual(1, len(parsed))
        self.assertEqual(
            "rag-guard-v4.2-full-corpus-1",
            parsed[0]["provenance"]["transform_version"],
        )


if __name__ == "__main__":
    unittest.main()
