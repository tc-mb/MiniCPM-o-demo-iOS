# RAG Guard 数据集重构与训练计划

> **架构更新：** 本文保留数据集调研、瓶颈证据和来源许可分析。最终的 Answerability 三分类 + Groundedness 四分类训练、Android 迁移和发布执行步骤，以 `docs/superpowers/plans/2026-08-24-rag-guard-answerability-3-groundedness-4-plan.md` 为准。

> 日期：2026-08-24  
> 状态：`PLAN_ONLY / NO_DATA_DOWNLOAD / NO_TRAINING`  
> 适用分支：`codex/rag-all-queries-experiment`  
> 当前模型：`local/minicpm-rag-guard-dual-head-v3-experimental`  
> 当前 INT8 SHA-256：`6d11400d62b8f15250932e3187aa7b7823809dc0baf0a0ff0a3c157dbe1d35fa`

## 1. 本阶段边界

本文件只汇总现有训练/测试证据，筛选外部数据集，并定义后续的数据改造、训练、量化和发布计划。本阶段没有下载大型数据集、生成新训练样本、修改现有 Guard 运行逻辑或启动训练。

后续任何数据进入受控训练目录前，必须先完成：

1. 官方来源与固定版本核验；
2. 许可证、署名、商用和衍生改造权利核验；
3. 原始归档 SHA-256 固定；
4. 隐私、恶意归档和不可信序列化检查；
5. 数据来源、改造方法和删除流程登记。

## 2. 审计范围

本轮交叉检查了以下材料和本地模型产物：

- `tools/rag_guard/MULTISOURCE_TRAINING_V3.md`
- `tools/rag_guard/PUBLIC_OFFICE_HOLDOUT.md`
- `tools/rag_guard/OFFICE_QUALITY_GATE.md`
- `tools/rag_guard/TRAINING.md`
- `tools/rag_guard/README.md`
- `tools/rag_guard/data/dataset_sources.json`
- `tools/rag_guard/data/regression_seed.jsonl`
- `tools/rag_guard/build_multisource_dataset.py`
- `tools/rag_guard/train.py`
- `tools/rag_guard/model.py`
- `docs/execution/evidence/groundedness-release-matrix-20260824.md`
- `docs/execution/evidence/rag-retrieval-calibration-20260817.md`
- `docs/superpowers/plans/2026-08-18-minicpm-android-unified-progress-plan.md`
- `D:/MiniCPM-V/artifacts/rag-guard-dual-head-v2/quantization_metrics.json`
- `D:/MiniCPM-V/artifacts/rag-guard-dual-head-v3/metrics.json`
- `D:/MiniCPM-V/artifacts/rag-guard-dual-head-v3/quantization_metrics.json`
- `D:/MiniCPM-V/artifacts/rag-guard-dual-head-v3/artifact_manifest.json`
- `D:/MiniCPM-V/private-eval/rag-guard-public/generated/public-quality-gate-report.json`

## 3. 当前模型与任务

Guard 不是 MiniCPM 聊天模型本身，而是共享 `intfloat/multilingual-e5-small` 编码器的双头分类器：

- Answerability：输入“问题 + 检索证据”，输出 `SUPPORTED / PARTIAL / UNSUPPORTED`；
- Groundedness：输入“问题 + 检索证据 + 候选回答”，输出 `GROUNDED / PARTIAL / UNGROUNDED`。

当前模型使用 384 维隐藏层、均值池化、两个线性三分类头，最大长度为 256 token。训练 checkpoint 以两个任务 macro-F1 的平均值选择：

$$
S=\frac{F1_{answerability}+F1_{groundedness}}{2}
$$

导出的单个 INT8 ONNX 文件约 118 MB。Android 端真实 CPU 测试中，Answerability P50/P95 约为 9.905/12.814 ms，Groundedness P50/P95 约为 13.062/17.906 ms；模型打开约 1.386 s，测试进程 PSS 增量约 239 MB。

## 4. 现有训练与测试结果

### 4.1 v2 合成基线

v2 每任务只有 300 条 calibration 和 300 条 test，结构高度规则：

| 项目 | FP32 | INT8 |
|---|---:|---:|
| Answerability test macro-F1 | 1.0000 | 1.0000 |
| Groundedness test macro-F1 | 1.0000 | 1.0000 |
| INT8/FP32 标签一致率 | — | 0.9984 |
| 最大 macro-F1 下降 | — | 0.0000 |

