# Android 端侧 RAG 低延迟重构实施计划

> **Archived 2026-08-18:** 本文保留低延迟重构、checkpoint、混合检索和双分类器的实施历史；当前进度和剩余任务统一由 [MiniCPM Android 统一进度与后续实施计划](2026-08-18-minicpm-android-unified-progress-plan.md) 跟踪。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将当前“每次 RAG 提问销毁 native context 并重放全部历史”的实现替换为选择性检索、受限证据和可回滚 native 状态分支，使普通聊天零 RAG 开销，并将 RAG 相对普通生成的额外 P95 前处理时间控制在 $1.5\text{ s}$ 以内。

**Architecture:** 稳定会话上下文只保存系统提示、用户原文、图片状态和最终 AI 回答；本轮检索证据通过 native checkpoint 临时追加，生成后恢复 checkpoint，再把原始用户消息和最终答案追加到稳定上下文。查询先经过无模型快速路由与严格相关性门控，检索后只保留少量句子级证据；`NoEvidence` 不注入候选、不显示额外提示，直接使用用户原文正常生成，只有 checkpoint、检索或预算等技术错误才返回不进入模型上下文的固定提示。

**Tech Stack:** Kotlin、Coroutines/Flow、Room/SQLCipher、FTS4 `matchinfo`、ONNX Runtime Android、multilingual-e5-small int8、C++17/JNI、llama.cpp-omni state/sequence API、Android instrumentation、JUnit 4。

---

## 1. 调研结论与当前根因

### 1.1 已验证的本项目事实

- `MainActivity.submitPromptToModel()` 在 `RagPromptPreparation.Augmented` 分支调用 `replayActiveConversationContext()`。
- `replayActiveConversationContext()` 调用 `LlamaEngine.clearContext()`，后者调用 JNI `fullReset()`。
- `fullReset()` 释放并重新创建 `llama_context`、batch、chat template 和 sampler。
- 当前真机日志显示每次 context 初始化重新 reserve 约 $1962\text{ MiB}$ CPU compute buffer；问候语曾停在 `appendHistoryMessage()`，尚未进入 `Sending user prompt`。
- `LocalRagRetriever` 对所有 RAG 已开启的输入执行 E5，并从数据库读取、解码全部选中知识库 embedding；没有查询意图路由和相关性阈值。
- `ExactVectorRanker` 总会返回 Top-K，因此“你好”也可能获得弱相关片段并触发增强。
- 增强 prompt 再次经过 `LlamaEngine.visualContextPolicy.evaluatePrompt()`；真机已出现 `BLOCK_NEEDS_VISUAL` 和 `BLOCK_UNCERTAIN`，说明安全分类检查了增强 prompt 而非用户原文。

### 1.2 采用的外部方案

- llama.cpp 官方提供完整 state、单 sequence state、内存和文件形式的保存/恢复 API；本仓库分支也为 recurrent 与 hybrid memory 实现了 state 读写：<https://github.com/ggml-org/llama.cpp/blob/master/include/llama.h#L3428-L3589>
- llama.cpp server 使用 slot/prompt cache 保存与恢复稳定前缀，避免重复 prefill：<https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md#post-slotsid_slotactionsave-save-the-prompt-cache-of-the-specified-slot-to-a-file>
- Adaptive-RAG 使用轻量分类器在 no-retrieval、single-step 和 multi-step 路径间选择，避免简单请求承担检索开销：<https://aclanthology.org/2024.naacl-long.389/>
- MobileRAG 使用分区加载的向量索引和 Selective Content Reduction 降低端侧检索内存、CPU、输入长度和功耗：<https://arxiv.org/abs/2507.01079>
- RECOMP 证明检索后只保留相关句子，并在证据无增益时输出空增强，可以降低输入成本：<https://proceedings.iclr.cc/paper_files/paper/2024/hash/bda88ed2892f5e61c9a9bf215c566913-Abstract-Conference.html>
- ONNX Runtime 支持 Android NNAPI，但不支持算子回退到 NNAPI CPU 时可能慢于 ORT CPU，必须在目标真机对比：<https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html>

### 1.3 明确不采用的方案

- 不为每轮 RAG 调用 `fullReset()`。
- 不创建第二个常驻 MiniCPM context；当前单 context 已产生约 $2.5\text{ GiB}$ RSS，双 context 风险不可接受。
- 不把整个知识库预填成 CAG/KV cache；当前上下文仅 4096 或 8192 token，且知识库可删除、切换和多选。
- 第一阶段不引入额外生成式压缩模型或 LLMLingua；压缩模型自身会增加端侧内存与延迟。
- 不让检索失败静默退化为无依据普通回答。
- 不把用户原文替换成增强 prompt 写入聊天归档。

## 2. 最终数据流和状态机

```text
Input safety/privacy confirmation
  -> RagQueryRouter
     -> Disabled/NoRetrieval: current stable context -> normal generation
     -> SingleRetrieval/ComplexRetrieval
        -> lexical gate
        -> optional E5 dense search
        -> HybridFusion + EvidenceAcceptancePolicy
           -> NoEvidence: original user text -> normal generation, no citations
           -> Ready: EvidenceReducer + RagContextBudgeter
              -> Native beginEphemeralTurn checkpoint
              -> sendPreparedPrompt(modelPrompt, originalUserText)
              -> output safety + CitationValidator
              -> Native restore checkpoint
              -> append original user message
              -> append final accepted answer
              -> persist answer and citation snapshots
```

RAG 轮次状态固定为：

```kotlin
sealed interface RagTurnState {
    data object Idle : RagTurnState
    data object Routing : RagTurnState
    data object Retrieving : RagTurnState
    data object ReducingEvidence : RagTurnState
    data object SavingCheckpoint : RagTurnState
    data object PrefillingPrompt : RagTurnState
    data object Generating : RagTurnState
    data object RestoringCheckpoint : RagTurnState
    data class Completed(val trace: RagLatencyTrace) : RagTurnState
    data class Failed(val kind: RagTurnFailure) : RagTurnState
}
```

事务不变量：

1. 同一时间最多一个 native checkpoint。
2. checkpoint 在当前图片预填完成后、RAG prompt 送模前创建。
3. 无论成功、取消、退后台、输出拦截还是异常，都必须在 `NonCancellable` 区域恢复或释放 checkpoint。
4. 恢复失败后将 engine 置为 `LlamaState.Error`，禁止继续追加消息；用户可执行一次明确的会话恢复。
5. RAG 证据永不写入 `ChatMessage.UserMessage.text`、`AiMessage.text` 或会话归档。
6. local reply、隐私提示、安全拒答继续保持 `includeInModelContext=false`。

## 3. 性能与质量验收标准

目标真机为当前已连接的 vivo `V2359A`，冷启动和热运行分开记录。

