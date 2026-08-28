# 公开办公 RAG Guard 测试集

本工具从有明确开放许可的公开文档问答数据构造独立评测集，用于检验
Answerability 与 Groundedness 分类头的跨文档、跨领域泛化能力。

它是公开数据预资格测试，不是企业内部真实办公分布验收。生成记录固定标记为：

- `distribution=public_office_licensed`
- `redaction_status=public_source_reviewed`
- `qualification_scope=public_prequalification_only`

即使公开预资格通过，也不能直接写入生产 `CurrentAnswerabilityCalibration.profile`；生产启用仍需
`real_office_redacted` 的独立校准集与测试集。

## 来源与许可

| 来源 | 用途 | 许可 | 固定方式 |
|---|---|---|---|
| Doc2Dial v1.0.1 | 政务办理、公共服务问答 | 数据集卡标注 CC BY 3.0；代码仓库为 Apache-2.0 | 版本 URL + ZIP SHA-256 |
| CUAD v1 | 商务合同条款问答 | CC BY 4.0 | 官方 GitHub 数据包 + ZIP SHA-256 |

生成器固定验证完整 SHA-256，并拒绝路径穿越、符号链接、超大成员、异常压缩比和缺少必要成员的 ZIP。
原始归档、未评分 JSONL、评分结果均放在 `D:\MiniCPM-V\private-eval\rag-guard-public`，不提交 Git。

FinanceBench 只公开了 150 条样例，但官方仓库当前没有清晰的根级许可证文件，因此本轮不自动纳入。

## 构造规则

每个来源分别选择 20 份校准文档和 20 份测试文档。排序键为固定种子、来源名与源文档 ID 的
SHA-256，因此输入归档不变时结果可重复。校准和测试按源文档切分，文档 ID 交集必须为空。

每份文档生成六条记录：

- Answerability `SUPPORTED`：原问题 + 原证据。
- Answerability `PARTIAL`：原问题再追加一个证据无法回答的子问题，证据保持不变。
- Answerability `UNSUPPORTED`：同一切分内另一文档的问题 + 当前证据。
- Groundedness `GROUNDED`：原问题 + 原证据 + 原答案。
- Groundedness `PARTIAL`：原答案后追加一项无证据支持的条件。
- Groundedness `UNGROUNDED`：使用同一切分内另一文档的答案。

当前每个切分有 40 份文档、240 条记录；每项任务 120 条，三类各 40 条。

## 生成与评分

```powershell
$python = 'C:\Users\mingjun.dong\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$raw = 'D:\MiniCPM-V\private-eval\rag-guard-public\raw'
$out = 'D:\MiniCPM-V\private-eval\rag-guard-public\generated'

& $python -m tools.rag_guard.public_office_dataset `
  --doc2dial "$raw\doc2dial_v1.0.1.zip" `
  --cuad "$raw\cuad_data.zip" `
  --output-dir $out
```

评分时必须显式指定公开分布，默认值仍是更严格的 `real_office_redacted`：

```powershell
& $python tools/rag_guard/score_office_holdout.py `
  --input "$out\public_office_test_unscored.jsonl" `
  --output "$out\public_office_test_scored.jsonl" `
  --manifest D:\MiniCPM-V\artifacts\rag-guard-dual-head-v2\manifest.json `
  --model D:\MiniCPM-V\artifacts\rag-guard-dual-head-v2\model.int8.onnx `
  --tokenizer D:\MiniCPM-V\artifacts\multilingual-e5-small-int8-pinned-132949c958b5\tokenizer.onnx `
  --distribution public_office_licensed
```

## 2026-08-19 结果

固定 Guard 与 tokenizer 完成 480 条 CPU 评分。公开预资格未通过：

| 指标 | 结果 | 门槛 |
|---|---:|---:|
| Answerability precision | 1.0000 | 至少 0.95 |
| Answerability recall | 0.0250 | 至少 0.90 |
| Groundedness macro-F1 | 0.3996 | 至少 0.85 |
| Groundedness ECE | 0.1452 | 至多 0.10 |

阈值是在校准集上满足精确率约束后冻结得到的 (0.9199624295)。结果说明当前模型在规则化合成训练集上
过拟合，无法可靠泛化到政务对话和合同文本。`profile` 必须继续保持 `null`。下一步应把公开训练文档与
人工审核的困难负例加入训练集后重新训练，再使用完全不参与训练的文档级隔离测试集复验。