该结果只证明管线能学习合成模板，不能证明真实泛化。旧回归集中，v2 FP32 的 Answerability/Groundedness macro-F1 只有 0.8056/0.5333，已经暴露出模板外能力不足。

### 4.2 公开办公预资格

Doc2Dial 与 CUAD 构造的公开独立留出集按文档隔离，校准/测试各 40 份文档、240 条记录。v2 结果为：

| 指标 | 结果 | 门槛 | 结论 |
|---|---:|---:|---|
| Answerability precision | 1.0000 | 至少 0.95 | 通过 |
| Answerability recall | 0.0250 | 至少 0.90 | 严重失败 |
| Groundedness macro-F1 | 0.3996 | 至少 0.85 | 失败 |
| Groundedness ECE | 0.1452 | 至多 0.10 | 失败 |

冻结阈值为 0.9199624295。极低召回率说明模型在新文档表达上过度保守，而 Groundedness 的低 macro-F1 表明三类边界没有跨数据来源泛化。

### 4.3 v3 多来源中英文训练

v3 使用 SQuAD 2.0、Doc2Dial、CUAD、CMRC2018、DRCD、DuReader Robust、KdConv、CrossWOZ 和 OASST1。每个任务包含 92,244 条 train、5,124 条 calibration、5,124 条 test，标签和中英文严格平衡。

| 模型 | Answerability macro-F1 | Groundedness macro-F1 | Groundedness ECE |
|---|---:|---:|---:|
| FP32 | 0.9897 | 0.8128 | 0.0080 |
| INT8 | 0.9885 | 0.8088 | 0.0098 |

量化标签一致率为 0.9921，低于 0.995 门槛；旧回归种子最大 macro-F1 降幅为 0.0979，超过 0.01 门槛。更关键的是，v3 FP32 在 16 条历史回归样本上的 Answerability/Groundedness macro-F1 已降至 0.3571/0.4405，说明问题不只来自 INT8 量化。

### 4.4 真机 Groundedness 发布矩阵

| 场景 | 预期 | 模型结果 | `GROUNDED` 概率 | 结论 |
|---|---|---|---:|---|
| 正确金额、日期、负责人 | 放行 | `GROUNDED` | 0.99274147 | 正确 |
| 金额改为 999 元 | 拒绝 | `GROUNDED` | 0.99171877 | 错误放行 |
| 日期改为 2027-01-01 | 拒绝 | `GROUNDED` | 0.99198450 | 错误放行 |
| 完全无依据扩写 | 拒绝 | `UNGROUNDED` | 0.00697412 | 正确 |

正确样本与金额/日期最小改动错误样本的分数几乎重叠。因此调高阈值无法解决，并会先伤害正确回答召回。

## 5. 已确认的核心瓶颈

### B1. 训练负例过于容易，模型学会了模板而不是事实对齐

v3 的每份文档主要生成六种固定形式：原问题、拼接另一个问题、整份错误文档问题、原答案、原答案后追加无依据句、整份错误文档答案。这些负例通常存在明显的主题或句式差异。模型容易用词面不匹配、句子拼接痕迹或长度特征分类，无需比较金额、日期、实体、否定和范围。

必须加入“其余 token 尽量不变，只改变一个事实槽位”的最小对，使正确与错误样本的表面相似度接近 1。

### B2. Groundedness 标签边界明显弱于 Answerability

v3 Answerability 已接近 0.99，但 Groundedness 只有约 0.81。当前 checkpoint 选择指标取两头平均，强势的 Answerability 会掩盖 Groundedness 未达标。后续不得再只按平均分选模。

推荐使用硬门槛后再排序：

$$
F1_{answerability}\ge 0.95,\qquad F1_{groundedness}\ge 0.85
$$

满足门槛后，优先最大化困难负例组的最小召回率；再以 ECE、INT8 对齐和模型大小打破并列。

### B3. 大规模扩容没有保护历史能力

v3 的总量远大于 v2，但 16 条旧回归集表现下降。严格标签平衡和数据量并不能替代困难类型覆盖。历史回归样本当前只进入测试，不进入训练；这适合防止“背答案”，但必须新增同一错误机制、不同文档和不同表述的训练族，避免只保留 16 个孤立测试点。

### B4. 数字、日期、实体和否定的局部一致性不足