| 指标 | 热运行 P50 | 热运行 P95 | 失败条件 |
|---|---:|---:|---|
| 普通问候路由 | $<5\text{ ms}$ | $<15\text{ ms}$ | 调用 E5、Room chunk 查询或 checkpoint |
| E5 单 query | $<500\text{ ms}$ | $<900\text{ ms}$ | P95 超过 $1.2\text{ s}$ |
| $N\le 5000$ 精确向量检索 | $<80\text{ ms}$ | $<180\text{ ms}$ | 每次从 Room读取全部 BLOB 超过预算 |
| 大索引检索 | $<120\text{ ms}$ | $<300\text{ ms}$ | RSS 随 chunk 数线性增长 |
| 证据缩减 | $<30\text{ ms}$ | $<80\text{ ms}$ | 需要第二个生成模型 |
| native checkpoint 保存 | $<200\text{ ms}$ | $<500\text{ ms}$ | state 大于 $256\text{ MiB}$ 或恢复不一致 |
| native checkpoint 恢复 | $<200\text{ ms}$ | $<500\text{ ms}$ | state 大于 $256\text{ MiB}$ 或恢复不一致 |
| RAG 相对普通生成额外前处理 | $<800\text{ ms}$ | $<1.5\text{ s}$ | 任一轮调用 `fullReset()` |
| RAG TTFT | 按模型基线 $+1\text{ s}$ | 按模型基线 $+2\text{ s}$ | 等待无阶段提示或超过 15 秒无恢复 |

路由质量：问候/感谢/纯聊天误触发检索率不高于 $1\%$；带明确文档锚点的问题漏检率不高于 $1\%$。检索质量：合成办公集 Recall@4 不低于 $90\%$，NoEvidence 精确率不低于 $95\%$。

## 4. 文件结构

### 新建 Kotlin 文件

- `app/src/main/java/com/example/minicpm_v_demo/rag/route/RagQueryRouter.kt`：纯 Kotlin 输入路由。
- `app/src/main/java/com/example/minicpm_v_demo/rag/route/RagQueryFeatures.kt`：NFKC 规范化、问候、知识库锚点和复杂度特征。
- `app/src/main/java/com/example/minicpm_v_demo/rag/retrieval/HybridRetriever.kt`：FTS 与 dense 调度、降级和结果类型。
- `app/src/main/java/com/example/minicpm_v_demo/rag/retrieval/FtsMatchInfo.kt`：安全解析 FTS4 `matchinfo` BLOB 并计算 BM25。
- `app/src/main/java/com/example/minicpm_v_demo/rag/retrieval/ReciprocalRankFusion.kt`：稳定 RRF。
- `app/src/main/java/com/example/minicpm_v_demo/rag/retrieval/EvidenceAcceptancePolicy.kt`：严格证据门控。
- `app/src/main/java/com/example/minicpm_v_demo/rag/retrieval/EvidenceReducer.kt`：无额外模型的句子窗口选择与去重。
- `app/src/main/java/com/example/minicpm_v_demo/rag/prompt/RagContextBudgeter.kt`：按真实 token 数分配证据预算。
- `app/src/main/java/com/example/minicpm_v_demo/rag/prompt/RagPromptBuilder.kt`：转义后构造临时 prompt。
- `app/src/main/java/com/example/minicpm_v_demo/rag/RagCoordinator.kt`：唯一 RAG 状态决策入口。
- `app/src/main/java/com/example/minicpm_v_demo/rag/RagTurnTransaction.kt`：checkpoint 生命周期和稳定历史提交。
- `app/src/main/java/com/example/minicpm_v_demo/rag/telemetry/RagLatencyTrace.kt`：只记录耗时、计数和匿名 ID。
- `app/src/main/java/com/example/minicpm_v_demo/rag/index/VectorSearchBackend.kt`：Exact/HNSW 统一接口。
- `app/src/main/java/com/example/minicpm_v_demo/rag/index/HnswIndexManager.kt`：原子索引文件、版本和内存映射生命周期。

### 修改文件

- `app/src/main/cpp/llama_jni.cpp`：native checkpoint、token 计数、上下文使用量和事务恢复。
- `app/src/main/java/com/example/minicpm_v_demo/LlamaEngine.kt`：公开串行化 checkpoint API和 prepared prompt API。
- `app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`：删除 RAG 每轮 replay，改为 coordinator + transaction。
- `app/src/main/java/com/example/minicpm_v_demo/MiniCPMApplication.kt`：组装单例组件。
- `app/src/main/java/com/example/minicpm_v_demo/rag/db/RagDaos.kt`：FTS `matchinfo` 投影和受限 embedding 读取。
- `app/src/main/java/com/example/minicpm_v_demo/rag/retrieval/LocalRagRetriever.kt`：最终由 `HybridRetriever` 替代后删除。
- `app/src/main/java/com/example/minicpm_v_demo/rag/retrieval/RagDispatchPolicy.kt`：替换为 `RagTurnPlan`。
- `app/src/main/java/com/example/minicpm_v_demo/rag/retrieval/RagPromptAssembler.kt`：迁移到安全 builder 后删除。
- `app/src/main/java/com/example/minicpm_v_demo/rag/config/RagLimits.kt`：性能和 token 限额。
- `app/src/main/res/values/strings.xml`：阶段、超时与安全降级提示。
- `README_MODIFIED_zh.md`：说明 AUTO 路由、性能边界和离线行为。

### 新建测试资源

- `app/src/test/resources/rag/route_cases.tsv`：`id、label、query、reason`。
- `app/src/test/resources/rag/retrieval_cases.tsv`：问题、相关 chunk、无证据标签和阈值版本。
- `app/src/androidTest/assets/rag/performance_corpus.txt`：不含真实隐私的固定性能语料。

## 5. 实施任务

### Task 0：冻结基线并增加分阶段计时

**Files:**
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/telemetry/RagLatencyTrace.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`
- Test: `app/src/test/java/com/example/minicpm_v_demo/rag/telemetry/RagLatencyTraceTest.kt`

- [x] **Step 1：写计时器红灯测试。** 固定单调时钟，断言阶段顺序、非负耗时、重复结束阶段被拒绝，并证明 trace 不包含 query 或正文。

```kotlin
val trace = RagLatencyTrace.start("run-1", clock)
trace.begin(RagPhase.ROUTE)
clock.advanceMillis(4)
trace.end(RagPhase.ROUTE)
assertEquals(4L, trace.snapshot().durationsMs.getValue(RagPhase.ROUTE))
assertFailsWith<IllegalStateException> { trace.end(RagPhase.ROUTE) }
```

- [x] **Step 2：运行红灯测试。**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.minicpm_v_demo.rag.telemetry.RagLatencyTraceTest" -x buildGgmlCpu_v86
```

预期：因 `RagLatencyTrace` 未定义而失败。

- [x] **Step 3：实现最小计时类型。**

```kotlin
enum class RagPhase { ROUTE, EMBED, LEXICAL, DENSE, FUSION, REDUCE, CHECKPOINT_SAVE, PREFILL, TTFT, CHECKPOINT_RESTORE }

data class RagLatencySnapshot(
    val runId: String,
    val durationsMs: Map<RagPhase, Long>,
    val candidateCount: Int,
    val evidenceTokenCount: Int,
)
```

