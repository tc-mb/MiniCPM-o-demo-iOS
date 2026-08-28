# RAG Guard Answerability 三分类与 Groundedness 四分类 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 训练并部署一个端侧双头 Guard：Answerability 使用 `SUPPORTED/PARTIAL/UNSUPPORTED` 三分类决定是否调用知识库；Groundedness 使用 `GROUNDED/PARTIAL/UNSUPPORTED/CONTRADICTED` 四分类决定显示、重生成、正常聊天或知识库替换。

**Architecture:** 保留固定 revision 的 `intfloat/multilingual-e5-small` 共享编码器，Answerability 头输出 3 类，Groundedness 头输出 4 类。ONNX 对外统一输出 4 logits：Answerability 的第 4 位使用固定极小值填充，Android 按 manifest 的任务标签数切片。语义判断与动作策略分离；只有高置信 `CONTRADICTED` 才强制知识库替换，`UNSUPPORTED` 必须恢复原问题并普通聊天。

**Tech Stack:** Python 3、PyTorch 2.4.1+cu121、Transformers 4.53.3、Safetensors、ONNX Runtime、Kotlin、Android Room/SQLCipher、llama.cpp-omni checkpoint、JUnit、Android instrumented tests、Graphify。

## 0. 2026-08-24 执行状态

- Task 1–5 的标签隔离、schema v2、构造器、最小对、去重切分和 fail-closed 审计已实现并通过回归。
- Task 6–7 的 3+4 双头代码、四维输出、任务独立损失、困难对采样和硬门槛选模已实现；本机缺少 PyTorch/Transformers，因此张量级测试保留但跳过，必须在训练主机训练前执行。
- 新增 `prepare_training_v4.py`，原始文件、大小、SHA-256、许可或用户条款任一未满足时禁止进入 Task 8。
- Task 8 的初始边界曾是手动下载官方原始数据并由用户本人接受 ContractNLI 条款；该边界现已通过。
- 2026-08-24 后续进展：Task 8 的原始数据固化、完整 schema v2 语料生成、隐私/许可审计、近重复族级切分已完成；Answerability 120,000 行、Groundedness 150,000 行，train/calibration/test 为 242,436/13,793/13,771。消融和模型训练仍未开始。转换代码未提交，正式训练前必须以最终 commit 再生并冻结哈希。
- Android Task 9–15 不提前修改，以免未训练 v4 模型与现有 v3 App 契约错配。

---

## 1. 标签与产品动作契约

### 1.1 Answerability 三分类

输入为“用户问题 + 检索候选证据”，不包含模型回答。

| 标签 | 严格定义 | App 动作 |
|---|---|---|
| `SUPPORTED` | 证据可以完整回答全部必要字段，包括明确的否定回答 | 达到冻结阈值后注入知识库 |
| `PARTIAL` | 至少一个必要字段可回答，但仍有必要字段缺失 | 不注入，原问题普通聊天 |
| `UNSUPPORTED` | 证据不能决定任何核心字段；仅主题相似也属于此类 | 不注入，原问题普通聊天 |

证据明确说明“不得自动续期”可以完整回答“是否允许自动续期”，因此属于 `SUPPORTED`。不得把 NLI 的 `Contradiction` 机械映射为 Answerability `UNSUPPORTED`。

### 1.2 Groundedness 四分类

输入为“用户问题 + 冻结证据 + 隐藏候选回答”。候选回答拆为原子断言集合 (C=\{c_1,\ldots,c_n\})，每条断言标为 `entailed/missing/contradicted`。

| 标签 | 严格定义 | App 动作 |
|---|---|---|
| `GROUNDED` | 所有必要断言均受支持，没有缺失或冲突 | 显示回答和引用 |
| `PARTIAL` | 至少一条必要断言受支持，至少一条缺证据，且没有明确冲突 | 同证据重生成一次；再次不完整则普通聊天 |
| `UNSUPPORTED` | 没有必要断言受支持，且证据没有明确反驳核心断言 | 立即丢弃 RAG 候选，普通聊天 |
| `CONTRADICTED` | 至少一条重要事实断言被证据明确反驳 | 丢弃模型草稿，直接以知识库摘录替换 |

聚合严重程度固定为：

$$
\mathrm{CONTRADICTED} > \mathrm{PARTIAL} > \mathrm{UNSUPPORTED} > \mathrm{GROUNDED}
$$

回答中三项正确、一项金额错误，仍标为 `CONTRADICTED`。

### 1.3 最终状态机

```text
Answerability.SUPPORTED + threshold pass
  -> 注入证据 -> 生成隐藏候选 -> Groundedness

Answerability.PARTIAL / UNSUPPORTED / low confidence / technical failure
  -> 不注入证据 -> 原问题普通聊天

Groundedness.GROUNDED + threshold pass
  -> 显示回答与引用

Groundedness.PARTIAL + threshold pass
  -> 同证据重生成一次
  -> GROUNDED: 显示
  -> CONTRADICTED: 知识库替换
  -> PARTIAL / UNSUPPORTED: 普通聊天

Groundedness.UNSUPPORTED + threshold pass
  -> 恢复 checkpoint -> 原问题普通聊天

Groundedness.CONTRADICTED + threshold pass
  -> 不再生成第二份模型草稿 -> 显示带 [S1] 等编号的知识库摘录

Groundedness low confidence / model mismatch / timeout
  -> 技术故障 -> 恢复 checkpoint -> 原问题普通聊天
```

