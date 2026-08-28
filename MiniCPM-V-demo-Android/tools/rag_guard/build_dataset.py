"""Build deterministic, privacy-safe synthetic corpora for the two RAG guard heads."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


DEPARTMENTS_ZH = ("财务部", "采购部", "人力资源部", "研发部", "行政部")
DEPARTMENTS_EN = ("Finance", "Procurement", "Human Resources", "Engineering", "Administration")


def _split(document_index: int) -> str:
    bucket = document_index % 10
    if bucket == 0:
        return "test"
    if bucket == 1:
        return "calibration"
    return "train"


def _base_case(document_index: int) -> dict[str, str | int]:
    amount = 600 + (document_index % 37) * 50
    deadline = 3 + document_index % 12
    wrong_amount = amount + 350
    wrong_deadline = deadline + 5
    english = document_index % 5 == 0
    document_id = f"office-policy-{document_index:04d}"
    if english:
        department = DEPARTMENTS_EN[document_index % len(DEPARTMENTS_EN)]
        evidence = (
            f"Policy {document_id}: {department} travel claims are capped at CNY {amount}. "
            f"Claims must be submitted within {deadline} days after the trip."
        )
        return {
            "document_id": document_id,
            "language": "en",
            "evidence": evidence,
            "supported_question": f"What is the travel claim cap for {department}?",
            "partial_question": (
                f"What is the travel claim cap for {department}, and how many remote-work days are allowed?"
            ),
            "unsupported_question": f"How many remote-work days are allowed for {department}?",
            "grounded_answer": (
                f"The cap is CNY {amount}, and claims are due within {deadline} days."
            ),
            "partial_answer": (
                f"The cap is CNY {amount}. Employees may also work remotely three days per week."
            ),
            "ungrounded_answer": (
                f"The cap is CNY {wrong_amount}, and claims are due within {wrong_deadline} days."
            ),
        }

    department = DEPARTMENTS_ZH[document_index % len(DEPARTMENTS_ZH)]
    evidence = (
        f"制度编号 {document_id}：{department}差旅报销上限为{amount}元，"
        f"出差结束后须在{deadline}日内提交报销材料。"
    )
    return {
        "document_id": document_id,
        "language": "zh",
        "evidence": evidence,
        "supported_question": f"{department}的差旅报销上限是多少？",
        "partial_question": f"{department}的差旅报销上限是多少，每周允许远程办公几天？",
        "unsupported_question": f"{department}每周允许远程办公几天？",
        "grounded_answer": f"报销上限为{amount}元，材料须在出差结束后{deadline}日内提交。",
        "partial_answer": f"报销上限为{amount}元，同时每周允许远程办公三天。",
        "ungrounded_answer": (
            f"报销上限为{wrong_amount}元，材料须在出差结束后{wrong_deadline}日内提交。"
        ),
    }


def _row(
    *,
    row_id: str,
    task: str,
    label: str,
    case: dict[str, str | int],
    question_key: str,
    answer_key: str | None,
    hard_negative_type: str,
    split: str,
) -> dict[str, str]:
    return {
        "id": row_id,
        "task": task,
        "label": label,
        "question": str(case[question_key]),
        "evidence": str(case["evidence"]),
        "answer": "" if answer_key is None else str(case[answer_key]),
        "document_id": str(case["document_id"]),
        "split": split,
        "language": str(case["language"]),
        "hard_negative_type": hard_negative_type,
        "source": "synthetic_office_v1",
    }


def _write_jsonl(path: Path, rows: list[dict[str, str]]) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as output:
        for row in rows:
            output.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
    temporary.replace(path)


def build_dataset(output_dir: Path, examples_per_task: int = 3000) -> dict[str, int]:
    if examples_per_task < 3 or examples_per_task > 30_000 or examples_per_task % 3 != 0:
        raise ValueError("examples_per_task must be divisible by 3 and between 3 and 30000")

    output_dir = output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    rows: list[dict[str, str]] = []
    document_count = examples_per_task // 3
    for index in range(document_count):
        case = _base_case(index)
        split = _split(index)
        document_id = str(case["document_id"])
        rows.extend(
            (
                _row(
                    row_id=f"ans-{document_id}-supported",
                    task="answerability",
                    label="SUPPORTED",
                    case=case,
                    question_key="supported_question",
                    answer_key=None,
                    hard_negative_type="NONE",
                    split=split,
                ),
                _row(
                    row_id=f"ans-{document_id}-partial",
                    task="answerability",
                    label="PARTIAL",
                    case=case,
                    question_key="partial_question",
                    answer_key=None,
                    hard_negative_type="MISSING_FIELD",
                    split=split,
                ),
                _row(
                    row_id=f"ans-{document_id}-unsupported",
                    task="answerability",
                    label="UNSUPPORTED",
                    case=case,
                    question_key="unsupported_question",
                    answer_key=None,
                    hard_negative_type="SAME_DOMAIN_MISSING_FIELD",
                    split=split,
                ),
                _row(
                    row_id=f"grd-{document_id}-grounded",
                    task="groundedness",
                    label="GROUNDED",
                    case=case,
                    question_key="supported_question",
                    answer_key="grounded_answer",
                    hard_negative_type="NONE",
                    split=split,
                ),
                _row(
                    row_id=f"grd-{document_id}-partial",
                    task="groundedness",
                    label="PARTIAL",
                    case=case,
                    question_key="partial_question",
                    answer_key="partial_answer",
                    hard_negative_type="MIXED_SUPPORT",
                    split=split,
                ),
                _row(
                    row_id=f"grd-{document_id}-ungrounded",
                    task="groundedness",
                    label="UNGROUNDED",
                    case=case,
                    question_key="supported_question",
                    answer_key="ungrounded_answer",
                    hard_negative_type="WRONG_NUMBER",
                    split=split,
                ),
            ),
        )

    for task in ("answerability", "groundedness"):
        for split in ("train", "calibration", "test"):
            selected = [row for row in rows if row["task"] == task and row["split"] == split]
            _write_jsonl(output_dir / f"{task}_{split}.jsonl", selected)

    return {
        "answerability": sum(row["task"] == "answerability" for row in rows),
        "groundedness": sum(row["task"] == "groundedness" for row in rows),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--examples-per-task", type=int, default=3000)
    arguments = parser.parse_args()
    summary = build_dataset(arguments.output_dir, arguments.examples_per_task)
    print(json.dumps(summary, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