生产日志只输出 `runId` 的截断哈希、阶段耗时、候选数、token 数、结果枚举；禁止输出问题、chunk 文本、文件名和引用摘录。

- [x] **Step 4：在现有链路记录基线。** 在 `preparePrompt()`、`replayActiveConversationContext()` 和首次 flow token 处打点；只用于证明旧链路耗时，后续 Task 3 删除 RAG replay 调用。
- [ ] **Step 5：运行单元测试和真机问候/RAG 各 20 次基线。** 结果保存到 `docs/execution/evidence/rag-latency-baseline-20260814.md`，只记录聚合统计。
- [ ] **Step 6：提交。**

```powershell
git add MiniCPM-V-demo-Android/app/src/main MiniCPM-V-demo-Android/app/src/test MiniCPM-V-demo-Android/docs/execution/evidence/rag-latency-baseline-20260814.md
git commit -m "android: instrument local RAG latency phases"
```

### Task 1：普通聊天零检索路由

**Files:**
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/route/RagQueryFeatures.kt`
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/route/RagQueryRouter.kt`
- Create: `app/src/test/resources/rag/route_cases.tsv`
- Test: `app/src/test/java/com/example/minicpm_v_demo/rag/route/RagQueryRouterTest.kt`

- [x] **Step 1：建立至少 120 条路由回归集。** 标签固定为 `NO_RETRIEVAL`、`SINGLE_RETRIEVAL`、`COMPLEX_RETRIEVAL`；包含中英文问候、感谢、翻译、改写、文档名、条款号、日期、合同金额、跨文档比较和历史绕过句式。语料必须是合成数据。
- [x] **Step 2：写参数化红灯测试。** 读取 TSV，断言所有标签；额外断言 `ragEnabled=false` 时路由不提取特征之外的组件。
- [x] **Step 3：实现确定性路由接口。**

```kotlin
enum class RagQueryRoute { NO_RETRIEVAL, SINGLE_RETRIEVAL, COMPLEX_RETRIEVAL }

data class RagRouteInput(
    val ragEnabled: Boolean,
    val query: String,
    val knownDocumentNames: List<String>,
)

interface RagQueryRouter {
    fun route(input: RagRouteInput): RagQueryRoute
}
```

`DefaultRagQueryRouter` 先做 Unicode NFKC、空白折叠和长度上限，再按优先级判断：关闭 RAG；明确文件名/“根据文档”“知识库”“第 N 条”；跨文档/比较/汇总；纯问候感谢；其他输入进入 `SINGLE_RETRIEVAL`，由证据阈值决定是否增强。

- [x] **Step 4：加入绕过防护。** 只有整句符合社交模式且不存在文件名、编号、金额、日期或知识库锚点时才能 `NO_RETRIEVAL`；“你好，请根据合同回答”必须检索。
- [x] **Step 5：运行测试并检查误触发率。** 120 条基础集要求 100% 通过；独立 100 条扰动集误触发率不高于 $1\%$。
- [ ] **Step 6：提交。**

```powershell
git add MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/rag/route MiniCPM-V-demo-Android/app/src/test
git commit -m "android: route ordinary chat around local RAG"
```

### Task 2：native checkpoint 正确性原型

**Files:**
- Modify: `app/src/main/cpp/llama_jni.cpp`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/LlamaEngine.kt`
- Test: `app/src/androidTest/java/com/example/minicpm_v_demo/LlamaCheckpointInstrumentedTest.kt`

- [x] **Step 1：写真机红灯测试。** 加载生产 MiniCPM 模型，预填固定历史，保存 checkpoint，追加临时 RAG 文本并生成固定数量 token，恢复 checkpoint，再次追加同一普通问题。断言恢复后 `currentPosition`、chat message count 和固定 seed 下首 token 与对照路径一致。
- [x] **Step 2：定义 native checkpoint 容器。**

```cpp
struct native_checkpoint {
    std::vector<uint8_t> context_state;
    common_sampler * sampler = nullptr;
    std::vector<common_chat_msg> chat_messages;
    llama_pos current_position = 0;
    llama_pos generation_start_position = 0;
    llama_pos stop_generation_position = 0;
    bool image_prefilled = false;
    bool vision_mode = false;
};
```

容器驻留 native heap，不经 JNI 复制正文；最多存在一个 checkpoint。释放时先 `common_sampler_free()`，再用 `std::fill` 覆盖 `context_state`。

- [x] **Step 3：实现 JNI 接口。**

```kotlin
private external fun beginEphemeralTurnNative(): Long
private external fun restoreEphemeralTurnNative(handle: Long): Boolean
private external fun releaseEphemeralTurnNative(handle: Long)
private external fun checkpointSizeBytesNative(handle: Long): Long
private external fun currentContextPositionNative(): Int
```

保存使用 `llama_state_seq_get_size_ext(..., LLAMA_STATE_SEQ_FLAGS_NONE)` 与 `llama_state_seq_get_data_ext()`；sampler 使用 `common_sampler_clone(g_sampler)`。恢复成功后替换 `g_sampler`、恢复所有 bookkeeping 字段并清空短期 UTF-8/token buffer。

- [x] **Step 4：增加 256 MiB 硬上限。** `size == 0`、写入长度不一致、超过上限、已有活动 checkpoint 或空 context 均返回 0；不得调用 `fullReset()` 兜底。
- [x] **Step 5：验证 recurrent/hybrid state。** 分别运行纯文本和已有图片上下文用例；恢复前后 position、下一 token 和引用图片追问能力一致。若 sequence state 不一致，只将实现切换到 `llama_state_get_data()` / `llama_state_set_data()`，保持 JNI 接口不变。

  2026-08-14：纯文本 recurrent/hybrid 用例已通过。视觉超时根因确认为 vivo V2359A 在无前台 Activity 时将 instrumentation 目标进程写入 vendor freezer（`cgroup.freeze=1`、`do_freezer_trap`），并非图片预填持续计算；使用受 `android.permission.DUMP` 保护且仅存在于 debug 构建的前台测试宿主，以及检测冻结后自动恢复宿主的真机脚本后，视觉用例连续两次分别以 10.728 秒和 10.337 秒通过。阶段日志显示模型与 mmproj 加载约 2.9 秒、96×96 图像预填约 5.15 秒，checkpoint 恢复后 position、图片状态和固定 seed 首 token 均一致。图像切割偏好在测试后同步恢复为 9。

- [x] **Step 6：记录真机 state 大小、保存和恢复 P50/P95。** 达不到第 3 节门槛时停止接入，保留普通聊天，RAG 返回固定“当前设备暂不支持低延迟知识库推理”。

  vivo V2359A、20 次热态纯文本 checkpoint：state 21,112,884 bytes（约 20.13 MiB），保存 P50/P95 为 9.86/18.14 ms，恢复 P50/P95 为 7.46/9.84 ms，满足 500 ms P95 闸门。
- [x] **Step 7：运行签名构建与真机测试。**

  2026-08-14：主 APK、测试 APK 和稳定签名校验通过；纯文本 checkpoint 真机用例稳定通过，视觉用例连续两次通过。真机执行统一使用 `scripts/run-device-instrumentation.ps1` 包装手动 `am instrument`，禁止调用 Gradle `connected*AndroidTest`；脚本只在检测到 vivo freezer 时恢复 debug 测试宿主，不启动聊天主界面，也不触发第二路模型加载。

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:assembleDebug :app:assembleDebugAndroidTest :app:verifyInstallationSigning -x buildGgmlCpu_v86
```