## 2. 数据来源与使用边界

| 来源 | 主要任务 | 贡献 | 状态 |
|---|---|---|---|
| ContractNLI | 两阶段 | 合同否定、例外、证据 span、明确冲突 | 第一批，CC BY 4.0 条款归档 |
| SQuAD 2.0 | Answerability 为主 | 可回答与对抗不可回答英文 QA | 第一批，CC BY-SA 4.0 |
| CMRC 2018 | 两阶段中文 | 中文 QA 正例和最小对基础 | 第一批，CC BY-SA 4.0 |
| FinQA | Groundedness 为主 | 金额、百分比、年份、单位、表格、程序 | 第一批，CC BY 4.0；逐项核验第三方来源 |
| HoVer | 两阶段 | 多跳、缺失 hop、实体替换、证据链断裂 | 第一批，CC BY-SA 4.0 |
| DuReader Robust | Answerability | 中文真实搜索问法与噪声 | 保留现有 Apache-2.0 来源 |
| Doc2Dial | 两阶段 | 政务与公共服务长文档对话 | 保留；公开 test 文档排除训练 |
| OASST1/CrossWOZ/KdConv | Answerability | 日常聊天 `UNSUPPORTED` | 保留，只作日常负例 |
| CUAD | 两阶段 | 商务合同字段 | 原始合同权利复核后再扩充 |
| RAGTruth/HaluEval/BIPIA | Groundedness | span 幻觉、对话、提示注入 | `review_required`，未批准不得训练 |
| AVeriTeC/FaithBench/OCNLI/XNLI | 研究评测 | 冲突证据与多语言鲁棒性 | NC 限制，不进入商用训练 |

仓库代码许可证不能代替数据正文许可证。原始归档、正文和生成 JSONL 只进入受控目录，不提交 Git。

## 3. 目标规模与统一 schema

Answerability 训练目标 120,000–150,000 条：`SUPPORTED/PARTIAL/UNSUPPORTED` 比例为 40%/25%/35%。Groundedness 训练目标 150,000–180,000 条：`GROUNDED/PARTIAL/UNSUPPORTED/CONTRADICTED` 比例为 30%/25%/20%/25%。中文、英文、mixed 分别设置最低覆盖量，不再为形式平衡大量下采样。

统一 JSONL schema v2：

```json
{
  "id":"v4-stable-content-id",
  "task":"groundedness",
  "label":"CONTRADICTED",
  "question":"差旅住宿上限是多少？",
  "evidence":[{"source_id":"S1","document_id":"doc-a","text":"住宿上限为800元。"}],
  "answer":"住宿上限为1500元。",
  "atomic_claims":[{"text":"住宿上限为1500元。","support":"contradicted","source_ids":["S1"],"material":true}],
  "language":"zh",
  "domain":"travel",
  "hard_negative_type":"WRONG_AMOUNT",
  "mutation_family_id":"family-a",
  "document_id":"doc-a",
  "conversation_id":"",
  "split":"train",
  "distribution":"public_licensed",
  "redaction_status":"public_source_reviewed",
  "source_dataset":"FinQA",
  "source_version":"1.0",
  "source_record_id":"record-a",
  "source_license":"CC-BY-4.0",
  "provenance":{"raw_sha256":"64-lowercase-hex","transform_version":"rag-guard-v4","generator_commit":"40-lowercase-hex"}
}
```

## 4. 实施任务

### Task 1: 冻结 v3 基线并定义 3+4 标签契约

**Files:**
- Create: `tools/rag_guard/V4_LABEL_CONTRACT.md`
- Create: `tools/rag_guard/test_v4_label_contract.py`
- Modify: `tools/rag_guard/training_data.py`
- Modify: `tools/rag_guard/data/dataset_sources.json`

- [ ] **Step 1: 写失败测试**

```python
def test_v4_labels_are_three_plus_four():
    from tools.rag_guard.training_data import LABELS_BY_TASK
    assert LABELS_BY_TASK["answerability"] == ("SUPPORTED", "PARTIAL", "UNSUPPORTED")
    assert LABELS_BY_TASK["groundedness"] == (
        "GROUNDED", "PARTIAL", "UNSUPPORTED", "CONTRADICTED"
    )
```

- [ ] **Step 2: 运行并确认失败**

```powershell
$python = 'C:\Users\mingjun.dong\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
& $python -m pytest -p no:cacheprovider tools/rag_guard/test_v4_label_contract.py -v
```

Expected: FAIL，因为 Groundedness 仍为三分类。

- [ ] **Step 3: 修改标签常量**

```python
LABELS_BY_TASK = {
    "answerability": ("SUPPORTED", "PARTIAL", "UNSUPPORTED"),
    "groundedness": ("GROUNDED", "PARTIAL", "UNSUPPORTED", "CONTRADICTED"),
}
```

