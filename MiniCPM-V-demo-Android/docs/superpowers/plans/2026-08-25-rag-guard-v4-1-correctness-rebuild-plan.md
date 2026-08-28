# RAG Guard v4.1 Correctness Rebuild Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复候选答案被截断、HoVer 二合一负例误映射、标签模板捷径和困难类型覆盖不完整的问题，生成独立可审计的 v4.1 数据并完成端侧可用的三分类加四分类模型。

**Architecture:** 保留 Answerability 三分类和 Groundedness 四分类外部契约。编码层改为受保护句对：问题与候选答案永远完整保留，只有证据允许按预算截断；数据层只把可证明的显式反驳标为 `CONTRADICTED`，旧 v4 数据和冻结测试只归档不覆盖。训练先以当前 E5 为控制组，再与同体量 NLI 初始化模型对照，最终用校准集锁定高精度冲突阈值。

**Tech Stack:** Python 3.10+、PyTorch 2.4.1、Transformers 4.53.3、Safetensors、JSONL、pytest/unittest、ONNX Runtime、Android Kotlin、Graphify。

---

## 2026-08-26 execution status

- Tasks 1–5 are complete. The independent v4.1 corpus and split audits pass.
- Local regression: 122 passed, 6 skipped only because local PyTorch is not installed.
- Training host is disconnected by user instruction. Execution is intentionally paused before Task 6 Step 1.
- v4 and `rag-guard-v4-stable` remain unchanged; v4.1 lives under `D:\MiniCPM-V\private-training\rag-guard-v4-1`.

### Task 1: Protected pair tokenization

**Files:**
- Modify: `tools/rag_guard/training_data.py`
- Modify: `tools/rag_guard/train.py`
- Test: `tools/rag_guard/test_training_pipeline.py`

- [x] **Step 1: Write the failing token-visibility tests**

构造超过 256 token 的证据，断言 Groundedness 的问题和候选答案 token 全部存在，只有 evidence 被截断；Answerability 同样完整保留 query。

- [x] **Step 2: Run RED**

Run: `python -m pytest tools/rag_guard/test_training_pipeline.py -q`

Expected: FAIL because the current flattened string places `answer:` after long evidence.

- [x] **Step 3: Implement pair fields and evidence-only truncation**

`format_model_pair_v4()` 返回受保护文本和证据文本；`EncodedRows` 使用 tokenizer pair API，并将 `truncation="only_second"`。如果受保护文本自身超过预算，fail closed，不静默截断。

- [x] **Step 4: Run GREEN and regression tests**

Run: `python -m pytest tools/rag_guard/test_training_pipeline.py tools/rag_guard/test_training_data.py -q`

Expected: PASS.

### Task 2: Correct HoVer and synthetic label semantics

**Files:**
- Modify: `tools/rag_guard/build_full_corpus_v4.py`
- Modify: `tools/rag_guard/claim_labeling.py`
- Test: `tools/rag_guard/test_build_full_corpus_v4.py`
- Test: `tools/rag_guard/test_v4_label_contract.py`

- [x] **Step 1: Write failing semantic tests**

断言 HoVer `NOT_SUPPORTED` 不得仅凭二合一标签进入 `CONTRADICTED`；`PARTIAL` 必须包含真实支持与缺失原子断言；`UNSUPPORTED` 不得使用固定元描述句；错误实体只有在唯一关系可证明时才能进入冲突类。

- [x] **Step 2: Run RED**

Run: `python -m pytest tools/rag_guard/test_build_full_corpus_v4.py tools/rag_guard/test_v4_label_contract.py -q`

Expected: FAIL on HoVer mapping and fixed synthetic answers.

- [x] **Step 3: Implement fail-closed mappings**

HoVer 发布版负例从 Groundedness 冲突语料中移除；HoVer 正例仍生成 GROUNDED、缺 hop 的 PARTIAL/UNSUPPORTED，并从可靠正例生成单事实最小冲突。QA 生成器用自然、多断言候选替代固定元句式。

- [x] **Step 4: Run GREEN**

Run: `python -m pytest tools/rag_guard/test_build_full_corpus_v4.py tools/rag_guard/test_v4_label_contract.py -q`

Expected: PASS.

### Task 3: Complete pair and hard-slice coverage

**Files:**
- Modify: `tools/rag_guard/train.py`
- Modify: `tools/rag_guard/evaluate_slices.py`
- Test: `tools/rag_guard/test_training_pipeline.py`
- Test: `tools/rag_guard/test_evaluate_slices.py`

- [x] **Step 1: Write failing coverage tests**

断言 `WRONG_UNIT`、`SCOPE_FLIP`、`MULTI_HOP_CONTRADICTION`、`CONTRACT_CONTRADICTION` 与既有四类全部进入 pair sampler 和 hard-slice 指标；一个 family 的多个冲突 sibling 不得永远只选第一个。

- [x] **Step 2: Run RED**

Run: `python -m pytest tools/rag_guard/test_training_pipeline.py tools/rag_guard/test_evaluate_slices.py -q`

Expected: FAIL because only four hard types are currently eligible.

- [x] **Step 3: Implement complete deterministic rotation**

统一困难类型常量，按 epoch 确定性轮换 family 内的冲突 sibling，并让发布报告覆盖全部存在的困难切片。

- [x] **Step 4: Run GREEN**

Run: `python -m pytest tools/rag_guard/test_training_pipeline.py tools/rag_guard/test_evaluate_slices.py -q`

Expected: PASS.

### Task 4: Dataset correctness gates

**Files:**
- Create: `tools/rag_guard/dataset_correctness_v4.py`
- Create: `tools/rag_guard/test_dataset_correctness_v4.py`
- Modify: `tools/rag_guard/audit_dataset_v4.py`