- [ ] **Step 8：提交。** `git commit -m "android: add bounded native context checkpoints"`

### Task 3：RAG 临时上下文事务

**Files:**
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/RagTurnTransaction.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/LlamaEngine.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`
- Test: `app/src/test/java/com/example/minicpm_v_demo/rag/RagTurnTransactionTest.kt`
- Test: `app/src/androidTest/java/com/example/minicpm_v_demo/RagConversationContextInstrumentedTest.kt`

- [x] **Step 1：写 fake engine 红灯测试。** 覆盖成功、生成异常、取消、输出安全拦截、restore 失败和重复 close；断言每条路径恰好恢复一次。
- [x] **Step 2：定义事务接口。**

```kotlin
interface EphemeralContextEngine {
    suspend fun beginEphemeralTurn(): NativeCheckpoint
    suspend fun restoreEphemeralTurn(checkpoint: NativeCheckpoint)
    suspend fun releaseEphemeralTurn(checkpoint: NativeCheckpoint)
    suspend fun appendStableHistory(role: ModelHistoryRole, text: String)
}

class RagTurnTransaction(
    private val engine: EphemeralContextEngine,
    private val checkpoint: NativeCheckpoint,
) {
    suspend fun commit(originalUserText: String, acceptedAnswer: String)
    suspend fun rollback(keepUserInHistory: Boolean, originalUserText: String)
}
```

- [x] **Step 3：修改发送接口的安全边界。**

```kotlin
fun sendPreparedPrompt(
    modelPrompt: String,
    originalUserTextForSafety: String,
    predictLength: Int = DEFAULT_PREDICT_LENGTH,
): Flow<String>
```

视觉输入分类只检查 `originalUserTextForSafety`；`modelPrompt` 仍经过长度、UTF-8 和 context capacity 校验，但不作为视觉意图输入。输入内容安全与隐私确认继续发生在 `MainActivity` 调用 coordinator 之前。

- [x] **Step 4：替换 MainActivity 的 Augmented 分支。** 删除该分支中的 `replayActiveConversationContext(skipMessageId)` 和图片重复预填；改为 checkpoint、临时 prompt、生成、恢复、稳定历史追加。普通 `PassThrough` 完全不创建 checkpoint。
- [x] **Step 5：定义取消语义。** 用户消息已显示后取消生成：恢复 checkpoint，再把用户原文追加到稳定上下文，移除空 AI 占位；退出后台的取消路径执行同样操作。恢复操作运行于 `NonCancellable + llamaDispatcher`，完成后才能清除 `isSubmitting`。
- [x] **Step 6：真机断言证据不残留。** 第一轮用知识库秘密词回答，第二轮关闭 RAG 后询问秘密词；模型上下文 dump 的 token hash 不含第一轮 evidence，历史仅包含用户原文和答案。

  2026-08-17：vivo V2359A 上运行 `RagConversationContextInstrumentedTest`，10.044 秒通过。测试先写入合成秘密证据，再提交稳定的用户原文与已接受答案；恢复后的 position、chat message count、视觉状态和原生历史指纹与“无证据直接重建”路径完全一致，后续普通提示的固定 seed 首 token 也一致。主 APK 与测试 APK 均使用 `adb install -r` 覆盖安装，未卸载、未清数据，`verifyInstallationSigning` 通过。
- [x] **Step 7：提交。** `git commit -m "android: isolate RAG evidence with context transactions"`（`91362c0`，2026-08-17 已推送）

### Task 4：统一 RagCoordinator 状态决策

**Files:**
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/RagCoordinator.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/MiniCPMApplication.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/retrieval/RagDispatchPolicy.kt`
- Test: `app/src/test/java/com/example/minicpm_v_demo/rag/RagCoordinatorTest.kt`

- [x] **Step 1：写零调用红灯测试。** `Disabled` 和 `NoRetrieval` 路径断言 embedding、Room chunk DAO、checkpoint 和 prompt builder 调用次数均为 0。
- [x] **Step 2：定义完整 plan。**

```kotlin
sealed interface RagTurnPlan {
    data object Disabled : RagTurnPlan
    data object NoRetrieval : RagTurnPlan
    data object NoSelection : RagTurnPlan
    data object Indexing : RagTurnPlan
    data object ModelRequired : RagTurnPlan
    data object NoEvidence : RagTurnPlan
    data class Ready(
        val runId: String,
        val prompt: String,
        val citations: List<RetrievedChunk>,
        val evidenceTokenCount: Int,
    ) : RagTurnPlan
    data class Failed(val kind: RagTurnFailure) : RagTurnPlan
}
```

- [x] **Step 3：实现依赖注入。** coordinator 只依赖 state DAO、router、retriever、reducer、budgeter、prompt builder、clock；不持有 Activity、View 或 LlamaEngine。
- [x] **Step 4：实现严格状态顺序。** Disabled -> route -> selection/index readiness -> retrieve -> accept -> reduce -> budget -> Ready；任何异常映射为匿名错误类型，禁止 catch 后返回普通 prompt。
- [x] **Step 5：删除 `LocalRagRetriever.preparePrompt()` 的策略职责。** 数据检索迁入 `HybridRetriever`，旧类在所有调用迁移后删除。

  2026-08-17：`preparePrompt()` 和所有分散的调度决策已删除；后续已由 `HybridRetriever`、`RoomDenseEvidenceRetriever` 和 `RoomLexicalEvidenceRetriever` 替换旧类。协调器单元测试以及两项真实 E5 真机路由/检索测试均已通过。

  2026-08-17 产品决策：`NoEvidence` 保留为内部诊断状态，但发送层必须原样使用用户输入走普通模型，不显示“知识库未命中”等额外回复，不携带候选证据或引用；`NoSelection`、`Indexing`、`ModelRequired` 和技术失败继续显示可行动提示。
- [x] **Step 6：提交。** `feat(rag): centralize adaptive turn planning`（`ebab5c2`）

### Task 5：FTS4 + dense 混合检索和证据阈值

**Files:**
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/retrieval/FtsMatchInfo.kt`
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/retrieval/ReciprocalRankFusion.kt`
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/retrieval/EvidenceAcceptancePolicy.kt`
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/retrieval/HybridRetriever.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/db/RagDaos.kt`
- Test: `app/src/test/java/com/example/minicpm_v_demo/rag/retrieval/FtsMatchInfoTest.kt`
- Test: `app/src/test/java/com/example/minicpm_v_demo/rag/retrieval/HybridRetrieverTest.kt`

