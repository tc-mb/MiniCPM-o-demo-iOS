import json
import tempfile
import unittest
from pathlib import Path

from tools.rag_guard.build_answerability_v4 import (
    AnswerabilitySourceRecord,
    build_answerability_family,
    contract_text_to_answerability,
    load_squad_answerability,
)


class BuildAnswerabilityV4Test(unittest.TestCase):
    def test_explicit_negative_answer_is_supported(self) -> None:
        row = contract_text_to_answerability(
            question="合同是否允许自动续期？",
            evidence="本合同不得自动续期。",
            source_record_id="contract-1",
        )
        self.assertEqual("SUPPORTED", row.label)

    def test_family_contains_supported_partial_and_topic_similar_unsupported(self) -> None:
        source = AnswerabilitySourceRecord(
            source_dataset="fixture",
            source_version="1",
            source_license="CC-BY-4.0",
            source_record_id="record-1",
            document_id="doc-1",
            language="zh",
            domain="travel",
            question="住宿上限是多少？",
            evidence="差旅制度规定住宿上限为800元，申请由财务部审批。",
        )
        rows = build_answerability_family(
            source,
            missing_question="审批需要几个工作日？",
            unsupported_question="住宿上限是否包含早餐费用？",
        )

        self.assertEqual(
            ["SUPPORTED", "PARTIAL", "UNSUPPORTED"],
            [row["label"] for row in rows],
        )
        self.assertEqual(1, len({row["mutation_family_id"] for row in rows}))
        self.assertTrue(all(row["document_id"] == "doc-1" for row in rows))

    def test_squad_loader_preserves_impossible_questions_as_unsupported(self) -> None:
        payload = {
            "data": [
                {
                    "title": "Policy",
                    "paragraphs": [
                        {
                            "context": "Appeals must be filed within ten days.",
                            "qas": [
                                {
                                    "id": "answerable",
                                    "question": "What is the deadline?",
                                    "answers": [{"text": "ten days", "answer_start": 29}],
                                    "is_impossible": False,
                                },
                                {
                                    "id": "impossible",
                                    "question": "What is the filing fee?",
                                    "answers": [],
                                    "is_impossible": True,
                                },
                            ],
                        }
                    ],
                }
            ]
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "squad.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            records = load_squad_answerability(
                path,
                source_dataset="SQuAD 2.0",
                source_version="2.0",
                source_license="CC-BY-SA-4.0",
                language="en",
            )

        self.assertEqual(["SUPPORTED", "UNSUPPORTED"], [row["label"] for row in records])


if __name__ == "__main__":
    unittest.main()