`V4_LABEL_CONTRACT.md` 必须写明：v3 `UNGROUNDED` 不得静默映射为 v4 `UNSUPPORTED`；v4 manifest schema 固定为 2，旧模型保留独立 v3 目录。

- [ ] **Step 4: 回归并提交**

```powershell
& $python -m pytest -p no:cacheprovider tools/rag_guard/test_v4_label_contract.py tools/rag_guard/test_training_data.py -v
git add tools/rag_guard/V4_LABEL_CONTRACT.md tools/rag_guard/test_v4_label_contract.py tools/rag_guard/training_data.py tools/rag_guard/data/dataset_sources.json
git commit -m "feat(rag-guard): define 3x4 label contract"
```

### Task 2: 建立 schema、许可登记与安全验证

**Files:**
- Create: `tools/rag_guard/dataset_schema_v2.py`
- Create: `tools/rag_guard/test_dataset_schema_v2.py`
- Create: `tools/rag_guard/data/dataset_registry_v4.json`
- Create: `tools/rag_guard/DATASET_CARD_V4.md`

- [ ] **Step 1: 写失败测试**

测试必须拒绝旧 `UNGROUNDED`、跨任务非法标签、Groundedness 缺 atomic claims、缺 provenance、未裁决许可证和重复 source ID。

```python
def test_groundedness_rejects_legacy_label():
    row = groundedness_row(label="UNGROUNDED")
    with pytest.raises(ValueError, match="invalid groundedness label"):
        validate_v2_row(row)
```

- [ ] **Step 2: 运行测试确认模块缺失**

```powershell
& $python -m pytest -p no:cacheprovider tools/rag_guard/test_dataset_schema_v2.py -v
```

- [ ] **Step 3: 实现验证器并固定目录**

```text
D:\MiniCPM-V\private-training\rag-guard-v4\raw
D:\MiniCPM-V\private-training\rag-guard-v4\generated
D:\MiniCPM-V\private-eval\rag-guard-office-v4
D:\MiniCPM-V\artifacts\rag-guard-dual-head-v4
```

验证器检查任务标签集合、字段长度、atomic claim、SHA-256、许可证状态、split、语言、document/mutation family ID。继续复用归档路径穿越、符号链接、压缩炸弹、超长行及隐私扫描。

- [ ] **Step 4: 运行并提交**

```powershell
& $python -m pytest -p no:cacheprovider tools/rag_guard/test_dataset_schema_v2.py -v
git add tools/rag_guard/dataset_schema_v2.py tools/rag_guard/test_dataset_schema_v2.py tools/rag_guard/data/dataset_registry_v4.json tools/rag_guard/DATASET_CARD_V4.md
git commit -m "feat(rag-guard): validate v4 dataset schema"
```

### Task 3: 构建 Answerability 三分类数据

**Files:**
- Create: `tools/rag_guard/build_answerability_v4.py`
- Create: `tools/rag_guard/test_build_answerability_v4.py`
- Modify: `tools/rag_guard/data/dataset_registry_v4.json`

- [ ] **Step 1: 写来源映射失败测试**

```python
def test_explicit_negative_answer_is_supported():
    row = contract_text_to_answerability(
        question="合同是否允许自动续期？",
        evidence="本合同不得自动续期。",
    )
    assert row["label"] == "SUPPORTED"
```

测试同时覆盖 SQuAD 可回答/不可回答、CMRC 正例、FinQA 缺表格行、日常聊天配无关证据。

- [ ] **Step 2: 运行测试确认失败**

```powershell
& $python -m pytest -p no:cacheprovider tools/rag_guard/test_build_answerability_v4.py -v
```

- [ ] **Step 3: 实现构造规则**

每个原始 QA 及其变体共享 `mutation_family_id`。`PARTIAL` 只能来自多字段问题或受控组合；`UNSUPPORTED` 至少一半是主题相似困难负例，禁止主要依赖随机错文档。

- [ ] **Step 4: 生成并审计小样**

```powershell
& $python -m tools.rag_guard.build_answerability_v4 --registry tools/rag_guard/data/dataset_registry_v4.json --output D:\MiniCPM-V\private-training\rag-guard-v4\generated\answerability-smoke.jsonl --limit-per-source 1000
& $python -m tools.rag_guard.dataset_schema_v2 D:\MiniCPM-V\private-training\rag-guard-v4\generated\answerability-smoke.jsonl
```

- [ ] **Step 5: 提交**

```powershell
git add tools/rag_guard/build_answerability_v4.py tools/rag_guard/test_build_answerability_v4.py tools/rag_guard/data/dataset_registry_v4.json
git commit -m "feat(rag-guard): build answerability v4 corpus"
```

### Task 4: 构建 Groundedness 四分类和最小对