- [x] **Step 1：写手算 BM25、RRF 和降级红灯测试。** 覆盖中文 bigram、英文词、短语、编号、空查询、FTS 运算符注入、tie-break、dense 失败、FTS 失败和双路失败。
- [x] **Step 2：增加安全 FTS 投影。** DAO 返回 `chunkId` 与 `matchinfo(chunk_fts, 'pcnalx')` BLOB；query token 只允许经过转义器生成，SQL 继续使用绑定参数。
- [x] **Step 3：在 Kotlin 解析 matchinfo 并计算 BM25。**

$$
\operatorname{IDF}(t)=\ln\left(1+\frac{N-df_t+0.5}{df_t+0.5}\right)
$$

$$
\operatorname{BM25}(q,d)=\sum_{t\in q}\operatorname{IDF}(t)
\frac{tf_{t,d}(k_1+1)}{tf_{t,d}+k_1\left(1-b+b\frac{|d|}{\overline{|d|}}\right)}
$$

固定 $k_1=1.2$、$b=0.75$，所有整数读取使用 little-endian 且验证 BLOB 长度，损坏数据返回显式失败。

- [x] **Step 4：实现稳定 RRF。**

$$
\operatorname{RRF}(d)=\sum_{r\in\{dense,lexical\}}\frac{1}{60+\operatorname{rank}_r(d)}
$$

最终 tie-break 固定为 RRF 降序、dense 降序、BM25 降序、chunkId 升序。

- [x] **Step 5：实现透明证据门控。** 只有以下任一条件成立才接受：文件名/条款精确锚点；dense 达高阈值；dense 达普通阈值且 lexical 同时命中。阈值键由 embedding model SHA 和语料版本组成，不使用未校准的统一常数。
- [ ] **Step 6：建立至少 300 条检索与可回答性校准集。** 分为相关、相似但不可回答、完全无关、问候、编号、日期、金额、跨文档；只有级联策略同时满足 NoEvidence 精确率和 Recall@4 门槛，才能写入版本化配置。
- [x] **Step 7：限制候选。** lexical top-40、dense top-40、RRF top-12、每文档最多 3 个候选；未 READY、全局停用和未选知识库必须在 SQL 层排除。

  2026-08-17：本地单元测试、主 APK 和测试 APK 已构建通过。签名校验通过后使用 `adb install -r` 覆盖安装主 APK 与测试 APK，未卸载、未清除应用数据。vivo `V2359A` 真机上 Room FTS `matchinfo`、READY/启用/所选知识库及语料版本过滤测试 1/1 通过；生产 `HybridRetriever` 的真实 E5 向量增强与普通问候零检索测试 2/2 通过。未校准时策略只放行精确文件名、强编号和条款锚点，dense 组合阈值保持关闭；Step 6 仍是启用普通 dense 证据的硬闸门。

  2026-08-17 复核：初次 320 条校准得到的绝对 BM25 阈值在 40 文档语料上满足指标，但单文档真机回归中，同一普通语义问题的 dense 为 `0.85583067`、BM25 仅为 `0.86304622`，低于原阈值 `4.571398`。根因是 BM25 的 IDF 随知识库规模变化，因此该生产阈值作废。改用词项覆盖率后，在 NoEvidence 精确率不低于 `0.95` 时最大 Recall@4 仅为 `0.88`，证明纯分数阈值不能可靠识别“语义相关但没有答案”。生产配置必须继续 fail-closed，直至 Task 5A 完成。
- [x] **Step 8：提交。** `feat(rag): add gated hybrid retrieval`（`e7ce7a8`）

### Task 5A：低开销级联 Answerability 门控

**Files:**
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/retrieval/AnswerabilityClassifier.kt`
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/retrieval/CascadedEvidenceAcceptancePolicy.kt`
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/retrieval/AnswerabilityModelManifest.kt`
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/retrieval/OnnxAnswerabilityClassifier.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/RagCoordinator.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/retrieval/EvidenceAcceptancePolicy.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/retrieval/RetrievalThresholdCalibrator.kt`
- Test: `app/src/test/java/com/example/minicpm_v_demo/rag/retrieval/CascadedEvidenceAcceptancePolicyTest.kt`
- Test: `app/src/test/java/com/example/minicpm_v_demo/rag/RagCoordinatorTest.kt`
- Test: `app/src/androidTest/java/com/example/minicpm_v_demo/rag/retrieval/AnswerabilityBenchmarkInstrumentedTest.kt`
- Test: `app/src/androidTest/java/com/example/minicpm_v_demo/rag/retrieval/RetrievalCalibrationInstrumentedTest.kt`

- [x] **Step 1：把证据裁决接口改成可暂停且显式接收问题。** 先写协调器红灯测试，证明问题原文传入门控、取消会继续抛出、分类异常映射为 `EVIDENCE_PROCESSING_FAILED`，然后实现：

```kotlin
fun interface RagEvidenceAcceptancePolicy {
    suspend fun accept(question: String, sources: List<RetrievedChunk>): List<RetrievedChunk>
}
```

协调器只传已经限制到 4096 code points 的 `boundedQuestion`，不得把问题或证据正文写入日志。

- [x] **Step 2：定义三分类契约并严格校验输入输出。** 先写缺失模型、空候选、重复 chunk、非有限概率和取消红灯测试，再实现：

```kotlin
enum class AnswerabilityLabel { SUPPORTED, PARTIAL, UNSUPPORTED }

data class AnswerabilityVerdict(
    val label: AnswerabilityLabel,
    val supportedProbability: Float,
    val modelSha256: String,
)

fun interface AnswerabilityClassifier {
    suspend fun classify(
        question: String,
        sources: List<RetrievedChunk>,
    ): AnswerabilityVerdict
}
```

概率必须有限且在 $[0,1]$，SHA-256 必须为 64 位小写十六进制；任何模型缺失、哈希不符、输出形状错误或推理异常都 fail-closed。

- [x] **Step 3：实现低成本级联策略。** 先写下列决策表红灯测试，再实现唯一生产决策路径：

| 条件 | 行为 |
|---|---|
| 结构无效、模型/语料版本不符 | 拒绝，不调用分类器 |
| 精确文件名、强编号、条款锚点 | 接受，不调用分类器 |
| 所有候选均低于保守 dense 下界且没有 lexical 命中 | 拒绝，不调用分类器 |
| 其余候选 | 只取排序后的 Top 3，一次批量/证据集合分类 |
| `SUPPORTED` 且 $p\ge\tau_{accept}$ | 接受参与分类的候选 |
| `PARTIAL` 或 `UNSUPPORTED` | 拒绝 |
| 分类器不可用、取消以外异常 | 拒绝；取消必须继续抛出 |

第一版不允许 high-dense 单独绕过分类器，因为“主题高度相似但没有答案”正是当前误放行根因。

