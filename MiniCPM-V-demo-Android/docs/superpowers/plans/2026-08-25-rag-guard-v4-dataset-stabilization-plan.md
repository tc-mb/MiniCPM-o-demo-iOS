# RAG Guard v4 Dataset Stabilization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除 Groundedness `CONTRADICTED` 在否定模板、来源和语言上的切片失衡，构建可审计的四类 contrast family，并用训练动态隔离歧义样本后重新训练。

**Architecture:** 保留 schema v2、原始数据许可、冻结 test 和现有发布门槛。新增独立的分布审计模块作为 fail-closed release gate；构造阶段生成多种事实冲突，选择阶段按 label、hard type、来源和语言做确定性 family 配额；训练阶段只记录 calibration 动态并输出隔离清单，不修改冻结 test。

**Tech Stack:** Python 3、JSONL、PyTorch 2.4.1、Transformers 4.53.3、Safetensors、unittest/pytest、Graphify。

---

## 2026-08-25 执行进度

- Task 1 已完成：release balance gate 已接入并在真实旧数据上按预期拒绝否定模板失衡。
- Task 2 已完成：新增错误实体、金额、日期、单位、范围和多跳矛盾候选；合同矛盾补齐 GROUNDED sibling。
- Task 3 已完成：按 `(hard_type, language)` 精确配额，矛盾中文 10,000、英文 27,500，sibling 覆盖 100%。
- Task 4 已完成：训练动态 recorder 已接入 calibration，远端 PyTorch 测试通过。
- Task 5 进行中：冻结 test 和全量审计已通过，稳定化 4 epoch 训练已启动。

### Task 1: Groundedness 切片分布硬门禁

**Files:**
- Create: `tools/rag_guard/dataset_balance_v4.py`
- Create: `tools/rag_guard/test_dataset_balance_v4.py`
- Modify: `tools/rag_guard/audit_dataset_v4.py`
- Modify: `tools/rag_guard/test_dataset_audit_v4.py`

- [ ] **Step 1: Write failing distribution tests**

测试必须证明：`NEGATION_FLIP` 超过 `CONTRADICTED` 的 35%、任一来源超过 55%、中文不足 25%、或具备 GROUNDED/CONTRADICTED 最小对的 family 不足 70% 时，release gate 拒绝数据。

```python
summary = summarize_groundedness(rows)
with self.assertRaisesRegex(ValueError, "negation share"):
    validate_groundedness_balance(summary, RELEASE_POLICY)
```

- [ ] **Step 2: Verify RED**

Run: `python -m unittest tools.rag_guard.test_dataset_balance_v4 -v`

Expected: FAIL because `dataset_balance_v4` does not exist.

- [ ] **Step 3: Implement deterministic summary and validation**

`DatasetBalancePolicy` 固定保存 `max_negation_share=0.35`、`max_source_share=0.55`、`min_zh_share=0.25`、`min_paired_contradicted_share=0.70`。`summarize_groundedness()` 输出 label、hard type、source、language 和 family 计数；`validate_groundedness_balance()` 返回报告或抛出具体 `ValueError`。

- [ ] **Step 4: Wire the release audit**

`audit_dataset_v4.py` 新增 `--profile smoke|release`，默认 `release`。release 对 Groundedness 执行分布门禁；smoke 仅执行 schema、隐私、许可和泄漏检查。

- [ ] **Step 5: Verify GREEN**

Run: `python -m unittest tools.rag_guard.test_dataset_balance_v4 tools.rag_guard.test_dataset_audit_v4 -v`

Expected: PASS.

### Task 2: 扩展事实冲突构造器

**Files:**
- Modify: `tools/rag_guard/mutations/amount_date.py`
- Modify: `tools/rag_guard/mutations/entity_scope.py`
- Create: `tools/rag_guard/mutations/unit_scope.py`
- Modify: `tools/rag_guard/build_full_corpus_v4.py`
- Modify: `tools/rag_guard/test_build_full_corpus_v4.py`

- [ ] **Step 1: Write failing minimal-pair tests**

同一 family 必须能生成 `WRONG_ENTITY`、`WRONG_AMOUNT`、`WRONG_DATE`、`WRONG_UNIT`、`SCOPE_FLIP`，且每个 CONTRADICTED 都存在 GROUNDED sibling；修改只能命中一个明确 span。

- [ ] **Step 2: Verify RED**

Run: `python -m unittest tools.rag_guard.test_build_full_corpus_v4 -v`

Expected: FAIL on missing hard types.