- [x] **Step 1: Write failing audit tests**

Release profile 必须拒绝候选答案不可见、HoVer 二合一负例直接标冲突、任一固定候选句支配单类、以及来源与标签完全绑定的数据。

- [x] **Step 2: Run RED**

Run: `python -m pytest tools/rag_guard/test_dataset_correctness_v4.py -q`

Expected: FAIL because the correctness gate does not exist.

- [x] **Step 3: Implement deterministic summaries and gates**

报告 `protected_input_visible`、模板最大占比、`source x label` 覆盖和不可信映射计数；release profile fail closed，smoke profile只报告。

- [x] **Step 4: Run GREEN**

Run: `python -m pytest tools/rag_guard/test_dataset_correctness_v4.py tools/rag_guard/test_dataset_audit_v4.py -q`

Expected: PASS.

### Task 5: Build v4.1 without overwriting v4

**Files:**
- Modify: `tools/rag_guard/TRAINING_RUN_V4.md`
- Generated outside Git: `D:\MiniCPM-V\private-training\rag-guard-v4-1\`

- [x] **Step 1: Generate a new candidate corpus**

输出必须写入 `rag-guard-v4-1`，不得修改 `rag-guard-v4-stable`；生成器 commit、配置、输入哈希和六个 split 哈希写入 manifest。

- [x] **Step 2: Run schema, privacy, license, leakage, balance and correctness audits**

Expected: all release gates pass and protected query/answer visibility is 100%.

- [x] **Step 3: Freeze v4.1 test**

先完成标签抽样验收，再冻结 row IDs 与 SHA-256；v4 历史测试继续保留但不再作为 v4.1 发布门槛。

### Task 6: Controlled training and model comparison

**Files:**
- Modify: `tools/rag_guard/train.py`
- Modify: `tools/rag_guard/TRAINING_RUN_V4.md`
- Generated outside Git: training runs and model weights

- [x] **Step 1: Run one-epoch E5 smoke training**

确认中文、长证据和新增困难切片不再系统失败；若输入可见性或标签审计失败，停止，不继续烧 GPU。

2026-08-26 结果：运行完成且冻结 test 未读取。Groundedness macro-F1 `0.922468`、冲突 precision/recall `0.909976/0.802575`；但 `WRONG_ENTITY` recall `0.455670`、`WRONG_DATE` recall `0.755556`，checkpoint 不具备发布资格。用户随后要求暂停五轮运行，五轮任务在首个 epoch 完成前已停止且未产生 checkpoint。

后续逐条审计发现：`WRONG_ENTITY` 是同段落任意其他答案，并非类型约束实体替换；英文月份名和裸年份大量误入 `WRONG_AMOUNT`。详细证据见 `tools/rag_guard/SMOKE_ERROR_AUDIT_V4_1.md`。当前重新启动的五轮实验只改变 epoch，完成前不修改生成器；归档曲线后再建立新数据版本修复困难类型语义。

五轮诊断已完成：关系绑定 recall 从第 1 轮 `0.432990` 升至第 5 轮 `0.800000`，但声明日期在 `0.688889–0.777778` 间震荡。自动排名保存 epoch 4 checkpoint，其 Groundedness macro-F1 `0.952547`、冲突 precision/recall `0.935000/0.902897`，仍不满足 release gate；冻结 test 未读取。下一步不是继续追加 epoch，而是建立新 transform 版本修复困难类型生成语义，再做 E5/NLI A/B。

- [ ] **Step 2: Run matched two-epoch A/B training**

控制组使用固定 revision 的 `multilingual-e5-small`；实验组使用固定 revision 的 `multilingual-MiniLMv2-L6-mnli-xnli`。数据、seed、batch、token budget 和优化器完全一致。

- [ ] **Step 3: Select the architecture before full training**

只用 calibration，比较 Answerability macro-F1、Groundedness macro-F1、冲突 precision-recall、中文/英文差距、全部困难切片和端侧预算；test 不参与选择。

- [ ] **Step 4: Train the winner for at most four epochs**

每轮保存诊断 checkpoint；不降低发布门槛，不以追加 epoch 代替根因修复。

### Task 7: Calibrate, export and deploy

**Files:**
- Modify: `tools/rag_guard/quality_gate.py`
- Modify: Android guard model manifest and policy files after model acceptance

- [ ] **Step 1: Lock a selective contradiction threshold**

在 calibration 上最大化冲突 recall，同时要求 95% 精确率置信下界不低于 0.98，并设置非零最低 recall，防止空选择器通过。

- [ ] **Step 2: Evaluate frozen v4.1 test exactly once**

记录总体、语言、来源、长度和困难类型指标；不再根据 test 回调阈值。

- [ ] **Step 3: Export and verify ONNX**

对 PyTorch/ONNX logits、label mapping、tokenization 和阈值做逐样本一致性测试。

- [ ] **Step 4: Integrate Android and run signed-device acceptance**

只有证据已接受且冲突概率超过锁定阈值时替换候选回答；其他情况保持正常聊天。构建和安装前执行 `verifyInstallationSigning`，不得卸载应用。

### Task 8: Documentation and knowledge graph

**Files:**
- Modify: `tools/rag_guard/TRAINING_RUN_V4.md`
- Modify: `docs/superpowers/plans/2026-08-18-minicpm-android-unified-progress-plan.md`
- Modify: `graphify-out/*`

- [x] **Step 1: Record hashes, metrics and exceptions**

- [x] **Step 2: Run `graphify update .` for code changes**

- [ ] **Step 3: Refresh semantic extraction for modified plans/docs**

- [x] **Step 4: Run `graphify check-update .` and preserve all persistent graph artifacts**