**Files:**
- Create: `tools/rag_guard/build_groundedness_v4.py`
- Create: `tools/rag_guard/claim_labeling.py`
- Create: `tools/rag_guard/mutations/amount_date.py`
- Create: `tools/rag_guard/mutations/entity_scope.py`
- Create: `tools/rag_guard/mutations/citation_injection.py`
- Create: `tools/rag_guard/test_build_groundedness_v4.py`

- [ ] **Step 1: 写四类边界失败测试**

```python
@pytest.mark.parametrize(("claims", "expected"), [
    (["entailed", "entailed"], "GROUNDED"),
    (["entailed", "missing"], "PARTIAL"),
    (["missing", "missing"], "UNSUPPORTED"),
    (["entailed", "contradicted"], "CONTRADICTED"),
])
def test_claim_aggregation(claims, expected):
    assert aggregate_claim_support(claims) == expected
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
& $python -m pytest -p no:cacheprovider tools/rag_guard/test_build_groundedness_v4.py -v
```

- [ ] **Step 3: 实现原子断言聚合**

```python
def aggregate_claim_support(labels: list[str]) -> str:
    if not labels:
        raise ValueError("at least one material claim is required")
    if "contradicted" in labels:
        return "CONTRADICTED"
    entailed = labels.count("entailed")
    if entailed == len(labels):
        return "GROUNDED"
    if entailed > 0:
        return "PARTIAL"
    return "UNSUPPORTED"
```

- [ ] **Step 4: 实现最小对**

每次金额、日期、实体、单位、否定、范围、版本和引用变异记录 `field_type/original_value/mutated_value/span_start/span_end`。变异后重新解析，确保除目标槽位外差异受控；不得误改手机号、身份证号或引用编号。

- [ ] **Step 5: 固定来源映射**

- ContractNLI：`Entailment -> GROUNDED`，`NotMentioned -> UNSUPPORTED`，`Contradiction -> CONTRADICTED`；
- FinQA：原程序与答案 -> `GROUNDED`，改数值/单位 -> `CONTRADICTED`，删除一个 gold cell -> `PARTIAL`；
- HoVer：完整链 -> `GROUNDED`，缺 hop -> `PARTIAL`，无支持链 -> `UNSUPPORTED`，关系反转 -> `CONTRADICTED`；
- SQuAD/CMRC：原答案 -> `GROUNDED`，添加缺证据断言 -> `PARTIAL`，完全越界 -> `UNSUPPORTED`，替换答案 span -> `CONTRADICTED`。

- [ ] **Step 6: 回归并提交**

```powershell
& $python -m pytest -p no:cacheprovider tools/rag_guard/test_build_groundedness_v4.py -v
git add tools/rag_guard/build_groundedness_v4.py tools/rag_guard/claim_labeling.py tools/rag_guard/mutations tools/rag_guard/test_build_groundedness_v4.py
git commit -m "feat(rag-guard): build four-class groundedness corpus"
```

### Task 5: 去重、族级切分与质量闸门

**Files:**
- Create: `tools/rag_guard/deduplicate_and_split_v4.py`
- Create: `tools/rag_guard/audit_dataset_v4.py`
- Create: `tools/rag_guard/test_dataset_audit_v4.py`

- [ ] **Step 1: 写泄漏测试**

同一 document、conversation、mutation family、translation family、near-duplicate cluster 不得跨 train/calibration/test。

- [ ] **Step 2: 实现确定性切分**

先做规范化 SHA-256 去重，再用字符 5-gram MinHash 聚类。以整个族为单位使用固定 SHA-256 排序分配 split。

- [ ] **Step 3: 执行安全与许可检查**

拒绝未批准许可证、路径穿越、符号链接、重复归档成员、异常压缩比、超长 JSONL、身份证号、手机号、邮箱和未复核真实地址。

- [ ] **Step 4: 执行审计**

```powershell
& $python -m tools.rag_guard.audit_dataset_v4 --registry tools/rag_guard/data/dataset_registry_v4.json --input-dir D:\MiniCPM-V\private-training\rag-guard-v4\generated --report D:\MiniCPM-V\private-training\rag-guard-v4\dataset-audit.json
```

Expected: `passed=true`，全部跨 split 交集为 0，未批准来源为 0。

- [ ] **Step 5: 回归并提交**

```powershell
& $python -m pytest -p no:cacheprovider tools/rag_guard/test_dataset_audit_v4.py -v
git add tools/rag_guard/deduplicate_and_split_v4.py tools/rag_guard/audit_dataset_v4.py tools/rag_guard/test_dataset_audit_v4.py
git commit -m "feat(rag-guard): audit and split v4 corpus"
```

### Task 6: 把共享模型改为 3+4 输出头

**Files:**
- Modify: `tools/rag_guard/model.py`
- Modify: `tools/rag_guard/train.py`
- Modify: `tools/rag_guard/test_training_pipeline.py`

- [ ] **Step 1: 写输出维度失败测试**

```python
def test_dual_head_emits_padded_four_logits():
    model = tiny_dual_head_guard()
    answerability = model(INPUT_IDS, MASK, torch.tensor([0]))
    groundedness = model(INPUT_IDS, MASK, torch.tensor([1]))
    assert answerability.shape == (1, 4)
    assert groundedness.shape == (1, 4)
    assert answerability[0, 3].item() <= -1000.0
```