- [ ] **Step 4：固定并验证多语言基线模型。** 先建立模型 manifest 和哈希红灯测试；候选基线为 Apache-2.0 的 `cross-encoder/mmarco-mMiniLMv2-L12-H384-v1`，但必须在独立工具链中微调为 `SUPPORTED/PARTIAL/UNSUPPORTED`，不能把通用重排分数冒充可回答性概率。导出前验证其 tokenizer 与现有 E5 tokenizer 的词表、特殊 token ID 和 normalizer 完全一致；不一致则随模型包携带独立 tokenizer。

- [ ] **Step 5：建立困难负样本。** 每个正问题至少生成并人工抽检三类负样本：实体/部门相同但所问字段缺失、时间/金额/编号被替换、问题前提在文档中不存在。训练集、阈值校准集和最终测试集按文档 ID 隔离，禁止同一文档模板跨集合泄漏；保留当前 320 条匿名合成语料并增加真实分布的脱敏回归样本。

- [ ] **Step 6：接入 INT8 ONNX 推理。** 输入最多 Top 3，合并后的 question + evidence 最大 256 tokens；一次 batch/session run 完成，关闭所有 `OnnxTensor` 和 result。模型文件下载到私有应用目录，使用 `.part`、长度上限、固定 SHA-256 和原子 rename；不得接受任意本地路径或未签名 manifest。

- [ ] **Step 7：真机性能选型。** vivo `V2359A` 上 CPU、NNAPI、NNAPI FP16 分别预热 5 次、测量 30 次，记录匿名聚合的 P50/P95、RSS 增量、失败次数和 10 分钟温升。只有同时满足以下闸门才启用：

$$
P95_{answerability,Top3}\le 500\text{ ms}
$$

$$
P95_{RAG\ total\ preprocessing}\le 1.5\text{ s}
$$

若 12 层多语言模型超预算，先蒸馏到 2 至 4 层双语浅模型再复测，不通过时保持 fail-closed，禁止静默退回 dense 阈值。

- [ ] **Step 8：重新校准并做规模不变性回归。** 同一问题/证据分别放入 1、10、40、500 文档知识库，断言最终 Answerability 决策一致；320 条集合要求 Recall@4 不低于 `0.90`、NoEvidence 精确率不低于 `0.95`，并单列 `SIMILAR_BUT_WRONG` 的拒绝率。只有独立保留集和普通单文档语义测试全部通过，才写入绑定模型 SHA、分类器 SHA、语料版本的生产 profile。

- [ ] **Step 9：提交。** `git commit -m "feat(rag): add cascaded answerability gating"`

### Task 6：句子级 Selective Content Reduction 和 token 预算

**Files:**
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/retrieval/EvidenceReducer.kt`
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/prompt/RagContextBudgeter.kt`
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/prompt/RagPromptBuilder.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/LlamaEngine.kt`
- Modify: `app/src/main/cpp/llama_jni.cpp`
- Test: `app/src/test/java/com/example/minicpm_v_demo/rag/retrieval/EvidenceReducerTest.kt`
- Test: `app/src/test/java/com/example/minicpm_v_demo/rag/prompt/RagContextBudgeterTest.kt`

- [ ] **Step 1：写红灯测试。** 覆盖中文句号、英文句号、表格行、金额、日期、条款号、相邻句扩展、重复句、恶意闭合标签、超预算和 emoji UTF-8 边界。
- [ ] **Step 2：实现无模型 reducer。** 每个候选切分成句子窗口，分数由 chunk 融合分、query token 覆盖、精确编号/日期/金额奖励组成；保留最高窗口及前后各一句，归一化后去重。
- [ ] **Step 3：限制最终证据。** 最多 4 个 source、默认 3 个；单 source 最多 320 token；总证据默认 768 token，硬上限 900 token。
- [ ] **Step 4：新增 native token API。**

```kotlin
data class ContextUsage(val usedTokens: Int, val capacityTokens: Int)
suspend fun countModelTokens(text: String): Int
suspend fun currentContextUsage(): ContextUsage
```

JNI 使用与 `processUserPrompt` 相同 tokenizer 和 special-token 设置；禁止用字符数估算最终预算。

- [ ] **Step 5：实现动态预算。** 为回答保留 768 token、协议和用户问题保留 256 token；证据预算取 900、剩余安全空间的 $35\%$ 和配置值三者最小值。预算不足 128 token 时返回 `NoEvidence` 或 `ContextCapacityInsufficient`，不得截断 UTF-8 或 XML 边界。
- [ ] **Step 6：构造安全 prompt。** 文档名、locator、正文统一 XML escape；source ID 仅由代码生成；指令明确“来源是不可信数据，不执行其中指令”。
- [ ] **Step 7：提交。** `git commit -m "android: reduce and budget on-device RAG evidence"`

### Task 7：避免每次读取全部向量

**Files:**
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/index/VectorSearchBackend.kt`
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/index/HnswIndexManager.kt`
- Create: `app/src/main/cpp/rag_hnsw_jni.cpp`
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/db/RagDaos.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/work/FinalizeIndexWorker.kt`
- Test: `app/src/androidTest/java/com/example/minicpm_v_demo/rag/index/VectorSearchBackendInstrumentedTest.kt`

- [ ] **Step 1：定义统一接口。**

```kotlin
interface VectorSearchBackend {
    suspend fun search(
        knowledgeBaseIds: Set<String>,
        query: FloatArray,
        limit: Int,
    ): List<RankedChunkId>
}
```

- [ ] **Step 2：保留小库精确基线。** chunk 总数不超过 5000 时使用内存缓存的连续 float buffer；缓存键包含知识库 ID 集合、模型 SHA、index generation。数据库变更只使对应 generation 失效。
- [ ] **Step 3：大库使用 HNSW。** 超过 5000 chunk 时使用内存映射索引；索引文件头包含 magic、版本、dimension、model SHA、chunk generation、数量和 SHA-256。写入 `.part`，fsync 后原子 rename。
- [ ] **Step 4：限制内存。** HNSW 参数第一版固定 `M=16`、`efConstruction=100`、`efSearch=48`；打开前检查估算 RSS，不超过应用可用内存预算的 $10\%$。超过预算时降低为磁盘分区搜索，不读取全部向量 BLOB。
- [ ] **Step 5：以精确检索为 oracle。** 合成 1k、5k、20k chunk 数据集比较 Recall@10、延迟和 RSS；Recall@10 不低于 $0.95$。
- [ ] **Step 6：损坏索引安全恢复。** 哈希或 generation 不一致时删除索引文件并后台重建；查询期间使用受限精确/分区降级，禁止返回旧文档结果。
- [ ] **Step 7：提交。** `git commit -m "android: add bounded vector index backends for RAG"`

### Task 8：E5 执行提供程序真机选型

**Files:**
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/embed/E5Embedder.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/embed/EmbeddingModelManager.kt`
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/embed/EmbeddingExecutionProfile.kt`
- Test: `app/src/androidTest/java/com/example/minicpm_v_demo/rag/embed/E5ExecutionProviderBenchmark.kt`