真机错误金额/日期以高置信度通过，表明模型主要识别“回答与证据主题一致”，没有可靠执行字段级蕴含。需要覆盖：

- 金额的数字/中文大写/币种/税前税后/上限下限；
- 日期的年月日/相对日期/生效与签署日期；
- 数量、百分比、编号、版本号和单位换算；
- 部门、人员角色、合同主体和地名替换；
- `必须/可以/不得`、`包含/不包含`、`至少/至多` 等否定与范围；
- 多证据块之间的冲突、过期版本和跨文档串线。

### B5. PARTIAL 类的构造和标注边界过窄

当前 PARTIAL 常由“正确答案 + 一整句无依据内容”产生，特征明显。真实 PARTIAL 更常见于：多个字段只支持一部分、结论正确但原因无依据、引用只覆盖其中一个断言、限定条件遗漏、主句可支持但数值或时间错误。必须按原子断言标注覆盖率。

对回答拆出原子断言集合 (C=\{c_1,\ldots,c_n\})，证据支持集合为 (S\subseteq C)。标签规则固定为：

$$
\begin{aligned}
&\lvert S\rvert=n &&\Rightarrow \mathrm{GROUNDED}\\
&0<\lvert S\rvert<n &&\Rightarrow \mathrm{PARTIAL}\\
&\lvert S\rvert=0 &&\Rightarrow \mathrm{UNGROUNDED}
\end{aligned}
$$

若任一核心槽位与证据矛盾，即使其他文字正确，也不得标为 `GROUNDED`。

### B6. 公开跨域泛化与真实办公验收仍不足

公开预资格已经证明规则合成到政务/合同存在显著域差距；同时，生产要求的授权、人工脱敏、文档级隔离真实办公 calibration/test 仍不存在。公开数据可用于训练和预资格，但不能替代真实分布最终门槛。

### B7. 256-token 拼接可能截断关键证据

Groundedness 同时拼接问题、证据和完整候选回答，最大长度仍为 256 token。合同、制度和多块证据中，关键限定词可能被截断。现有输入只做整体拼接与末端 token 保留，没有为问题、证据、回答分别分配预算。该项需要先通过截断审计验证，再决定是否采用字段预算或多窗口分类。

### B8. INT8 决策边界不稳

v3 的 INT8/FP32 标签一致率低于发布门槛，最大 logit 差为 5.849，历史回归 macro-F1 最大下降约 0.098。这通常意味着困难样本的 FP32 决策间隔本就过小，量化进一步翻转标签。先改善训练边界，再扩大有代表性的量化校准集；不能仅靠放宽量化门槛。

### B9. 端侧延迟合格，但内存仍偏高

约 10–18 ms 的单次分类延迟可接受；118 MB 模型和约 239 MB PSS 增量对长期驻留不理想。现有惰性加载和普通回答降级应保留。质量达标前不应为减小模型而更换编码器；质量通过后再比较蒸馏、小模型和会话释放策略。

## 6. 外部候选数据集调研结果

<!-- DATASET_RESEARCH_START -->
本轮只收集元数据和许可信息，没有下载数据。以下“改造用途”是本项目的规划推断，不代表原数据集自带这些三分类标签。仓库代码许可证不自动等于数据正文许可证；来源或第三方文本权利不清时统一标记为 `review_required`。

### 6.1 A 级：第一批优先申请和核验

