import gzip
import json
import io
import tarfile
import zipfile
import tempfile
import unittest
from pathlib import Path

from tools.rag_guard.build_multisource_dataset import (
    CorpusExample,
    build_balanced_rows,
    load_dialogue_prompts,
    load_kdconv,
    load_oasst_messages,
    load_squad_documents,
    load_squad_tar_documents,
    safe_extract_zip,
    write_training_dataset,
)


def example(index: int, *, language: str = "zh", source: str = "fixture") -> CorpusExample:
    return CorpusExample(
        source=source,
        source_document_id=f"document-{index}",
        language=language,
        domain="general",
        question=f"问题 {index} 的正确答案是什么？" if language == "zh" else f"What is answer {index}?",
        evidence=(
            f"文档 {index} 说明正确答案是数值 {index + 100}，并给出了适用条件。"
            if language == "zh"
            else f"Document {index} says the answer is {index + 100} under the stated conditions."
        ),
        answer=f"数值 {index + 100}" if language == "zh" else f"The answer is {index + 100}.",
    )


class MultiSourceDatasetTest(unittest.TestCase):
    def test_writer_emits_six_training_files_and_aggregate_manifest(self) -> None:
        documents = [
            example(index, language="zh" if index < 30 else "en") for index in range(60)
        ]
        rows = build_balanced_rows(documents, [], seed="writer-v3")
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)

            manifest = write_training_dataset(output, rows, source_counts={"fixture": 60})

            names = {path.name for path in output.glob("*.jsonl")}
            self.assertEqual(
                names,
                {
                    f"{task}_{split}.jsonl"
                    for task in ("answerability", "groundedness")
                    for split in ("train", "calibration", "test")
                },
            )
            self.assertEqual(manifest["source_counts"], {"fixture": 60})
            manifest_text = (output / "dataset_manifest.json").read_text(encoding="utf-8")
            self.assertNotIn("Document 59 says", manifest_text)

    def test_zip_extraction_rejects_path_traversal(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "unsafe.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("../escape.json", "{}")

            with self.assertRaisesRegex(ValueError, "unsafe zip member"):
                safe_extract_zip(path, Path(directory) / "output")

    def test_tar_loader_rejects_path_traversal(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "unsafe.tar.gz"
            payload = b"{}"
            with tarfile.open(path, "w:gz") as archive:
                member = tarfile.TarInfo("../escape.json")
                member.size = len(payload)
                archive.addfile(member, io.BytesIO(payload))

            with self.assertRaisesRegex(ValueError, "unsafe tar member"):
                load_squad_tar_documents(path, source="unsafe", language="zh")

    def test_kdconv_loader_builds_grounded_examples_and_daily_prompts(self) -> None:
        conversations = [
            {
                "name": "歌曲",
                "messages": [
                    {"message": "你了解这首歌吗？"},
                    {
                        "message": "它是电影的主题曲。",
                        "attrs": [
                            {"name": "歌曲", "attrname": "用途", "attrvalue": "电影主题曲"}
                        ],
                    },
                    {
                        "message": "它在二零二零年发行。",
                        "attrs": [
                            {"name": "歌曲", "attrname": "发行时间", "attrvalue": "二零二零年"}
                        ],
                    },
                    {"message": "谢谢，今天过得怎么样？"},
                ],
            }
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "data" / "music"
            path.mkdir(parents=True)
            (path / "train.json").write_text(
                json.dumps(conversations, ensure_ascii=False), encoding="utf-8"
            )

            documents, prompts = load_kdconv(Path(directory))

        self.assertEqual(len(documents), 2)
        self.assertIn("电影主题曲", documents[0].evidence)
        self.assertEqual(documents[0].question, "你了解这首歌吗？")
        self.assertEqual(len(prompts), 2)

    def test_dialogue_prompt_loader_understands_role_and_content(self) -> None:
        payload = {
            "dialogue-1": {
                "messages": [
                    {"role": "usr", "content": "帮我找一家附近的餐厅"},
                    {"role": "sys", "content": "请问希望吃什么菜系？"},
                    {"role": "usr", "content": "川菜"},
                ]
            }
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "train.json"
            path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

            prompts = load_dialogue_prompts([path], source="crosswoz", language="zh")

        self.assertEqual([item[2] for item in prompts], ["帮我找一家附近的餐厅", "川菜"])

    def test_squad_loader_preserves_document_identity_and_skips_impossible_questions(self) -> None:
        payload = {
            "version": "2.0",
            "data": [
                {
                    "title": "Policy A",
                    "paragraphs": [
                        {
                            "context": "Policy A allows ten days for filing an appeal.",
                            "qas": [
                                {
                                    "id": "q-supported",
                                    "question": "How many days are allowed?",
                                    "is_impossible": False,
                                    "answers": [{"text": "ten days", "answer_start": 16}],
                                },
                                {
                                    "id": "q-impossible",
                                    "question": "What is the filing fee?",
                                    "is_impossible": True,
                                    "answers": [],
                                },
                            ],
                        }
                    ],
                }
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "squad.json"
            path.write_text(json.dumps(payload), encoding="utf-8")

            rows = load_squad_documents(path, source="squad2", language="en")

        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0].source_document_id, "Policy A:0")
        self.assertEqual(rows[0].answer, "ten days")

    def test_squad_loader_keeps_answer_inside_long_evidence_window(self) -> None:
        context = "开头内容。" * 400 + "关键答案是四十二天。" + "结尾内容。" * 100
        answer = "四十二天"
        payload = {
            "data": [
                {
                    "title": "长文档",
                    "paragraphs": [
                        {
                            "context": context,
                            "qas": [
                                {
                                    "id": "long-1",
                                    "question": "关键答案是多少天？",
                                    "answers": [{"text": answer, "answer_start": context.index(answer)}],
                                }
                            ],
                        }
                    ],
                }
            ]
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "long.json"
            path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

            rows = load_squad_documents(path, source="long", language="zh")

        self.assertIn(answer, rows[0].evidence)
        self.assertLessEqual(len(rows[0].evidence), 1400)

    def test_text_sanitization_preserves_dates_but_redacts_real_phone_numbers(self) -> None:
        context = "政策于2017-04-10发布，联系电话13812345678，申报期限为四十二天。"
        payload = {
            "data": [
                {
                    "title": "日期政策",
                    "paragraphs": [
                        {
                            "context": context,
                            "qas": [
                                {
                                    "question": "申报期限是多少？",
                                    "answers": [
                                        {"text": "四十二天", "answer_start": context.index("四十二天")}
                                    ],
                                }
                            ],
                        }
                    ],
                }
            ]
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "date.json"
            path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

            rows = load_squad_documents(path, source="date", language="zh")

        self.assertIn("2017-04-10", rows[0].evidence)
        self.assertIn("[PHONE]", rows[0].evidence)
        self.assertNotIn("13812345678", rows[0].evidence)

    def test_oasst_loader_keeps_reviewed_user_prompts_in_both_languages(self) -> None:
        messages = [
            {
                "message_id": "en-1",
                "role": "prompter",
                "lang": "en",
                "text": "How are you doing today?",
                "deleted": False,
                "review_result": True,
            },
            {
                "message_id": "zh-1",
                "role": "prompter",
                "lang": "zh",
                "text": "今天心情怎么样？",
                "deleted": False,
                "review_result": True,
            },
            {
                "message_id": "bad-1",
                "role": "assistant",
                "lang": "en",
                "text": "Assistant reply",
                "deleted": False,
                "review_result": True,
            },
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "oasst.jsonl.gz"
            with gzip.open(path, "wt", encoding="utf-8", newline="\n") as output:
                for message in messages:
                    output.write(json.dumps(message, ensure_ascii=False) + "\n")

            prompts = load_oasst_messages(path)

        self.assertEqual(prompts, [("oasst1:en-1", "en", "How are you doing today?"), ("oasst1:zh-1", "zh", "今天心情怎么样？")])

    def test_builder_is_balanced_bilingual_deterministic_and_document_isolated(self) -> None:
        documents = [example(index, language="zh" if index < 80 else "en") for index in range(120)]
        conversations = [
            (f"daily-{index}", "zh" if index % 2 == 0 else "en", f"日常聊天问题 {index}" if index % 2 == 0 else f"Daily chat question {index}")
            for index in range(60)
        ]

        first = build_balanced_rows(documents, conversations, seed="fixed-v3")
        second = build_balanced_rows(documents, conversations, seed="fixed-v3")

        self.assertEqual(first, second)
        split_documents: dict[str, set[str]] = {}
        for split, rows in first.items():
            split_documents[split] = {row["document_id"] for row in rows}
            for task in ("answerability", "groundedness"):
                task_rows = [row for row in rows if row["task"] == task]
                counts: dict[tuple[str, str], int] = {}
                for row in task_rows:
                    key = (row["label"], row["language"])
                    counts[key] = counts.get(key, 0) + 1
                self.assertEqual(len(set(counts.values())), 1)
            self.assertEqual({row["language"] for row in rows}, {"zh", "en"})
        self.assertTrue(split_documents["train"].isdisjoint(split_documents["calibration"]))
        self.assertTrue(split_documents["train"].isdisjoint(split_documents["test"]))
        self.assertTrue(split_documents["calibration"].isdisjoint(split_documents["test"]))

    def test_builder_excludes_reserved_document_ids(self) -> None:
        documents = [example(index, language="zh" if index % 2 == 0 else "en") for index in range(90)]
        reserved = {documents[0].document_id, documents[1].document_id}

        rows = build_balanced_rows(documents, [], seed="fixed-v3", excluded_document_ids=reserved)

        observed = {row["document_id"] for split in rows.values() for row in split}
        self.assertTrue(reserved.isdisjoint(observed))


if __name__ == "__main__":
    unittest.main()