- [ ] **Step 1：建立可重复 benchmark。** CPU、NNAPI、NNAPI FP16 分别预热 5 次、测量 30 次；记录 P50/P95、RSS、温升、向量余弦一致性和失败次数。
- [ ] **Step 2：禁止盲目 NNAPI fallback。** Android 29+ 测试 `NNAPI_FLAG_CPU_DISABLED`；若模型发生大量分区或 P95 慢于 ORT CPU，配置回到 CPU。
- [ ] **Step 3：保存设备级选择。** key 由 Build.SOC_MODEL、Android API、模型 SHA 和 app version 组成；只保存 profile 枚举和统计摘要，不保存输入文本。
- [ ] **Step 4：保持 Session 常驻。** `EmbeddingModelManager` 单例拥有 session；后台超过 5 分钟且系统触发 trim memory 才释放，下一次知识查询懒加载。
- [ ] **Step 5：提交。** `git commit -m "android: select the fastest safe E5 execution profile"`

### Task 9：生命周期、取消和编辑一致性

**Files:**
- Modify: `app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/ConversationStore.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/RagTurnTransaction.kt`
- Test: `app/src/test/java/com/example/minicpm_v_demo/rag/RagTurnLifecycleTest.kt`
- Test: `app/src/androidTest/java/com/example/minicpm_v_demo/RagLifecycleInstrumentedTest.kt`

- [ ] **Step 1：写状态表测试。** 覆盖 Home、返回前台、旋转、来电式 pause、切换会话、删除会话、编辑用户问题、编辑 AI 回答、清空会话和模型切换。
- [ ] **Step 2：后台不立即取消生成。** `onStop()` 仅因配置变更或短暂切换时保留任务；真正结束 Activity、切换会话、编辑时间线或用户点击停止才取消。若产品坚持后台取消，必须先完整 rollback checkpoint 再持久化。
- [ ] **Step 3：用户编辑重答。** 截断旧回答及 citations，恢复稳定上下文到编辑点；该低频操作允许一次受控 replay，但记录耗时并显示“正在恢复会话”，且不能和 RAG turn 并发。
- [ ] **Step 4：AI 文本编辑。** 仅修改显示和稳定上下文；保持引用快照和 `answerEdited=true`，不重新检索。
- [ ] **Step 5：15 秒 watchdog。** 任一非生成阶段连续 15 秒无状态推进时取消，restore checkpoint，输出固定本地错误并恢复输入框；watchdog 不杀进程、不卸载模型。
- [ ] **Step 6：提交。** `git commit -m "android: make RAG turns lifecycle-safe and cancellable"`

### Task 10：UI 阶段反馈和来源展示

**Files:**
- Modify: `app/src/main/java/com/example/minicpm_v_demo/ChatAdapter.kt`
- Modify: `app/src/main/res/layout/item_ai_message.xml`
- Create: `app/src/main/res/layout/item_rag_source_chip.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/androidTest/java/com/example/minicpm_v_demo/rag/ui/RagAnswerUiTest.kt`

- [ ] **Step 1：阶段文案只显示可行动状态。** `正在检索知识库`、`正在整理依据`、`正在生成回答`；不显示内部模型名、文件路径或百分比伪进度。
- [ ] **Step 2：普通聊天不显示 RAG 阶段。** `NO_RETRIEVAL` 直接进入现有生成气泡，确保问候无额外 UI 延迟。
- [ ] **Step 3：来源 chip 使用归档快照。** 显示 `S1 · 文件名 · 定位`；点击时先查 DocumentEntity，存在则打开定位，不存在则显示“来源已删除”并继续展示摘录。
- [ ] **Step 4：超时可恢复。** watchdog 触发后 AI 气泡流式显示固定提示，`includeInModelContext=false`，发送按钮立即恢复。
- [ ] **Step 5：无障碍与视觉检查。** chip 整体可点击，contentDescription 包含来源编号和文件名；长名称省略，详情显示完整名称；淡蓝选中、绿色成功和红色持久失败风格与现有知识库页一致。
- [ ] **Step 6：提交。** `git commit -m "android: expose responsive RAG stages and sources"`

### Task 11：全链回归、性能门禁和灰度

**Files:**
- Create: `app/src/androidTest/java/com/example/minicpm_v_demo/rag/RagEndToEndPerformanceTest.kt`
- Create: `app/src/test/resources/rag/retrieval_cases.tsv`
- Modify: `README_MODIFIED_zh.md`
- Modify: `docs/superpowers/plans/2026-08-10-android-local-rag.md`