- [ ] **Step 3: Implement bounded mutations**

金额和日期只修改带上下文边界的单一 span；实体替换要求原实体恰好出现一次；单位只允许在固定映射表中替换；范围变异只处理明确的 `must/may/not/unless/仅/不得/可以` 结构。无法证明变异改变事实时不生成样本。

- [ ] **Step 4: Build complete contrast families**

每个选中的事实基础行生成 GROUNDED、PARTIAL、UNSUPPORTED 和至少一个 CONTRADICTED sibling，并共享 `mutation_family_id`、`document_id` 和证据。

- [ ] **Step 5: Verify GREEN**

Run: `python -m unittest tools.rag_guard.test_build_full_corpus_v4 tools.rag_guard.test_build_groundedness_v4 -v`

Expected: PASS.

### Task 3: 确定性切片与 family 均衡选择

**Files:**
- Create: `tools/rag_guard/select_balanced_corpus_v4.py`
- Create: `tools/rag_guard/test_select_balanced_corpus_v4.py`
- Modify: `tools/rag_guard/build_full_corpus_v4.py`

- [ ] **Step 1: Write failing quota tests**

反例目标为：否定 25%–30%、错误实体 20%–25%、金额/日期/单位 20%–25%、合同范围 15%–20%、多跳/引用 10%–15%；任一来源不得超过 50%，中文不得低于 25%。相同 seed 和反向输入必须产生相同 ID 集合。

- [ ] **Step 2: Verify RED**

Run: `python -m unittest tools.rag_guard.test_select_balanced_corpus_v4 -v`

Expected: FAIL because the selector does not exist.

- [ ] **Step 3: Implement family-first selection**

先以 family 为单位按 SHA-256 排序，再依次满足 hard type、语言、来源和 label 配额；不拆分 family，不通过复制样本补足配额，候选不足时 fail closed 并报告缺口。

- [ ] **Step 4: Verify GREEN**

Run: `python -m unittest tools.rag_guard.test_select_balanced_corpus_v4 -v`

Expected: PASS.

### Task 4: 训练动态与歧义隔离

**Files:**
- Create: `tools/rag_guard/training_dynamics_v4.py`
- Create: `tools/rag_guard/test_training_dynamics_v4.py`
- Modify: `tools/rag_guard/train.py`

- [ ] **Step 1: Write failing dynamics tests**

每个 calibration/train row 记录每轮 gold probability、预测标签、margin 和 flip count；低平均置信度或高波动样本进入 `review.jsonl`，正文不写日志。

- [ ] **Step 2: Verify RED**

Run: `python -m unittest tools.rag_guard.test_training_dynamics_v4 -v`

Expected: FAIL because the module does not exist.

- [ ] **Step 3: Implement bounded aggregation**

聚合器以 row ID 为键，只存浮点统计和标签；正文仍从冻结 JSONL 读取。隔离文件通过临时文件原子替换，重复运行结果确定。

- [ ] **Step 4: Verify GREEN**

Run: `python -m unittest tools.rag_guard.test_training_dynamics_v4 tools.rag_guard.test_training_pipeline -v`

Expected: PASS.

### Task 5: 重建、审计与受控重训

**Files:**
- Modify: `tools/rag_guard/TRAINING_RUN_V4.md`
- Generated outside Git: `D:\MiniCPM-V\private-training\rag-guard-v4-stable\`

- [ ] **Step 1: Regenerate without changing frozen test**

新数据写入独立目录；冻结 test 的 row IDs 和 SHA-256 必须与当前版本相同。新增 family 只进入 train/calibration，并按 document、mutation、translation 和 near-duplicate family 隔离。

- [ ] **Step 2: Run full audits**

Run: `python -m tools.rag_guard.audit_dataset_v4 --profile release --registry tools/rag_guard/data/dataset_registry_v4.json --input-dir D:\MiniCPM-V\private-training\rag-guard-v4-stable\splits --pattern all_*.jsonl --report D:\MiniCPM-V\private-training\rag-guard-v4-stable\dataset-audit.json`

Expected: privacy/license/schema/leakage and distribution gates all pass.

- [ ] **Step 3: Train one fixed run**

固定 base revision、seed 42、max length 256、batch 16、gradient accumulation 2、learning rate `2e-5`；最多 4 epoch，使用 calibration 选模，test 只评估一次。

- [ ] **Step 4: Record results and update Graphify**

记录数据哈希、切片分布、参数、每轮 calibration、最终 test、模型哈希和异常；运行 `graphify update .`。