- [ ] **Step 2: 运行测试确认旧模型失败**

```powershell
& $python -m pytest -p no:cacheprovider tools/rag_guard/test_training_pipeline.py -v
```

- [ ] **Step 3: 实现统一四维 ONNX 输出**

```python
self.answerability_head = nn.Linear(hidden_size, 3)
self.groundedness_head = nn.Linear(hidden_size, 4)
answer_logits = torch.nn.functional.pad(
    self.answerability_head(pooled), (0, 1), value=-10000.0
)
ground_logits = self.groundedness_head(pooled)
selector = task_ids.eq(self.GROUNDEDNESS_TASK_ID).unsqueeze(-1)
return torch.where(selector, ground_logits, answer_logits)
```

Answerability 训练只使用 `logits[:, :3]`；Groundedness 使用全部 4 logits。Android 同样按 manifest 标签数切片后 softmax。

- [ ] **Step 4: 回归并提交**

```powershell
& $python -m pytest -p no:cacheprovider tools/rag_guard/test_training_pipeline.py tools/rag_guard/test_model.py -v
git add tools/rag_guard/model.py tools/rag_guard/train.py tools/rag_guard/test_training_pipeline.py
git commit -m "feat(rag-guard): add 3x4 dual-head model"
```

### Task 7: 加入困难组损失和硬门槛选模

**Files:**
- Modify: `tools/rag_guard/train.py`
- Create: `tools/rag_guard/evaluate_slices.py`
- Create: `tools/rag_guard/test_evaluate_slices.py`

- [ ] **Step 1: 写选模失败测试**

```python
def test_checkpoint_rejects_weak_groundedness():
    metrics = metrics_fixture(answerability_f1=0.99, groundedness_f1=0.81)
    assert eligible_checkpoint(metrics) is False
```

- [ ] **Step 2: 实现联合损失**

$$
\mathcal{L}=\lambda_a\mathcal{L}_{CE3}+\lambda_g\mathcal{L}_{CE4}+\lambda_p\mathcal{L}_{pair}
$$

第一轮固定 \(\lambda_a=1.0\)、\(\lambda_g=1.5\)、\(\lambda_p=0.25\)。最小对排序损失为：

$$
\mathcal{L}_{pair}=\max\left(0,m-d(x^+)+d(x^-)\right),\qquad d(x)=z_G(x)-z_C(x),\quad m=1.0
$$

每批至少包含一组金额、日期、实体或否定最小对，禁止重复冻结 test 样本。

- [ ] **Step 3: 实现硬门槛后排序**

checkpoint 先满足 Answerability macro-F1 不低于 0.95、Groundedness macro-F1 不低于 0.88、`CONTRADICTED` precision 不低于 0.98，再按最差困难组 recall、Groundedness macro-F1、ECE 排序。

- [ ] **Step 4: 回归并提交**

```powershell
& $python -m pytest -p no:cacheprovider tools/rag_guard/test_evaluate_slices.py tools/rag_guard/test_training_pipeline.py -v
git add tools/rag_guard/train.py tools/rag_guard/evaluate_slices.py tools/rag_guard/test_evaluate_slices.py
git commit -m "feat(rag-guard): select checkpoints by hard slices"
```

### Task 8: 构建完整 v4 数据并执行预定义消融

**Files:**
- Create: `tools/rag_guard/TRAINING_RUN_V4.md`
- Generated outside Git: `D:\MiniCPM-V\private-training\rag-guard-v4\generated\*.jsonl`

- [ ] **Step 1: 固定原始归档版本和 SHA-256**

把许可证已批准的数据下载到 `D:\MiniCPM-V\private-training\rag-guard-v4\raw`。registry 记录官方 URL、版本、许可 URL、字节数和 SHA-256；任何哈希不符立即停止。

- [ ] **Step 2: 生成完整数据**

```powershell
& $python -m tools.rag_guard.build_answerability_v4 --registry tools/rag_guard/data/dataset_registry_v4.json --output D:\MiniCPM-V\private-training\rag-guard-v4\generated\answerability.jsonl
& $python -m tools.rag_guard.build_groundedness_v4 --registry tools/rag_guard/data/dataset_registry_v4.json --output D:\MiniCPM-V\private-training\rag-guard-v4\generated\groundedness.jsonl
& $python -m tools.rag_guard.deduplicate_and_split_v4 --input-dir D:\MiniCPM-V\private-training\rag-guard-v4\generated --output-dir D:\MiniCPM-V\private-training\rag-guard-v4\generated\splits
```

- [ ] **Step 3: 只运行三组消融**

1. v3 数据 + 新 3+4 头；
2. v3 数据 + 金额/日期/实体/否定最小对；
3. 完整 v4 数据。

三组固定 base revision `614241f622f53c4eeff9890bdc4f31cfecc418b3`、seed 42、max length 256、batch 16、gradient accumulation 2、learning rate `2e-5`、2 epoch。禁止查看最终 test 后继续调参。

- [ ] **Step 4: 在训练主机复用已有环境**