- [ ] **Step 1：执行功能矩阵。** 两知识库隔离、关闭零调用、问候零调用、无证据、弱相关、跨文档、图片+RAG、隐私确认、违法拒答、视觉无图保护、编辑回滚、删除来源和进程重启全部通过。
- [ ] **Step 2：执行 checkpoint 压力测试。** 连续 100 个成功 RAG turn、50 次取消、20 次前后台切换；checkpoint 数最终为 0，RSS 不持续增长，任何轮次都不调用 `fullReset()`。
- [ ] **Step 3：执行性能矩阵。** 空历史、10 轮、30 轮会话分别测试普通问候与 RAG；记录 P50/P95、TTFT、state 大小、RSS、CPU 和电量估算。结果必须满足第 3 节，否则不得标记稳定。
- [ ] **Step 4：签名和覆盖安装。**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:verifyInstallationSigning -x buildGgmlCpu_v86
& 'D:\Android\Sdk\platform-tools\adb.exe' install -r 'app\build\outputs\apk\debug\app-debug.apk'
```

禁止卸载、禁止清除数据；签名不一致立即停止。

- [ ] **Step 5：灰度开关。** `low_latency_rag_v1` 默认仅在 checkpoint 真机自检通过后开启；失败时本轮 RAG 返回固定不可用提示，普通聊天仍可使用。开关不得恢复旧的 full-reset-per-turn 路径。
- [ ] **Step 6：更新文档。** 记录 AUTO 路由语义、性能目标、引用行为、离线隐私和已知设备差异。
- [ ] **Step 7：提交。** `git commit -m "android: verify and document low-latency on-device RAG"`

## 6. 实施顺序与发布门槛

1. Task 0–1 可独立上线，立即解决问候误走 RAG，但不宣称 RAG 性能已完成。
2. Task 2 是架构闸门；checkpoint 正确性或大小不达标时停止 Task 3，不允许以 full reset 作为替代。
3. Task 3–4 完成后才允许重新启用 RAG 生成。
4. Task 5、Task 5A 与 Task 6 完成后，才允许把无证据普通回退与引用结果作为稳定功能。
5. Task 7 按真实知识库规模启用；小于 5000 chunk 时精确缓存可先交付。
6. Task 8 只能依据目标设备 benchmark 选择，不因“硬件加速”名称直接启用 NNAPI。
7. Task 9–11 全部通过后，README 才可将 RAG 从“开发中”改为“测试版”。

## 7. 回滚策略

- Kotlin 路由、混合检索和 reducer 可通过 `low_latency_rag_v1` 关闭。
- native checkpoint 自检失败时只关闭 RAG 生成，普通聊天保持现有稳定 context；禁止切回每轮 full reset。
- HNSW 文件可删除重建，Room chunk/embedding 仍是事实来源。
- schema 和会话归档不因本计划降级；引用快照继续可读。
- 任一发布版本出现 checkpoint 泄漏、恢复不一致或错误引用，立即关闭 RAG 开关并保留文档数据，修复后覆盖安装。

## 8. 自检清单

- [ ] 普通问候路径没有 E5、chunk DAO、checkpoint、fullReset 调用。
- [ ] RAG 路径没有重放全部历史。
- [ ] checkpoint 包含 context、recurrent/hybrid memory、sampler clone 和 JNI bookkeeping。
- [ ] 图片预填发生在 checkpoint 前，恢复后仍能形成稳定视觉历史。
- [ ] 增强 prompt 不作为视觉意图分类输入。
- [ ] 无证据路径使用未经修改的用户原文调用 MiniCPM，且不携带证据、引用或额外提示；技术失败路径不调用 MiniCPM。
- [ ] 证据不超过 900 token，来源不超过 4 个。
- [ ] 伪造来源编号不会进入 `AiMessage.citations`。
- [ ] 日志不包含问题、文档正文、文件名、电话、地址或身份证号。
- [ ] 后台、取消和异常不会遗留 checkpoint 或永久禁用输入框。
- [ ] 所有设备安装先通过 `verifyInstallationSigning`，只使用 `adb install -r`。

## 9. 2026-08-18 双三分类器检查点

当前实现基线为分支 `codex/rag-all-queries-experiment`、提交 `1f0b016`。本检查点只建立
Answerability 与 Groundedness 的共享契约和训练数据，不提前改变现有 App 运行路径。

- [x] 新增 `rag/guard/RagGuardClassifier.kt`，固定一个共享编码骨干、两个独立分类头的接口。
- [x] Groundedness 标签固定为 `GROUNDED/PARTIAL/UNGROUNDED`，概率和模型 SHA-256 严格校验。
- [x] 新增 `RagOutputReviewPolicy`：有依据立即接受；部分或无依据最多重生成一次；再次失败使用不进入上下文的本地提示。
- [x] 新增匿名合成数据生成器，第一版各生成 3000 条 Answerability 和 Groundedness 样本。
- [x] 数据按 `document_id` 固定划分 train/calibration/test，并包含字段缺失、相似但不可回答、混合支持和错误数字等困难负样本。
- [x] 历史绕过、伪引用、文档提示注入和文字资料视觉描述保存为 test-only 回归种子。
- [x] 原 320 条语料继续作为检索评测集；由于缺少回答级标签，不将检索相关性标签伪装成三分类训练标签。
- [x] 已在单张 RTX 4090 上微调 `multilingual-e5-small` 共享编码骨干和两个三分类头；固定种子 42、BF16、4 epochs，Safetensors SHA-256 为 `9e2166a86487fec359eb36de69a08165eff9b6d2561a609942bc852af8fd05e6`。
- [x] 合成测试集两个任务 macro-F1 均为 1.0；以 ECE 打破同 F1 检查点并列后，测试集 Answerability ECE 为 0.0547、Groundedness ECE 为 0.0602。该结果只证明训练管线和标签契约可学习，不视为真实办公分布的上线结论。
- [x] 已导出单个 INT8 ONNX 模型包：118,169,267 bytes，SHA-256 为 `45d42125648c169a19697ce8b64f6883e63c2d8a45fd666c73bf163a3c59e097`。量化覆盖 `MatMul/Gemm/Gather`，压缩比 0.2513，INT8/FP32 标签一致率 0.9984，现有 calibration/test/test-only 回归集最大 macro-F1 降幅为 0.0；模型和完整导出审计文件已固化至训练机持久存储。
- [ ] 补充脱敏真实办公分布样本并完成独立质量门槛；当前合成数据和 test-only 种子不能替代真实分布验收。
- [x] 已在 Android 端实现固定 manifest、文件长度与 SHA-256 校验、E5 tokenizer SHA 绑定、训练输入格式复现、保留结束 token 的 256-token 截断、共享 session 双任务推理、稳定 softmax 解码、单实例缓存和资源关闭，并通过对应 JVM 单元测试。`MiniCPMApplication` 暴露惰性管理器但在质量闸门通过前不预加载 Guard；检索接受策略仍保持 `classifier=null/profile=null` 上线闸门。
- [x] 已将固定 E5 INT8 模型包从本机精确缓存复制到独立备份 `D:\MiniCPM-V\artifacts\multilingual-e5-small-int8-pinned-132949c958b5`，并记录逐文件长度与 SHA-256；Guard INT8 模型包保存在 `D:\MiniCPM-V\artifacts\rag-guard-dual-head-v2`。两套模型均经设备临时目录、应用私有 `.part` 目录和最终目录三段 SHA-256 校验后原子恢复到 vivo `V2359A`，未卸载应用、未清除会话或知识库数据。
- [x] E5 真机仪器测试 1/1 通过。Guard 使用 ORT CPU、2 个 intra-op 线程，模型打开耗时 `1385.750 ms`；30 次 Answerability 推理 P50/P95 为 `9.905/12.814 ms`，Groundedness 推理 P50/P95 为 `13.062/17.906 ms`，失败数为 0，测试进程 PSS 增量为 `239199 KB`。延迟满足单次推理门槛，但内存增量及真实办公分布质量仍未达到生产启用条件。
- [x] 顶层 Gradle 已禁止 `connectedCheck` 和所有 `connected*AndroidTest` 任务，避免 Android Gradle Plugin 自动卸载测试目标并连带清除应用数据；真机测试固定采用 `assembleDebugAndroidTest`、主/测试 APK `adb install -r` 和 `scripts/run-device-instrumentation.ps1`。保护脚本 `scripts/test-connected-device-test-guard.ps1`、全量 JVM 测试、主 APK、测试 APK及固定签名校验均已通过。
- [x] 新增 `tools/rag_guard/score_office_holdout.py`、`quality_gate.py` 和独立办公分布验收说明：评分器使用与 Android 相同的 `tokenizer.onnx`，验证 manifest、长度和 Guard/tokenizer SHA-256，并复现结束 token 保留截断；校准集与最终测试集严格分离，且与训练文档 ID 两两隔离。门槛工具只输出聚合指标，默认要求 Answerability 精确率/召回率不低于 `0.95/0.90`、Groundedness macro-F1 不低于 `0.85`、ECE 不高于 `0.10`。Windows 现有 `.rag-python-tools` CPU 环境已用真实 tokenizer 和 Guard INT8 ONNX 完成匿名样本端到端评分：Answerability `SUPPORTED=0.9415`、Groundedness `GROUNDED=0.9067`，概率和均为 1；无需显卡、CUDA、PyTorch或新环境。当前尚无脱敏真实办公评测数据，因此生产质量验收仍未完成。
- [ ] 模型通过离线质量和真机性能门槛后，才替换 `MiniCPMApplication` 中的 `classifier=null/profile=null`。
