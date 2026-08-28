# RAG Guard 独立办公分布质量门槛

此门槛用于决定双三分类器是否有资格进入 App 的生产 RAG 路径。它不会训练模型，也不会因为合成测试集分数较高而自动启用模型。

## 数据隔离

准备两份已经人工脱敏并复核的 JSONL：

- `office_calibration.jsonl`：只用于选择 Answerability 的 `SUPPORTED` 概率阈值。
- `office_test.jsonl`：只用于最终验收，不能参与阈值选择。

字段格式可参考 `data/office_holdout_example_unscored.jsonl`。该文件仅为匿名合成格式示例，不能计入真实办公质量验收。

训练、办公校准、办公测试三部分的 `document_id` 必须两两不相交。每条记录必须标记：

```json
{
  "id": "office-test-a-001",
  "task": "answerability",
  "label": "SUPPORTED",
  "probabilities": [0.97, 0.02, 0.01],
  "document_id": "redacted-document-001",
  "distribution": "real_office_redacted",
  "redaction_status": "reviewed",
  "model_sha256": "45d42125648c169a19697ce8b64f6883e63c2d8a45fd666c73bf163a3c59e097",
  "tokenizer_sha256": "3396f311d68a8ee4351c0949ab2626543334c5566d7f8ea17b026952ac14d0fe",
  "question": "脱敏后的问题",
  "evidence": "脱敏后的证据",
  "answer": ""
}
```

Groundedness 记录使用 `GROUNDED/PARTIAL/UNGROUNDED` 标签并填写 `answer`。JSONL 只允许保存在受控评测目录，不提交真实办公正文、手机号、身份证号或地址到 Git。工具会拒绝未标记人工复核的数据，并对手机号和身份证号做第二道阻断；该自动检查不能替代人工脱敏。

## 验收规则

Answerability 把 `SUPPORTED` 视为可注入，其余两类视为不可注入。在办公校准集上选择满足精确率要求且召回率最高的阈值；同指标并列时选择更高阈值。冻结阈值后只在办公测试集计算：

$$
\mathrm{Precision}=\frac{TP}{TP+FP},\qquad
\mathrm{Recall}=\frac{TP}{TP+FN}
$$

Groundedness 在办公测试集计算三分类 macro-F1 和 ECE。默认闸门为：

$$
\mathrm{Precision}_{answerability}\ge 0.95,\quad
\mathrm{Recall}_{answerability}\ge 0.90
$$

$$
\mathrm{MacroF1}_{groundedness}\ge 0.85,\quad
\mathrm{ECE}_{groundedness}\le 0.10
$$

办公测试集每个任务默认至少 100 条；实际发布前应扩大覆盖部门、文档类型、字段缺失、错误日期/金额/编号、跨文档和提示注入等困难案例。

## 执行

`training_document_ids.txt` 每行保存一个训练文档 ID。评测概率必须由固定 Guard ONNX 和固定 E5 tokenizer 生成；工具会逐条校验两者 SHA-256。

当前 Windows 项目环境已在 `D:\MiniCPM-V\.rag-python-tools` 安装并验证 CPU 版 `onnxruntime 1.22.1`、`onnxruntime-extensions 0.13.0` 和 `numpy 2.5.2`，不需要显卡、CUDA 或 PyTorch，也不需要重复创建虚拟环境。使用 Codex 工作区 Python 时，必须同时把依赖目录和 Android 项目根目录加入 `PYTHONPATH`：

```powershell
$env:PYTHONPATH = 'D:\MiniCPM-V\.rag-python-tools;D:\MiniCPM-V\MiniCPM-V-Apps\MiniCPM-V-demo-Android'
$python = 'C:\Users\mingjun.dong\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
```

先用与 Android 相同的 `tokenizer.onnx` 和 Guard `model.int8.onnx` 生成概率。评分器逐文件校验 manifest 的长度与 SHA-256，使用 ORT Extensions 执行 tokenizer 自定义算子，并复现 App 的 256-token 截断和结束 token 保留规则：

```powershell
& $python tools/rag_guard/score_office_holdout.py `
  --input <受控目录>\office_calibration_unscored.jsonl `
  --output <受控目录>\office_calibration.jsonl `
  --manifest D:\MiniCPM-V\artifacts\rag-guard-dual-head-v2\manifest.json `
  --model D:\MiniCPM-V\artifacts\rag-guard-dual-head-v2\model.int8.onnx `
  --tokenizer D:\MiniCPM-V\artifacts\multilingual-e5-small-int8-pinned-132949c958b5\tokenizer.onnx
```

办公测试集用同一命令单独评分。评分器只在标准输出中显示样本数和两个哈希，不打印问题、证据或回答。

```powershell
& $python tools/rag_guard/quality_gate.py `
  --office-calibration <受控目录>\office_calibration.jsonl `
  --office-test <受控目录>\office_test.jsonl `
  --training-document-ids <受控目录>\training_document_ids.txt `
  --classifier-sha256 45d42125648c169a19697ce8b64f6883e63c2d8a45fd666c73bf163a3c59e097 `
  --tokenizer-sha256 3396f311d68a8ee4351c0949ab2626543334c5566d7f8ea17b026952ac14d0fe `
  --output <受控目录>\quality-gate-report.json
```

退出码 `0` 表示所有门槛通过，退出码 `2` 表示指标未通过；数据损坏、隐私检查失败、模型哈希不符或文档泄漏会直接报错。报告仅包含聚合指标、样本数、阈值和模型哈希，不包含问题、证据或回答正文。

即使报告通过，也必须同时满足真机延迟、内存和稳定性门槛，才允许把 `MiniCPMApplication` 中的 `classifier=null/profile=null` 替换为固定版本化配置。