```bash
conda activate base
cd /root/autodl-fs/rag-guard-v4
python -m tools.rag_guard.train --model /root/autodl-fs/rag-guard-v4/model-base/multilingual-e5-small --data-dir /root/autodl-fs/rag-guard-v4/generated/splits --output-dir /root/autodl-fs/rag-guard-v4/runs/full-v4 --epochs 2 --batch-size 16 --eval-batch-size 32 --gradient-accumulation 2 --max-length 256 --learning-rate 2e-5 --bf16
```

不得安装或升级 CUDA/PyTorch；先确认已有 PyTorch 2.4.1+cu121 与 CUDA 12.1。

- [ ] **Step 5: 记录聚合结果并提交**

`TRAINING_RUN_V4.md` 只记录数据哈希、数量、参数、指标、模型哈希和异常，不记录正文。

```bash
git add tools/rag_guard/TRAINING_RUN_V4.md
git commit -m "docs(rag-guard): record v4 training run"
```

### Task 9: 独立校准动作阈值

**Files:**
- Modify: `tools/rag_guard/quality_gate.py`
- Modify: `tools/rag_guard/score_office_holdout.py`
- Create: `tools/rag_guard/calibrate_v4_actions.py`
- Create: `tools/rag_guard/test_calibrate_v4_actions.py`

- [ ] **Step 1: 写动作阈值失败测试**

```python
def test_v4_profile_has_independent_action_thresholds():
    profile = calibrate_v4(calibration_rows())
    assert 0.0 <= profile["answerability_supported_threshold"] <= 1.0
    assert 0.0 <= profile["grounded_threshold"] <= 1.0
    assert 0.0 <= profile["contradicted_threshold"] <= 1.0
```

- [ ] **Step 2: 固定校准目标**

| 动作 | calibration 目标 |
|---|---|
| 注入知识库 | `SUPPORTED` precision 不低于 0.98，再最大化 recall |
| 显示 RAG 回答 | `GROUNDED` precision 不低于 0.98 |
| 强制知识库替换 | `CONTRADICTED` precision 不低于 0.99，再最大化 recall |
| 普通聊天 | `UNSUPPORTED` recall 不低于 0.95 |

- [ ] **Step 3: 实现温度缩放和分组 ECE**

只使用 calibration 拟合 temperature；test 只执行一次。分别输出中文、英文、mixed、金额、日期、实体、单位、否定、范围、伪引用、提示注入的 ECE。

- [ ] **Step 4: 回归并提交**

```powershell
& $python -m pytest -p no:cacheprovider tools/rag_guard/test_calibrate_v4_actions.py tools/rag_guard/test_quality_gate.py tools/rag_guard/test_score_office_holdout.py -v
git add tools/rag_guard/quality_gate.py tools/rag_guard/score_office_holdout.py tools/rag_guard/calibrate_v4_actions.py tools/rag_guard/test_calibrate_v4_actions.py
git commit -m "feat(rag-guard): calibrate v4 action thresholds"
```

### Task 10: 执行 FP32 冻结验收

**Files:**
- Modify: `tools/rag_guard/OFFICE_QUALITY_GATE.md`
- Create: `docs/execution/evidence/rag-guard-v4-fp32-release-matrix.md`

- [ ] **Step 1: 冻结公开、真实办公和历史 test 哈希**

公开 test、真实办公 test、历史 regression 和真机矩阵的 document/mutation family 必须与训练、calibration 两两不相交。

- [ ] **Step 2: 执行一次 test 并应用硬门槛**

$$
\mathrm{Precision}_{A,SUPPORTED}\ge0.98,\qquad
\mathrm{Recall}_{A,SUPPORTED}\ge0.90
$$

$$
\mathrm{MacroF1}_{G,4class}\ge0.90,\qquad
\mathrm{ECE}_{G}\le0.05
$$

$$
\mathrm{Precision}_{G,CONTRADICTED}\ge0.99,\qquad
\mathrm{Recall}_{G,CONTRADICTED}\ge0.90
$$

$$
\mathrm{Recall}_{G,UNSUPPORTED}\ge0.95
$$

金额、日期、实体、否定各组 `CONTRADICTED` recall 不低于 0.95；`UNSUPPORTED` 与 `CONTRADICTED` 互相混淆率不超过 0.02。

- [ ] **Step 3: 失败即停止**

任一门槛失败，不导出 Android INT8、不修改生产 profile，也不通过调低阈值、修改 test 标签或加入 test 样本继续。

### Task 11: 导出 3+4 INT8 ONNX

**Files:**
- Modify: `tools/rag_guard/export_onnx.py`
- Modify: `tools/rag_guard/test_export_onnx.py`
- Output outside Git: `D:\MiniCPM-V\artifacts\rag-guard-dual-head-v4\model.int8.onnx`

- [ ] **Step 1: 写 manifest schema 2 失败测试**