| 数据集 | 语言/领域 | 原始信号 | 计划用途 | 许可结论 |
|---|---|---|---|---|
| [ContractNLI](https://stanfordnlp.github.io/contract-nli/) | 英文 NDA/合同 | `Entailment / Contradiction / NotMentioned`、证据 span；607 份 NDA | 合同 Answerability、Groundedness、否定/例外/条款最小对 | CC BY 4.0；下载条款仍需归档 |
| [SQuAD 2.0](https://rajpurkar.github.io/SQuAD-explorer/) | 英文 Wikipedia | 约 10 万可回答 + 5 万对抗不可回答问题 | Answerability 基础；相似实体、错误数字/日期负例 | CC BY-SA 4.0；衍生分发须同许可 |
| [CMRC 2018](https://github.com/ymcui/cmrc2018) | 简体中文 Wikipedia | 约 2 万抽取式 QA | 中文 Answerability 正例和中文改写基础 | CC BY-SA 4.0 |
| [FinQA](https://finqasite.github.io/) | 英文财报/表格 | 约 2.7k 报告、8k+ QA、程序与 `gold_inds` | 金额、百分比、年份、单位、表格与可执行数值 Groundedness | 数据主页标 CC BY 4.0；涉及 FinTabNet 的部分继续逐项核验 |
| [HoVer](https://hover-nlp.github.io/) | 英文 Wikipedia 多跳 | 约 26k claims、支持文档/句 | 多跳证据缺失、实体替换、证据链断裂 | CC BY-SA 4.0；保留 Wikipedia 来源说明 |

第一批不等于“全部直接混合训练”。优先顺序是：ContractNLI 修复合同蕴含，FinQA 修复金额/日期/单位，SQuAD 2.0 与 CMRC 建立中英文 Answerability 基础，HoVer 补多跳和缺失证据。每个来源先做 1,000–5,000 条小规模转换审计，再决定是否扩量。

### 6.2 B 级：有价值，但需许可或来源复核

| 数据集 | 价值 | 主要风险 | 当前决定 |
|---|---|---|---|
| [FEVER](https://github.com/awslabs/fever) | `Supported / Refuted / NotEnoughInfo`，185,441 claims | 仓库 Apache-2.0 是代码许可，Wikipedia 数据许可未独立说明 | `review_required`；未批准前只研究 schema |
| [FEVEROUS](https://github.com/Raldir/FEVEROUS) | 87,026 claims，句子/表格/list 证据，适合数值和结构化字段 | 数据正文许可未单独清晰声明 | `review_required` |
| [RAGTruth](https://github.com/ParticleMedia/RAGTruth) | 17,790 responses、14,289 hallucination spans，直接贴近 RAG 输出审查 | 混合 CNN/DailyMail、MS MARCO、Yelp 等第三方来源；仓库 MIT 不覆盖全部源数据 | `review_required`；优先争取只使用可清权子集或只作评测 |
| [HaluEval](https://github.com/RUCAIBox/HaluEval) | QA、知识对话、摘要和日常查询，共约 35k | 种子来源许可不统一，且包含模型生成偏差 | `review_required` |
| [ConvFinQA](https://github.com/czyssrs/ConvFinQA) | 多轮金融问答，适合上下文依赖和数值推理 | 仓库代码 MIT，但数据未给出独立明确许可 | `review_required` |
| [BIPIA](https://github.com/microsoft/BIPIA) | Web/Email/Table/Summary/Code 间接提示注入；含发票组件 | 各组件许可证不同，部分要求自行从源数据生成 | 仅按组件审批；优先核验 MIT 的 OpenAI Evals invoice 子集 |
| [CUAD](https://www.atticusprojectai.org/cuad) | 510 份合同、13k+ 标签、41 类条款 | 数据集标注 CC BY 4.0，但官方不保证每份 SEC/EDGAR 原合同的原始版权 | 继续用于内部受控训练前先复核原文使用与再分发边界；不直接再分发合同全文 |
| [TruthfulQA](https://github.com/sylinrl/TruthfulQA) | 约 800 个跨 38 类日常问题，含正确/错误答案 | 没有显式检索证据，不能直接代表 RAG Groundedness | Apache-2.0；只作日常错误答案辅助/评测，不作为核心训练源 |

### 6.3 C 级：研究评测可用，商用训练排除或另行授权

| 数据集 | 价值 | 许可与决定 |
|---|---|---|
| [AVeriTeC](https://fever.ai/dataset/averitec.html) | 4,568 个真实网络 claims；包含冲突证据、证据不足和 cherry-picking | CC BY-NC 4.0；商用训练排除，研究评测可用 |
| [FaithBench](https://github.com/vectara/FaithBench) | 现代 LLM 摘要的 span-level 一致性/幻觉标注 | CC BY-NC-SA 4.0；商用训练排除 |
| [OCNLI](https://github.com/CLUEbenchmark/OCNLI) | 约 50k 原生中文 NLI，覆盖公文、新闻、文学、谈话 | CC BY-NC 2.0，部分来源另有 ELRA 条款；商用训练排除或另行授权 |
| [XNLI](https://github.com/facebookresearch/XNLI) | 15 语言 NLI，含中英文和翻译鲁棒性 | CC BY-NC 4.0；商用训练排除或另行授权 |

### 6.4 明确不采用的做法

1. 不把 FEVER、FEVEROUS、HaluEval、RAGTruth 或 BIPIA 的代码仓库许可证误当成全部数据许可；
2. 不把带 `NC` 的 AVeriTeC、FaithBench、OCNLI、XNLI 混入计划商用模型；
3. 不直接再分发 CUAD 原始合同全文；
4. 不把没有显式证据的 TruthfulQA 当作核心 Groundedness 数据；
5. 不只用随机错配制造 `UNSUPPORTED/UNGROUNDED`；
6. 不通过机器翻译把英文数据机械扩成中文主训练集；
7. 不把公开 Wikipedia/新闻事实核验替代真实办公 calibration/test。

### 6.5 推荐组合

计划商用模型第一轮只使用“许可通过”的来源：

- 合同与规则蕴含：ContractNLI；CUAD 仅在原文权利复核后使用；
- 中英文 Answerability：SQuAD 2.0 + CMRC 2018 + 现有 DuReader Robust；
- 金额、日期、单位和表格：FinQA；
- 多跳和缺失证据：HoVer；
- 日常聊天负例：保留现有 OASST1、CrossWOZ、KdConv，并用自行编写的脱敏日常最小对补充；
- 提示注入和发票：只采用 BIPIA 中许可证逐组件批准的部分，否则由本项目基于无隐私办公模板自行生成；
- RAG 输出 span 标注：RAGTruth 仅在第三方来源许可审查通过后进入训练，否则只参考其标签设计。

论文入口用于理解标签和构造，不替代许可证核验：[ContractNLI](https://aclanthology.org/2021.findings-emnlp.164/)、[SQuAD 2.0](https://arxiv.org/abs/1806.03822)、[CMRC 2018](https://aclanthology.org/D19-1600/)、[FinQA](https://arxiv.org/abs/2109.00122)、[HoVer](https://aclanthology.org/2020.findings-emnlp.309/)、[FEVER](https://aclanthology.org/N18-1074/)、[FEVEROUS](https://arxiv.org/abs/2106.05707)、[RAGTruth](https://arxiv.org/abs/2401.00396)、[HaluEval](https://arxiv.org/abs/2305.11747)、[BIPIA](https://arxiv.org/abs/2312.14197)。
<!-- DATASET_RESEARCH_END -->

## 7. 统一数据模式

所有改造后的记录使用 JSONL，正文只保存在受控训练目录。建议 schema v2：

```json
{
  "id": "stable-content-derived-id",
  "task": "answerability|groundedness",
  "label": "SUPPORTED|PARTIAL|UNSUPPORTED|GROUNDED|UNGROUNDED",
  "language": "zh|en|mixed",
  "domain": "contract|policy|invoice|procurement|travel|hr|public_service|daily_chat|other",
  "question": "...",
  "evidence": [
    {"source_id": "S1", "document_id": "...", "text": "..."}
  ],
  "answer": "...",
  "atomic_claims": [
    {"text": "...", "support": "entailed|contradicted|missing", "source_ids": ["S1"]}
  ],
  "hard_negative_type": "NONE|WRONG_AMOUNT|WRONG_DATE|WRONG_ENTITY|WRONG_UNIT|NEGATION|SCOPE|MISSING_FIELD|MIXED_SUPPORT|FALSE_CITATION|CROSS_DOCUMENT|STALE_VERSION|DOCUMENT_PROMPT_INJECTION|DAILY_CHAT_IRRELEVANT",
  "mutation_family_id": "...",
  "source_dataset": "...",
  "source_version": "...",
  "source_record_id": "...",
  "source_license": "...",
  "document_id": "...",
  "conversation_id": "...",
  "split": "train|calibration|test|regression",
  "distribution": "public_licensed|synthetic_derived|real_office_redacted",
  "redaction_status": "public_source_reviewed|reviewed|not_applicable",
  "provenance": {
    "raw_sha256": "...",
    "transform_version": "...",
    "generator_commit": "..."
  }
}
```

实现时 Groundedness 的 `label` 枚举仍严格为 `GROUNDED/PARTIAL/UNGROUNDED`；上述联合 schema 的校验器必须按 `task` 限定合法标签。

## 8. 数据改造方案

### 8.1 先保留原始可支持样本

从 QA、NLI、对话依据和事实一致性数据中抽取原始问题、证据、答案、文档 ID 和原标签。不可追溯到原始文档或许可不明确的记录不得进入训练主集。

### 8.2 构造 Answerability 三分类

- `SUPPORTED`：证据覆盖问题的全部核心槽位；
- `PARTIAL`：多子问题只覆盖一部分，或关键限定条件缺失；
- `UNSUPPORTED`：证据与问题仅主题相似、发生矛盾、来自错误实体/版本，或完全无关。

每条 `SUPPORTED` 至少生成一个高相似 `PARTIAL` 或 `UNSUPPORTED` 最小对，但训练/评测配比按困难类型控制，不机械扩增所有记录。

### 8.3 构造 Groundedness 三分类

以原子断言为单位对齐证据。优先使用已有人工蕴含/事实一致性标签；弱监督生成的样本必须经过确定性校验或人工抽检。

- `GROUNDED`：所有核心断言均被证据支持，允许不改变事实的同义改写；
- `PARTIAL`：至少一个断言被支持，且至少一个断言缺失或矛盾；
- `UNGROUNDED`：没有核心断言得到支持，或回答的唯一核心结论与证据矛盾。

### 8.4 最小对变异器

变异必须记录原值、新值、跨度和校验结果：

1. 数字：整数、小数、百分比、正负号、数量级；
2. 金额：币种、含税/未税、上限/实际值；
3. 日期：签署/生效/截止/审批日期及相对日期；
4. 实体：公司、部门、负责人、地点、产品；
5. 单位：天/工作日、元/万元、kg/g、小时/分钟；
6. 否定：可以/不得、包含/排除、已批准/未批准；
7. 范围：至少/至多、全部/部分、仅限/包括；
8. 引用：正确内容配错误 source ID、无来源断言、跨文档拼接；
9. 版本：现行制度与旧版制度冲突；
10. 文档指令：证据正文包含“忽略问题/系统规则”等不可信指令。

每个变异后的错误样本必须与原正确样本一起保留为 `mutation_family_id` 相同的对，但整个族只能进入一个 split。

### 8.5 中英文和日常聊天

- 原生中文和原生英文优先，不以机器翻译替代全部目标语言；
- 翻译样本标记翻译引擎/版本，并抽检数字、否定、专名和时间；
- 保留中文、英文和 mixed 三组指标；
- 日常问候、写作、翻译和开放聊天与无关证据配对，用于 `UNSUPPORTED`，但其数量不能淹没办公困难负例；
- 同一语义的中英文对不得跨 split。

### 8.6 去重与防泄漏

切分单位不能只看 `document_id`，还要同时约束：

- 原始文档；
- 对话；
- QA/摘要来源记录；
- 最小对变异族；
- 模板族；
- 机器翻译族；
- 近重复簇。

先规范化文本并做精确 SHA-256 去重，再使用 MinHash/字符 n-gram 找近重复。若任一族成员进入 test，该族其他成员不得进入 train/calibration。

## 9. 建议数据配比

最终数量由许可核验和去重结果决定，不为追求规模强行补齐。第一轮目标是每任务约 100,000–160,000 条高质量记录，建议按机制配比：

| 数据族 | 目标比例 | 目的 |
|---|---:|---|
| 原始可支持/有依据正例 | 25% | 保持正常放行召回 |
| 数字、金额、日期、单位最小对 | 20% | 修复当前真机阻塞 |
| 实体、否定、范围、版本最小对 | 15% | 提升细粒度蕴含 |
| 多断言 PARTIAL 与字段缺失 | 15% | 学习真实 PARTIAL 边界 |
| 跨文档、伪引用、提示注入 | 10% | 安全与来源一致性 |
| 普通日常对话无关证据 | 10% | 避免强制套用知识库 |
| 长证据、混合语言和截断压力 | 5% | 覆盖端侧输入约束 |

中英文不再要求每个标签绝对相等，而是分别设置最低覆盖量并保留真实分布权重。训练可使用采样权重平衡小类别；calibration/test 应尽量反映预期办公分布，并单独报告每个语言和困难类型。

## 10. 数据质量与人工复核

### 10.1 自动检查

- schema、枚举、非空字段和唯一 ID；
- 原始与改造数据的哈希、版本、许可证元数据；
- 文档/对话/变异族/近重复簇跨 split 泄漏；
- 数字、日期、实体变异前后只改变预期槽位；
- source ID 必须存在且引用文本可追溯；
- 手机号、身份证号、邮箱、地址和真实姓名检测；
- 归档路径穿越、符号链接、压缩炸弹和超长行；
- 不执行数据集自带代码或不可信反序列化。

### 10.2 人工复核

- 每个来源与困难类型至少抽检 100 条或该组的 5%，取较大者；
- 全量复核 calibration、test 和 regression；
- PARTIAL、提示注入、伪引用和跨证据冲突优先双人复核；
- 争议样本进入 `REVIEW`，不直接训练；
- 记录 reviewer、规则版本和最终裁决，不记录未经脱敏的个人信息。

### 10.3 真实办公数据

公开数据不能替代真实办公最终测试。需要用户授权后，在受控目录准备人工脱敏且文档级隔离的：

- `office_calibration.jsonl`：只选阈值/温度；
- `office_test.jsonl`：只做一次最终验收；
- `office_regression.jsonl`：保存已发现的失败机制，不参与阈值选择。

真实正文和可识别信息不得提交 Git；Git 只保存 schema、生成器、聚合统计和哈希清单。

## 11. 训练计划

### Phase 0：冻结基线与验收集

1. 固定 v3 checkpoint、INT8、tokenizer、训练数据 manifest 和所有现有指标；
2. 将当前金额/日期真机失败矩阵登记为不可修改标签的 regression；
3. 冻结公开文档级 test 和真实办公 test；
4. 在训练前输出按来源、语言、标签、困难类型、文档族的统计。

退出条件：基线可复现，任何 test 正文均未参与数据构造参数选择。

### Phase 1：数据构建与一次性质量闸门

1. 下载许可已批准的固定版本原始归档到受控训练目录；
2. 生成 provenance manifest 和 SHA-256；
3. 转换为 schema v2；
4. 构造最小对、PARTIAL 和安全困难样本；
5. 去重、族级切分、隐私扫描与人工抽检；
6. 输出数据卡和拒绝清单。

退出条件：无跨 split 泄漏、无未裁决许可、关键困难类型均达到最低覆盖量。

### Phase 2：先做数据消融，不立即更换基础模型

仍固定 `intfloat/multilingual-e5-small` revision `614241f622f53c4eeff9890bdc4f31cfecc418b3`，只比较三组：

1. v3 原始数据基线；
2. v3 + 数字/日期/实体最小对；
3. 完整重构数据。

每组使用相同 seed、epoch、batch、最大长度和学习率。只运行预先定义的一轮消融，不反复试探最终 test。

选择规则：先满足两任务及困难组硬门槛，再比较最差困难组召回率和 ECE，不再按两个头平均分直接选模。

### Phase 3：修正训练目标和输入预算

若 Phase 2 仍失败，按以下顺序一次只改一项：

1. 对 Groundedness 与困难负例加权，避免 Answerability 主导共享编码器；
2. 使用 group-aware sampler，保证每批包含正例及其最小对；
3. 为 query/evidence/answer 分配明确 token 预算并做截断审计；
4. 评估对比损失或 margin loss，使正例与最小错例的 logit 间隔不低于预设值；
5. 只有上述方案仍失败时，比较更适合 cross-encoder/NLI 的多语言小模型。

不在同一轮同时更换数据、基础模型和量化方式，以保证能定位收益来源。

### Phase 4：校准与 FP32 冻结评测

- Answerability 在独立 calibration 上选择满足 precision 不低于 0.95 时 recall 最高的阈值；
- Groundedness 采用温度缩放或等价的单调校准，仅用 calibration 拟合；
- test 只执行一次冻结评测；
- 输出总体、语言、来源、领域、标签和困难类型分组指标。

发布前至少满足：

$$
\mathrm{Precision}_{answerability}\ge 0.95,\qquad
\mathrm{Recall}_{answerability}\ge 0.90
$$

$$
\mathrm{MacroF1}_{groundedness}\ge 0.85,\qquad
\mathrm{ECE}_{groundedness}\le 0.10
$$

同时要求金额、日期、实体、否定、范围、字段缺失、伪引用和提示注入各组达到预先登记的最低召回率；建议初始门槛为 0.90，最终由样本量置信区间确认。

### Phase 5：INT8 导出与量化校准

1. 使用覆盖所有困难类型和长度桶的 calibration 子集；
2. 导出 FP32 ONNX 并先验证 PyTorch/ONNX 等价；
3. 导出 INT8，比较总体和分组标签；
4. 失败时优先扩大代表性校准和增加训练 margin，再考虑静态量化、选择性量化或保留分类头 FP16/FP32；
5. 不得直接放宽现有量化门槛。

冻结门槛：

$$
\mathrm{Agreement}_{INT8,FP32}\ge 0.995
$$

$$
\max_g\left(F1^{FP32}_g-F1^{INT8}_g\right)\le 0.01
$$

其中 (g) 包含任务、语言和关键困难类型，而不只总体 split。

### Phase 6：真机发布矩阵

仅对通过离线门槛的固定 SHA 模型执行：

- 正确/错误金额、日期、实体、单位、否定和范围最小对；
- PARTIAL、伪引用、跨文档、旧版本和文档提示注入；
- 中文、英文、mixed；
- 1、10、40、500 和大库规模不变性；
- 0/10/30 轮会话、后台恢复、取消、模型缺失和哈希错误降级；
- 模型打开时间、P50/P95、PSS、连续运行和低内存恢复。

当前已完成且与模型无关的 UI/生命周期测试不重复；只跑 Guard 重训直接关联矩阵及必要回归。

### Phase 7：生产固化

仅当 FP32、INT8、公开预资格、真实办公独立测试和真机矩阵全部通过时：

1. 固定模型、tokenizer、数据 manifest、阈值/profile 和代码 commit；
2. 将 Guard SHA 与 production profile 绑定；
3. 更新 README、统一进度文档、模型卡和 graphify；
4. 提交模型链接或受控发布产物，不提交受限原始数据；
5. 保留技术故障时恢复 checkpoint 并走普通回答的策略；模型明确判定内容冲突时，纠偏后仍失败则使用带来源编号的知识库摘录，不显示冲突提示。

## 12. 需要新增或调整的文件（后续实施，不在本阶段创建）

- `tools/rag_guard/dataset_schema_v2.py`
- `tools/rag_guard/build_guard_dataset_v4.py`
- `tools/rag_guard/mutations/amount_date.py`
- `tools/rag_guard/mutations/entity_scope.py`
- `tools/rag_guard/mutations/citation_injection.py`
- `tools/rag_guard/deduplicate_and_split.py`
- `tools/rag_guard/audit_dataset_v4.py`
- `tools/rag_guard/train_v4.py`
- `tools/rag_guard/evaluate_slices.py`
- `tools/rag_guard/data/dataset_registry_v4.json`
- `tools/rag_guard/DATASET_CARD_V4.md`
- `tools/rag_guard/TRAINING_RUN_V4.md`
- `docs/execution/evidence/groundedness-release-matrix-v4.md`

现有 `regression_seed.jsonl` 保持 test-only；新增训练数据必须是同一失败机制的不同文档、不同数值和不同表述，不能把冻结回归样本原文复制进 train。

## 13. 执行顺序与停止条件

严格按以下顺序执行：

1. 许可审批与数据登记；
2. schema 和自动检查测试；
3. 转换器测试；
4. 最小对校验和人工抽检；
5. 族级切分与泄漏审计；
6. 数据消融训练；
7. FP32 冻结评测；
8. INT8 导出和分组量化评测；
9. 真机直接关联矩阵；
10. 固定 profile 与发布文档。

出现以下任一情况立即停止，不进入下一阶段：

- 许可证或商业/衍生使用权不明确；
- calibration/test 与 train 存在文档、对话、变异族或近重复泄漏；
- 真实办公数据未授权或未完成脱敏复核；
- Groundedness 金额/日期最小对仍高置信误放行；
- INT8 通过总体指标但关键困难组退化；
- 通过放宽门槛或修改 test 标签获得“通过”。

## 14. 本计划完成后的预期结果

本计划的目标不是让总体 macro-F1 更漂亮，而是让端侧模型在保持低延迟的同时，可靠区分：

- 真正有完整证据的问题与仅主题相似的问题；
- 完全有依据、部分有依据和无依据回答；
- 正确回答与只错一个金额、日期、实体、单位或限定词的高相似回答；
- 普通日常聊天与需要知识库支持的回答；
- 正常文档内容与伪引用、跨文档串线及文档提示注入。

在完成真实办公独立验收和固定生产 profile 前，当前 Guard 继续保持实验状态，不宣称稳定完成。
