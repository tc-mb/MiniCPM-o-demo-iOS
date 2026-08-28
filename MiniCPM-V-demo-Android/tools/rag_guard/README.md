# RAG 双三分类器训练数据

本目录只负责生成第一版匿名合成语料，不执行模型训练。

运行：

```powershell
python tools/rag_guard/build_dataset.py `
  --output-dir tools/rag_guard/data/generated `
  --examples-per-task 3000
```

每条 JSONL 记录包含：`id`、`task`、`label`、`question`、`evidence`、`answer`、
`document_id`、`split`、`language`、`hard_negative_type` 和 `source`。

- Answerability 标签：`SUPPORTED`、`PARTIAL`、`UNSUPPORTED`。
- Groundedness 标签：`GROUNDED`、`PARTIAL`、`UNGROUNDED`。
- 同一 `document_id` 只能属于一个 split，避免文档模板泄漏。
- 当前生成 80% train、10% calibration、10% test。
- 数据全部为合成办公制度，不得加入真实姓名、电话、身份证号、地址或文档正文。

`data/regression_seed.jsonl` 额外保存历史绕过、伪引用、错误数字、文档提示注入和
“文字资料描述图片”等测试用例，只进入 test，不参加训练。

现有 320 条 `SyntheticOfficeCalibrationCorpus` 继续作为检索校准集使用。它只有检索相关性标签，
没有完整的 Answerability/Groundedness 标注，因此不能自动转换成分类训练样本。三类数据源及用途
统一记录在 `data/dataset_sources.json`。

这些数据用于跑通训练与校准流程。正式模型启用前，还需加入脱敏、人工复核的真实分布样本，
但不得把任何原始隐私数据提交到 Git。