```python
def test_manifest_records_three_plus_four_labels():
    manifest = exported_manifest_fixture()
    assert manifest["schema_version"] == 2
    assert len(manifest["labels_by_task"]["answerability"]) == 3
    assert len(manifest["labels_by_task"]["groundedness"]) == 4
    assert manifest["output"]["logits"] == "float32[batch,4]"
```

- [ ] **Step 2: 验证 padded Answerability logits**

Answerability 第 4 logit 在 PyTorch、FP32 ONNX、INT8 ONNX 均小于等于 -1000；Android 不得对其做三分类概率解释。

- [ ] **Step 3: 执行量化门槛**

$$
\mathrm{Agreement}_{INT8,FP32}\ge0.995
$$

每任务、每语言和每困难组 macro-F1 下降不超过 0.01；`CONTRADICTED` 到 `UNSUPPORTED` 的量化翻转率不超过 0.005。

- [ ] **Step 4: 回归并提交**

```powershell
& $python -m pytest -p no:cacheprovider tools/rag_guard/test_export_onnx.py -v
git add tools/rag_guard/export_onnx.py tools/rag_guard/test_export_onnx.py tools/rag_guard/TRAINING_RUN_V4.md
git commit -m "feat(rag-guard): export v4 int8 package"
```

### Task 12: 迁移 Android manifest 与分类契约

**Files:**
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/guard/RagGuardClassifier.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/guard/RagGuardModelManifest.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/guard/OnnxRagGuardClassifier.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/guard/RagGuardModelManager.kt`
- Modify: `app/src/test/java/com/example/minicpm_v_demo/rag/guard/RagGuardContractTest.kt`
- Modify: `app/src/test/java/com/example/minicpm_v_demo/rag/guard/RagGuardModelManifestTest.kt`

- [ ] **Step 1: 写 Android 四类失败测试**

```kotlin
@Test
fun `v4 manifest exposes four groundedness labels`() {
    val manifest = CurrentRagGuardModel.V4.manifest
    assertEquals(
        listOf("GROUNDED", "PARTIAL", "UNSUPPORTED", "CONTRADICTED"),
        manifest.labelsByTask.getValue("groundedness"),
    )
}
```

- [ ] **Step 2: 修改枚举和概率契约**

```kotlin
enum class GroundednessLabel {
    GROUNDED,
    PARTIAL,
    UNSUPPORTED,
    CONTRADICTED,
}

data class GroundednessVerdict(
    val label: GroundednessLabel,
    val probabilities: List<Float>,
    val modelSha256: String,
)
```

构造时要求 4 个有限概率、每项位于 `[0,1]`、概率和误差不超过 `1e-4`。Answerability 只读取前 3 logits；Groundedness 读取全部 4 logits。

- [ ] **Step 3: 隔离旧模型**

manifest schema 1 继续由 v3 实验路径解析；v4 runtime 要求 schema 2 和精确标签顺序。SHA、长度或标签不符时返回模型不可用，不猜 ordinal。

- [ ] **Step 4: 回归并提交**

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.example.minicpm_v_demo.rag.guard.*"
git add app/src/main/java/com/example/minicpm_v_demo/rag/guard app/src/test/java/com/example/minicpm_v_demo/rag/guard
git commit -m "feat(android): support v4 groundedness contract"
```

### Task 13: 实现四分类动作策略

**Files:**
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/guard/RagOutputReviewPolicy.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/guard/RagReviewedGenerator.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`
- Modify: `app/src/test/java/com/example/minicpm_v_demo/rag/guard/RagOutputReviewPolicyTest.kt`
- Modify: `app/src/test/java/com/example/minicpm_v_demo/rag/guard/RagReviewedGenerationTest.kt`

- [ ] **Step 1: 写动作矩阵失败测试**

```kotlin
@Test
fun `unsupported uses normal chat while contradicted uses knowledge base`() {
    assertEquals(
        RagOutputReviewAction.FALLBACK_TO_NORMAL_CHAT,
        RagOutputReviewPolicy.decide(GroundednessLabel.UNSUPPORTED, 0, highConfidence = true),
    )
    assertEquals(
        RagOutputReviewAction.REPLACE_WITH_KNOWLEDGE_BASE,
        RagOutputReviewPolicy.decide(GroundednessLabel.CONTRADICTED, 0, highConfidence = true),
    )
}
```

- [ ] **Step 2: 实现动作枚举**

```kotlin
enum class RagOutputReviewAction {
    ACCEPT,
    REGENERATE,
    FALLBACK_TO_NORMAL_CHAT,
    REPLACE_WITH_KNOWLEDGE_BASE,
}
```

低于对应类别阈值统一 `FALLBACK_TO_NORMAL_CHAT`，禁止低置信冲突触发覆盖。

- [ ] **Step 3: 保证上下文隔离**

- `UNSUPPORTED`：恢复 checkpoint，清空 citations/runId，原问题普通生成；
- `CONTRADICTED`：候选不进入 UI/历史/context，直接使用中立化 `<think>` 标签后的知识库摘录；
- `PARTIAL`：最多重生成一次；第二次仍非 `GROUNDED` 且非高置信 `CONTRADICTED` 时普通聊天；
- 取消继续传播，不被 fallback 捕获。

- [ ] **Step 4: 回归并提交**

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.example.minicpm_v_demo.rag.guard.RagOutputReviewPolicyTest" --tests "com.example.minicpm_v_demo.rag.guard.RagReviewedGenerationTest" --tests "com.example.minicpm_v_demo.rag.RagTurnTransactionTest"
git add app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt app/src/main/java/com/example/minicpm_v_demo/rag/guard app/src/test/java/com/example/minicpm_v_demo/rag/guard
git commit -m "feat(rag): route unsupported and contradicted outputs"
```

### Task 14: 执行端侧发布矩阵

**Files:**
- Modify: `app/src/androidTest/java/com/example/minicpm_v_demo/rag/guard/GroundednessReleaseMatrixInstrumentedTest.kt`
- Create: `app/src/androidTest/java/com/example/minicpm_v_demo/rag/guard/RagGuardV4ActionMatrixInstrumentedTest.kt`
- Create: `docs/execution/evidence/rag-guard-v4-device-release-matrix.md`

- [ ] **Step 1: 增加真实动作断言**

覆盖正确金额/日期/实体、无关日常问题、资料未提及、多字段缺失、错金额、错日期、错实体、错单位、否定翻转、范围扩大、伪引用、跨文档串线、旧版本规则、低置信、超时、模型缺失、SHA 不匹配、中文、英文、mixed、取消和后台恢复。

- [ ] **Step 2: 构建和签名验证**

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:verifyInstallationSigning
```

- [ ] **Step 3: 安装到已连接真机**

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb install -r .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
```

签名不一致时只核对固定 debug keystore；禁止临时生成新 keystore。卸载必须获得用户明确授权。

- [ ] **Step 4: 执行矩阵**

```powershell
.\scripts\run-device-instrumentation.ps1 -TestClass "com.example.minicpm_v_demo.rag.guard.RagGuardV4ActionMatrixInstrumentedTest" -TimeoutSeconds 1800
```

只归档 case ID、期望/实际标签、动作、概率、延迟和模型哈希，不归档真实正文。

### Task 15: 固化发布和文档

**Files:**
- Modify: `README_MODIFIED_zh.md`
- Modify: `docs/superpowers/plans/2026-08-18-minicpm-android-unified-progress-plan.md`
- Modify: `tools/rag_guard/TRAINING_RUN_V4.md`
- Modify: `graphify-out/*`

- [ ] **Step 1: 固定生产 profile**

只有全部门槛通过后，profile 同时绑定 Guard SHA-256、tokenizer SHA-256、schema 2、标签顺序、三个动作阈值、数据 manifest SHA-256 和训练 commit。

- [ ] **Step 2: 更新用户说明**

知识库页面继续显示：

> 模型回答与知识库内容不一致时，将优先采用知识库中的答案。请确保导入的文档内容准确、有效。

README 明确：知识库没有答案时正常聊天；只有高置信明确冲突才知识库替换。

- [ ] **Step 3: 更新 Graphify**

```powershell
graphify update .
graphify query "Answerability three class Groundedness four class unsupported normal chat contradicted knowledge base replacement" --budget 3000
```

- [ ] **Step 4: 最终提交**

```powershell
git add README_MODIFIED_zh.md docs tools/rag_guard app/src graphify-out
git commit -m "feat(rag): release 3x4 guard pipeline"
```

## 5. 发布停止条件

出现以下任一情况立即停止，不部署 v4：

1. 数据正文许可、商用或衍生改造权利不明确；
2. train/calibration/test 存在 document、conversation、mutation、translation 或近重复族泄漏；
3. Answerability `UNSUPPORTED` 仍被频繁放入知识库生成路径；
4. Groundedness `UNSUPPORTED` 与 `CONTRADICTED` 混淆率超过 0.02；
5. 错误金额或日期的 `CONTRADICTED` recall 低于 0.95；
6. `CONTRADICTED` precision 低于 0.99；
7. INT8 关键困难组退化超过 0.01；
8. Android manifest、标签顺序、模型 SHA 或 tokenizer SHA 不一致；
9. 通过降低门槛、修改冻结 test 标签或把 test 样本加入 train 获得通过；
10. 固定签名覆盖安装验证失败。

## 6. 完成后的确定行为

```text
知识库没有答案
-> Answerability.PARTIAL/UNSUPPORTED，或 Groundedness.UNSUPPORTED
-> 清除 RAG 临时证据
-> 原问题正常聊天
-> 不显示知识库标识

知识库有答案且回答正确
-> Groundedness.GROUNDED
-> 显示回答、引用和知识库标识

回答只有部分依据
-> Groundedness.PARTIAL
-> 同证据重生成一次
-> 仍不完整则正常聊天

回答与知识库发生明显冲突
-> Groundedness.CONTRADICTED 且超过高精度阈值
-> 丢弃模型回答
-> 不显示冲突提示
-> 直接显示带来源编号的知识库结果
```

当前 v3 双三分类模型和 App 枚举尚未改变；只有完成 Task 1–15 并通过全部冻结门槛后，才能把上述行为标记为已实现。
