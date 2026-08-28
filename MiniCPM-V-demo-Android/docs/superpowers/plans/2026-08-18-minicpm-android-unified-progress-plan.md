# MiniCPM Android 统一进度与后续实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 MiniCPM Android 应用的历史功能计划、端侧 RAG 总体方案、低延迟重构、三分类加四分类 Guard 训练和真机验证统一为唯一活动进度文档，准确区分已经完成、已经验证和仍未完成的工作。

**Architecture:** 应用继续使用 MiniCPM/llama.cpp-omni 作为有状态生成引擎；本地 RAG 使用 SAF、WorkManager、Room/SQLCipher、加密原文、FTS4、E5 INT8 ONNX、dense + lexical RRF、临时 native checkpoint 和引用快照。Guard 外部契约为 Answerability 三分类和 Groundedness 四分类，共用一个 INT8 ONNX 编码骨干和两个任务头。v4.2 E5 已按产品决定进入正式路径；性能评测只如实记录，不阻断模型接入，制品完整性和签名检查继续强制执行。

**Tech Stack:** Kotlin、Java、C++17/JNI、Android SDK 36、JDK 21、Gradle 9.6.1、Room、SQLCipher、WorkManager、ONNX Runtime Android、ONNX Runtime Extensions、PDFBox、ML Kit、JUnit 4、Android instrumentation。

> **2026-08-20 增量：** `9b229c2` 已完成单文档长按删除、失败导入无持久文档记录、失败提示左滑移除和同名/同内容重传；本轮继续完成来源 Chip 的当前索引块定位、来源删除状态和归档摘录降级。

> **2026-08-20 阶段 UI 增量：** `RagCoordinator` 已增加真实 `RETRIEVING/ORGANIZING` 回调，AI 占位气泡增加不入归档的 `RETRIEVING/ORGANIZING/GENERATING` 内存态；规划和 Groundedness 分类分别使用 15 秒边界，分类超时沿现有 checkpoint 路径降级普通回答。

> **2026-08-20 checkpoint 压力增量：** native 调试快照已增加只读活动 checkpoint 计数；vivo V2359A 真机通过 100 次恢复、50 次取消释放和 20 次生产 `MainActivity.onStop()` 取消矩阵，最终活动数均为 0。checkpoint 大小 20,546,716 bytes，保存 P95 19.036 ms、恢复 P95 16.599385 ms；编辑用户消息会截断生成中 RAG 尾部，切换后的另一会话保持隔离。

> **2026-08-20 大库后端增量：** 已增加 `VectorSearchBackend`、`VectorEmbeddingSource` 和 `ExactVectorSearchBackend`，`RoomDenseEvidenceRetriever` 已通过统一接口保留 5000 chunks 连续缓存与 1000-row 分页精确降级；分页结果与 exact oracle 一致。HNSW sidecar、原子 generation、损坏恢复和 1k/5k/20k benchmark 仍待完成。

> **2026-08-24 全链增量：** `ALL_QUERIES` 的 Ready/NoEvidence/NoSelection 真机闭环、真实 MiniCPM token 预算、E5 CPU 执行配置、1k/5k/20k HNSW 基准、固定签名覆盖安装持久化和 0/10/30 轮 TTFT 矩阵均已通过。Groundedness 真机矩阵发现错误金额与错误日期被高置信误判为 `GROUNDED`；按产品决定只记录为最终重训阻塞项，不增加应用层数字规则，也不继续刻意试探模型边界。

> **2026-08-24 人工验收闭环：** 用户确认图片预处理/删除/原图/视觉推理，以及旋转、前后台、pause/resume、会话编辑和键盘交互两组真机人工验收通过。应用工程、UI、生命周期和发布基础设施已闭环；剩余事项全部归入 Guard 模型数据、训练、导出、profile 固定与训练后复测工作流。

> **2026-08-26 v4.1 训练前闭环：** 已修复 Groundedness 候选答案被长证据右截断、HoVer `NOT_SUPPORTED` 二合一标签误映射、固定元答案捷径和新增困难类型未完整进入 pair/hard-slice 的问题。独立 v4.1 共 270,000 行，train/calibration/test 为 `243,367/13,693/12,940`；受保护输入超限、不可信 HoVer 冲突和跨 split family 泄漏均为 0，候选及切分后 release 审计通过。训练主机断开，当前精确暂停在一轮 E5 烟雾训练前。

> **2026-08-28 v4.2 正式接入：** calibration-only 五轮 A/B 选择 E5；FP32/INT8 导出、三加四分类 Android 契约、生成 asset、私有目录原子安装、JVM/Python 回归、固定签名、APK 内容和 vivo V2359A 真机验收完成。正式 INT8 为 `118,171,779` bytes，SHA-256 `d674ef4ef4fb2b4dce37d43c46eeb4b0e8038eb66da7cde1b568ca78dc45e1c2`。量化性能指标仅记录、不作为接入门控；frozen test 保持未读。覆盖安装保留全部用户数据并受控迁移 Guard；30 次双头推理稳定。

> **2026-08-20 HNSW 边界增量：** 已实现有界元数据 codec、严格 UTF-8、corpus generation 匹配、SHA-256 命名的受控路径、单次流式长度/摘要校验和应用内存预算 10% 的 RSS 准入；截断、尾随字节、路径穿越、摘要不一致和超预算测试均通过。native HNSW 与认证原子发布尚未接入。

---

## 1. 文档权威性与使用规则

从 2026-08-18 起，本文是项目功能进度和后续开发顺序的唯一活动计划。

- 历史计划保留用于追溯需求、设计取舍和旧测试，不再单独更新完成度。
- 若历史计划与本文冲突，以本文、当前代码和最近真机证据为准。
- 架构决策和威胁模型继续有效，但不承担进度跟踪职责。
- 每完成一个后续任务，必须同步更新本文的状态表、验收证据和剩余工作。
- “代码存在”不等于“可以发布”；必须分别记录实现状态、JVM 测试、真机测试、生产启用状态。

### 1.1 状态定义

| 状态 | 含义 |
|---|---|
| `COMPLETED` | 功能已实现，相关自动化测试和必要真机验证通过，已进入当前运行路径 |
| `VERIFIED` | 已在目标 vivo `V2359A` 或固定桌面工具链完成真实模型/文件/流程验证 |
| `IMPLEMENTED_NOT_ENABLED` | 代码、模型或策略已存在，但生产开关或质量闸门仍关闭 |
| `PARTIAL` | 主要路径可工作，但缺少性能、异常、规模或 UI 完整性 |
| `NOT_STARTED` | 当前代码中尚无对应生产实现 |
| `BLOCKED_BY_DATA` | 工具链已完成，但缺少经过授权和脱敏的真实分布数据 |
| `ARCHIVED` | 历史计划已被本文吸收，不再作为活动任务清单 |

## 2. 历史计划归并结果

### 2.1 已完成并归档的基础功能计划

以下计划的目标已经体现在当前代码和测试中。旧文档中的未勾选框不再代表当前完成度。

| 历史计划 | 统一状态 | 当前结果 |
|---|---|---|
| `2026-07-31-android-camera-pending-image.md` | `ARCHIVED / COMPLETED` | 拍照入口、图片缓存、预处理进度、原图查看和预处理期间删除已接入 |
| `2026-08-03-android-status-download-image-viewer.md` | `ARCHIVED / COMPLETED` | 状态栏常驻、模型下载返回前台不重复提示、原图查看已接入 |
| `2026-08-03-unified-chat-settings-and-no-image-research.md` | `ARCHIVED / COMPLETED` | 左上角统一设置、视觉上下文状态和无图视觉请求保护已接入 |
| `2026-08-03-visual-context-guard.md` | `ARCHIVED / COMPLETED` | `hasVisualContext` 生命周期、输入视觉意图保护和快捷入口状态已接入 |
| `2026-08-04-local-streaming-guard-reply.md` | `ARCHIVED / COMPLETED` | 本地拦截提示以模拟流式 AI 消息显示，且不进入模型上下文 |
| `2026-08-04-semantic-visual-output-guard.md` | `ARCHIVED / PARTIAL` | 视觉输入/输出语义保护已存在；RAG Groundedness 输出复核属于新的未完成生产路径 |
| `2026-08-05-inline-privacy-input-confirmation.md` | `ARCHIVED / COMPLETED` | 隐私输入在用户气泡下确认，拒绝后删除且不调用模型 |
| `2026-08-05-local-content-safety-stage-two.md` | `ARCHIVED / COMPLETED` | `ALLOW/WARNING/BLOCK/REVIEW` 策略、隐私检测和违法内容固定流式拒答已接入 |
| `2026-08-06-conversation-history-editing.md` | `ARCHIVED / COMPLETED` | 多会话、消息删除、用户消息修改重答和 AI 消息编辑已接入 |
| `2026-08-06-persistent-conversations.md` | `ARCHIVED / COMPLETED` | 版本化会话归档、原子保存、重启恢复和应用私有图片持久化已接入 |
| `2026-08-07-flexible-message-editing.md` | `ARCHIVED / COMPLETED` | 用户消息截断重答、AI 文本只改显示与上下文、整气泡长按已接入 |

### 2.2 RAG 计划归并

| 历史计划 | 统一状态 | 保留价值 |
|---|---|---|
| `2026-08-10-android-local-rag.md` | `ARCHIVED / SUPERSEDED` | 保留总体架构、数据模型、文件安全、解析格式和原始验收目标 |
| `2026-08-14-android-rag-low-latency-refactor.md` | `ARCHIVED / SUPERSEDED` | 保留 native checkpoint、RagCoordinator、混合检索、级联门控和性能门槛的实施历史 |

以下支持文档继续有效：

- `docs/architecture/ADR-001-local-rag-stack.md`
- `docs/architecture/rag-threat-model.md`
- `docs/execution/evidence/rag-retrieval-calibration-20260817.md`
- `tools/rag_guard/OFFICE_QUALITY_GATE.md`

## 3. 当前基线

| 项目 | 当前值 |
|---|---|
| 分支 | `codex/rag-all-queries-experiment`（分支名保留，但 `ALL_QUERIES` 已确定为正式产品行为） |
| 已提交基线 | `c7c6d25f873f6b1a05e7be3a59cebf2c48f45110` |
| 工作树 | 正在固化全量检索交付契约；HNSW 主体、来源生命周期、Guard、检索和导入删除改动均已提交 |
| Android 包名 | `com.example.minicpm_v_demo` |
| 目标真机 | vivo `V2359A` |
| 安装规则 | 先执行 `verifyInstallationSigning`，只允许 `adb install -r`，禁止自动卸载和清除数据 |
| E5 模型 | `multilingual-e5-small` INT8，384 维，固定文件 SHA-256 |
| Guard 模型 | v4.2 E5 正式双头 INT8 ONNX，118,171,779 bytes，SHA-256 `d674ef4ef4fb2b4dce37d43c46eeb4b0e8038eb66da7cde1b568ca78dc45e1c2`；性能指标如实记录，不设接入门控 |
| 当前 RAG 模式 | `ALL_QUERIES` 正式行为：选中知识库后所有问题检索，只有证据通过门控才增强回答 |
| 当前正式 Guard | Answerability 使用 `CurrentAnswerabilityCalibration.profile`；Groundedness 使用 `CurrentGroundednessCalibration.profile`。UNSUPPORTED 回退普通回答，CONTRADICTED 直接使用知识库摘录，PARTIAL 最多纠偏一次 |

## 4. 完成度总览

完成度是工程估算，不使用历史计划中已经失真的勾选数量直接计算。

| 口径 | 当前完成度 | 说明 |
|---|---:|---|
| 基础 App 历史需求 | `100%` | 状态栏、图片、设置、安全、多会话、持久化、编辑、键盘与人工 UI 验收均完成 |
| RAG 基础闭环 | `100%` | 手机导入、解析、切块、向量化、全量检索、临时注入、输出审查、普通回答回退和引用归档均完成 |
| RAG 完整办公发布目标 | `100%` | v4.2 E5 正式模型、APK、离线回归和真机专项验收完成；真实办公评测继续作为非阻断观测 |
| 项目整体正式发布准备度 | `100%` | 正式 Debug APK 已覆盖安装并完成模型身份、持久性和双头推理验收 |

### 4.1 RAG 子系统状态

| 子系统 | 完成度 | 状态 | 关键结论 |
|---|---:|---|---|
| 数据库、迁移和加密 | `95%` | `COMPLETED` | Room schema、SQLCipher、Keystore、加密原文、迁移和防备份已实现 |
| 知识库 UI | `95%` | `COMPLETED` | 创建、命名、淡蓝选择、阶段状态、知识库删除、单文档长按删除、失败提示左滑移除及同名重传已实现并验收 |
| 文件导入与恢复 | `90%` | `COMPLETED` | SAF、WorkManager、取消、失败原因、恢复和原子文件流程已实现 |
| 文档解析 | `90%` | `COMPLETED` | TXT、Markdown、CSV、HTML、PDF/OCR、DOCX、PPTX、XLSX 已有解析器和限额 |
| 切块与嵌入 | `90%` | `VERIFIED` | 结构化 chunk、中文 bigram、E5 tokenizer、INT8 embedding 和真机推理已通过 |
| 混合检索 | `90%` | `VERIFIED` | FTS4 BM25、dense、RRF、SQL 过滤及实验 Answerability profile 已接入全量检索路径；等待真实分布最终阈值 |
| RAG 状态协调 | `90%` | `COMPLETED` | Disabled、NoSelection、Indexing、NoEvidence、Ready 和匿名失败已统一 |
| 临时上下文事务 | `90%` | `VERIFIED` | native checkpoint 保存/恢复、取消恢复、视觉 checkpoint 和证据不残留已验证 |
| 引用归档与校验 | `90%` | `PARTIAL` | 引用白名单、不可变快照、来源 chip、当前索引块定位和来源删除归档状态已实现；外部二进制文件页/单元格深链为后续增强 |
| Answerability 门控 | `100%` | `PRODUCTION_VERIFIED` | v4.2 E5、Android runtime、正式 profile、模型哈希、离线回归和 30 次真机推理通过 |
| Groundedness 输出审查 | `100%` | `PRODUCTION_VERIFIED` | 四分类、候选隐藏、一次同证据重生成、知识库摘录替换、技术故障普通回答和真机推理已接入验证 |
| 证据压缩和 token 预算 | `100%` | `VERIFIED` | 句子窗口缩减、跨来源去重、真实模型 token 计数、动态预算及真机 token 对齐已通过 |
| 大知识库向量索引 | `100%` | `VERIFIED` | native HNSW、认证原子 generation、上一代恢复、损坏精确降级、后台重建、5001 向量闭环、1k/5k/20k 基准及四个真实 force-stop 窗口均完成 |
| 生命周期和 watchdog | `100%` | `VERIFIED` | 规划/审查超时、后台取消、编辑前 cancel-and-join、100/50/20 自动矩阵及旋转、前后台、pause/resume 人工验收完成 |
| 性能/压力/灰度发布 | `100%` | `VERIFIED` | checkpoint、E5、Guard、HNSW、0/10/30 轮 TTFT、四窗口 force-stop、运行时灰度降级和覆盖安装均完成；模型质量单列管理 |

## 5. 已完成内容

### 5.1 基础 App 与办公安全

- 系统状态栏永久显示，App 不占据最上方系统区域。
- 模型下载切后台再返回时不会重复弹出“未下载模型”。
- 设置统一在左上角，模型管理、图片切片、会话和知识库均从统一入口进入。
- 聊天输入区支持相册和拍照；图片先缓存并预处理，处理期间变暗、显示圆形进度和提示。
- 图片预处理期间和完成后均可删除；输入区和聊天气泡可打开缓存原图。
- `hasVisualContext` 随图片成功写入、清空会话、切换/卸载模型正确变化。
- 无图视觉依赖问题会被本地拦截；本地提示模拟流式 AI 输出且 `includeInModelContext=false`。
- 隐私文本先在用户气泡下确认，只有明确选择“是”才发送给模型。
- 违法内容固定拒答；隐私、电话、身份证号和地址类内容进入 WARNING/REVIEW/BLOCK 规则。
- 多会话、永久存储、删除消息、用户消息修改重答、AI 消息编辑和会话回滚已实现。

### 5.2 RAG 数据、导入和索引闭环

- 用户可创建并命名不同知识库，名称规范化后防重名。
- 知识库选择使用淡蓝背景，不使用勾号；删除知识库有二次确认。
- SAF 支持一次选择多个文档，导入任务使用唯一 WorkManager 工作链。
- 文档状态覆盖复制、解析、OCR、切块、嵌入、最终 READY、失败、取消和恢复。
- 成功导入保留正常状态显示并支持长按二次确认删除；失败导入清理实际文件和文档记录，仅在页面显示可左滑移除的匿名原因，同名/同内容可再次上传。
- 文件类型同时使用扩展名、MIME 和魔数检测，避免仅凭 TXT 扩展名误判。
- 原始文档复制到应用私有隔离区并加密；数据库使用 SQLCipher，密钥由 Android Keystore 保护。
- 文档解析、chunk 和 embedding 受文件大小、页数、行数、解压大小、token 和维度上限保护。
- E5 模型包使用固定长度和 SHA-256，`.part` 写入后原子替换。
- Worker 链为 `ImportCopyWorker -> ParseWorker -> OcrWorker -> ChunkWorker -> EmbedWorker -> FinalizeIndexWorker`。
- 只有 chunk 与 embedding 完整且模型哈希一致时，文档才进入 READY。

### 5.3 检索、注入和无结果行为

- 当前会话可独立开启/关闭 RAG，并选择一个或多个知识库。
- 空选择永远不解释为“查询全部知识库”。
- FTS4 通过安全转义后的绑定参数查询，Kotlin 解析 `matchinfo` 并计算 BM25。
- dense 和 lexical 候选通过稳定 RRF 融合，限制每路候选数和单文档候选数。
- `RagCoordinator` 是唯一状态决策入口。
- `NoEvidence` 使用原始用户问题正常调用模型，不显示“知识库未命中”固定回复，不注入候选和引用。
- RAG Ready 路径使用 native checkpoint 临时追加证据；生成结束、取消或异常后恢复稳定上下文。
- 增强 prompt 不参与无图视觉意图判断；已确认 RAG 文本不会被误识别为图片描述请求而拦截。
- 证据来源编号由代码生成，伪造或越界 `[Sx]` 不会写入会话归档。
- 会话归档 v2 可持久保存引用快照、`ragRunId` 和 `answerEdited`。

### 5.4 三分类加四分类 Guard 与质量工具链

- v4.2 共享 `multilingual-e5-small` 编码骨干，训练 Answerability 三分类头和 Groundedness 四分类头；五轮 E5/NLI calibration-only A/B 已完成并选择 E5。
- Answerability 标签为 `SUPPORTED/PARTIAL/UNSUPPORTED`。
- Groundedness 标签为 `GROUNDED/PARTIAL/UNSUPPORTED/CONTRADICTED`。
- 训练集、校准集和测试集按 `document_id` 隔离；历史绕过、伪引用和提示注入作为 test-only 回归种子。
- 合成测试集两个任务 macro-F1 均为 1.0，但不作为真实办公上线结论。
- 正式 v4.2 INT8/FP32 calibration 标签一致率为 `0.9693585127`，最大 macro-F1 降幅为 `0.0107869130`，压缩率为 `0.2512633907`；仅记录，不作为接入门控。
- v4.1 训练输入为受保护句对：问题与候选答案完整保留，仅 evidence 允许在 256-token 预算内截断；Android runtime 必须逐 token 复现该格式、结束 token 和稳定 softmax。
- Guard 真机 CPU 打开耗时 `1385.750 ms`；Answerability P50/P95 为 `9.905/12.814 ms`；Groundedness P50/P95 为 `13.062/17.906 ms`；30 次无失败。
- Guard 测试进程 PSS 增量为 `239199 KB`，因此当前禁止 App 启动时预加载。
- `score_office_holdout.py` 使用与 Android 相同的 `tokenizer.onnx` 评分。
- `quality_gate.py` 检查人工脱敏标记、手机号/身份证号、文档隔离、模型哈希和聚合质量指标。
- Windows 现有 `.rag-python-tools` CPU 环境已完成真实 tokenizer + Guard ONNX 匿名样本端到端评分，不需要显卡或 CUDA。

### 5.5 构建、安装和数据保护

- 主 APK、AndroidTest APK、JVM 单元测试和 `verifyInstallationSigning` 已通过。
- 主 APK 与测试 APK 使用相同固定证书。
- 顶层 Gradle 禁止 `connectedCheck` 和所有 `connected*AndroidTest`，防止测试插件自动卸载应用并清空数据。
- 真机测试固定使用构建测试 APK、`adb install -r` 和 `scripts/run-device-instrumentation.ps1`。
- E5 和 Guard 模型均有本机独立备份，并在复制到设备的每个阶段验证 SHA-256。

## 6. 已实现但不能启用的内容

### 6.1 Answerability

`OnnxRagGuardClassifier`、模型管理器、级联接受策略、惰性适配器和质量门槛工具已经完成。当前 `MiniCPMApplication` 已接入：

```kotlin
classifier = LazyAnswerabilityClassifier(ragGuardModelManager::openInstalled)
profile = CurrentAnswerabilityCalibration.profile
```

`CurrentAnswerabilityCalibration.profile` 仍固定为 `null`。这意味着当前生产路径仅放行精确文件名、编号、条款等锚点；普通语义相似证据不会仅凭 dense 分数进入 prompt，惰性分类器也不会打开约 239 MB PSS 的 Guard session。必须先通过真实脱敏办公校准集和独立测试集。

### 6.2 Groundedness

`RagOutputReviewPolicy` 已定义：

1. `GROUNDED`：接受回答。
2. `PARTIAL/UNGROUNDED` 且尚未重生成：最多重生成一次。
3. 第二次仍明确失败：丢弃两次模型草稿，直接使用带来源编号的知识库摘录作为本轮回答。
4. 分类器缺失、超时、模型 SHA 不匹配或 checkpoint 异常：恢复 checkpoint，使用原始用户问题执行普通生成。

该策略已接入 `MainActivity` 的真实 RAG 候选隐藏、一次同证据重生成、知识库摘录替换和技术故障普通回答降级事务；知识库页面常驻提示“冲突时知识库优先”，并要求用户确保导入文档准确有效。因 v3 质量门槛未通过，仍只能声明为实验路径，不能标记为稳定生产审查。

### 6.3 ALL_QUERIES 正式检索模式

当前产品行为固定为：

```kotlin
retrievalMode = RagRetrievalMode.ALL_QUERIES
```

只要当前会话启用了并选择了 READY 知识库，所有问题都先检索。检索本身不等于注入：Answerability 和证据预算通过后才生成 RAG 候选；Groundedness 明确拒绝的候选最多纠偏一次，再失败则用带来源编号的知识库摘录替换。无证据、模型缺失、索引未就绪或技术失败使用未经修改的用户原文普通生成。未选择知识库或关闭会话 RAG 时不检索。

不再开发 `ADAPTIVE` 正式路由、普通问题意图分类器或“问候零 E5”发布门槛；保留现有路由代码仅用于历史回归和可能的低端设备兼容实验，不参与当前生产配置。

## 7. 未完成内容

### 7.1 真实办公分布质量观测

状态：`OPTIONAL_NON_BLOCKING`。

尚未取得经过授权、人工脱敏、按文档隔离的办公 Answerability/Groundedness 校准集和最终测试集。该数据到位后继续用于观察真实分布表现，但不阻止当前 v4.2 正式模型随 APK 发布。

后续观测目标为：

- Answerability 精确率不低于 `0.95`。
- Answerability 召回率不低于 `0.90`。
- Groundedness macro-F1 不低于 `0.85`。
- Groundedness ECE 不高于 `0.10`。
- `SIMILAR_BUT_WRONG`、错误金额、错误日期、字段缺失和前提不存在必须单独统计。
- 训练、校准和最终测试文档 ID 必须两两不相交。

### 7.2 Groundedness 输出生产接入

- 仅在 `RagTurnPlan.Ready` 且实际注入了证据时运行。
- 审查输入必须是用户原文、最终使用的证据快照和候选回答。
- 审查不能读取会话中未选择的知识库。
- 最多重生成一次；第二次明确内容审核失败不显示固定提示，直接改用带来源编号的知识库摘录；只有分类器、模型或 checkpoint 技术故障才恢复后走普通模型回答。
- 被拒绝的候选和修正 prompt 不写入稳定历史。
- 取消、异常、切后台均必须恢复 checkpoint。

### 7.3 句子级证据缩减和真实 token 预算

当前句子级 reducer、真实 native token 计数和动态预算已经接入并完成真机对齐：

- 中文/英文句子、表格行和条款边界切分。
- query token 覆盖、编号、日期和金额奖励。
- 相邻句扩展和跨来源去重。
- 单来源最多 320 token。
- 默认总证据 768 token，硬上限 900 token。
- 给回答至少预留 768 token，协议和问题至少预留 256 token。
- XML escape、source ID 代码生成和文档提示注入声明。

### 7.4 大知识库向量后端

当前精确向量搜索作为小库正确性基线，超过 5000 chunks 使用 HNSW；主体和规模基准已完成：

- [已实现] 小于等于 5000 chunks 时使用连续 float buffer 缓存，不重复从 Room 解码全部 BLOB。
- [已实现] 大于 5000 chunks 时使用 HNSW，拒绝 sidecar 时分页精确降级。
- [已实现] 索引头绑定模型 SHA、语料 generation、维度、数量和文件 SHA-256。
- [已实现] `.part + fsync + atomic rename`、认证元数据和上一代恢复。
- [已实现] 索引损坏或 generation 不一致时后台重建并禁止返回旧文档。
- [已验证] 1k、5k、20k chunks 的 Recall@10、P50/P95、构建时间、文件体积和 RSS 对照。

### 7.5 E5 执行提供程序和内存策略

- [已验证] CPU、NNAPI、NNAPI FP16 分别预热 5 次、测量 30 次，并记录 P50/P95、失败数、RSS 和向量余弦一致性。
- [已固定] vivo V2359A 上 NNAPI 慢于 ORT CPU，生产配置固定使用 CPU。
- [已实现] E5 session 按真实使用、5 分钟后台超时和系统 trim memory 释放。
- [已实现] E5/Guard 禁止启动预加载，仅在实际检索或分类时懒加载。

### 7.6 生命周期、编辑和超时恢复

- [已验收] Home、返回前台、旋转、来电式 pause、切换会话、删除会话、编辑消息和模型切换状态矩阵。
- [已验证] RAG turn 与用户编辑/删除不能并发修改同一时间线。
- [已验证] 用户问题编辑重答清除旧答案和旧引用，并恢复稳定上下文到编辑点。
- [已验证] AI 文本编辑保留引用快照并设置 `answerEdited=true`。
- [已验证] 任一非生成阶段连续 15 秒无进展时触发 watchdog，恢复 checkpoint、恢复输入框并安全降级。
- [已验收] 键盘展开保持当前底部视觉锚点，滑动/长按不收键盘，点击对话区才收起；末条消息使用 12dp 对话间距。

### 7.7 来源和阶段 UI

- [已实现] `正在检索知识库`、`正在整理依据`、`正在生成回答` 三类真实阶段；普通 Disabled/NoRetrieval 不显示，阶段字段不进入归档或模型上下文。
- 普通聊天不显示 RAG 阶段。
- [已实现] AI 气泡下显示 `S1 · 文件名 · 定位` 来源 chip；点击后按 `documentId + chunkId` 定位当前索引原文。
- [已实现] 来源删除后继续显示回答时的归档摘录并标记“来源已删除”；索引不匹配单独标记“当前索引不可用”。
- chip 整体可点击、有 contentDescription、长名称省略且详情可查看完整名称。

### 7.8 全链验收和发布

- 两知识库隔离、关闭零调用、问候零调用、无证据、弱相关、跨文档、图片 + RAG。
- 隐私确认、违法拒答、无图保护、RAG 视觉优先级和文档提示注入。
- 编辑回滚、删除来源、进程重启、模型丢失和数据库迁移。
- 连续 100 个成功 RAG turn、50 次取消、20 次前后台切换。
- 空历史、10 轮、30 轮历史的普通聊天和 RAG P50/P95/TTFT/RSS。
- `low_latency_rag_v1` 灰度开关和失败降级。
- README、改版说明、模型来源、设备差异和隐私边界更新。

## 8. 后续唯一执行顺序

### Task 1：真实办公质量观测

**Files:**
- Existing: `tools/rag_guard/score_office_holdout.py`
- Existing: `tools/rag_guard/quality_gate.py`
- Existing: `tools/rag_guard/OFFICE_QUALITY_GATE.md`
- Modify after passing: `app/src/main/java/com/example/minicpm_v_demo/MiniCPMApplication.kt`
- Test: `app/src/test/java/com/example/minicpm_v_demo/rag/retrieval/CascadedEvidenceAcceptancePolicyTest.kt`
- Test: `app/src/androidTest/java/com/example/minicpm_v_demo/rag/guard/RagGuardInstrumentedTest.kt`

- [ ] **Step 1：准备受控数据目录。** 在不进入 Git 的 `D:\MiniCPM-V\private-eval\rag-guard` 保存人工脱敏的 `office_calibration_unscored.jsonl`、`office_test_unscored.jsonl` 和 `training_document_ids.txt`。
- [ ] **Step 2：执行隐私人工复核。** 每条数据确认不包含真实姓名、电话、身份证号、精确地址、内部账号、客户编号和未授权正文；将 `redaction_status` 标记为 `reviewed`。
- [ ] **Step 3：使用固定 CPU 环境评分。** 按 `tools/rag_guard/OFFICE_QUALITY_GATE.md` 运行 `score_office_holdout.py`，输出校准集和测试集 scored JSONL。
- [ ] **Step 4：运行质量报告。** 使用固定 Guard SHA 和 tokenizer SHA 运行 `quality_gate.py`，保留聚合结果且报告不得包含正文；结果只用于诊断，不切断正式模型接入。
- [x] **历史公开数据预资格基线（不替代 Step 1-4）。** 2026-08-19 从 Doc2Dial v1.0.1（政务服务）和 CUAD v1（商务合同）构造文档级隔离的公开许可评测集；当时 v3 的 Answerability precision/recall 为 `1.0000/0.0250`，Groundedness macro-F1/ECE 为 `0.3996/0.1452`，所以当时 profile 保持 `null`。该结论仅描述 v3 历史状态；当前 v4.2 已绑定正式 profile。详见 `tools/rag_guard/PUBLIC_OFFICE_HOLDOUT.md`。
- [x] **v3 扩充训练与一次独立测试。** 中英文公开语料扩充到每个任务训练 `92,244` 条、校准 `5,124` 条、测试 `5,124` 条，文档 ID 两两隔离并包含办公与日常对话负例。FP32 独立测试 Answerability/Groundedness macro-F1 为 `0.9897/0.8128`；INT8 为 `0.9885/0.8088`。量化标签一致率 `0.9921` 低于 `0.995` 门槛，旧回归种子最大 macro-F1 降幅 `0.0979`，所以稳定发布门槛失败。当前使用与模型 SHA 绑定的 `0.95` 保守阈值；明确内容审核失败纠偏一次后使用知识库摘录，技术故障静默降级普通回答。训练 checkpoint 与 INT8 包已备份到 `D:\MiniCPM-V\artifacts\rag-guard-dual-head-v3`，并已原子部署到 vivo V2359A 私有 v3 目录。
- [x] **Step 5：写生产 profile 红灯测试。** 已覆盖错误 SHA、未安装模型、分类异常、低概率、最多 3 个候选、未校准 profile 和精确锚点旁路；所有失败路径均 fail-closed，取消继续传播。
- [x] **Step 6：接入惰性 Answerability。** `LazyAnswerabilityClassifier` 只在 `classify()` 首次实际执行时解析并缓存已验证模型；`MiniCPMApplication` 已绑定 v4.2 `CurrentAnswerabilityCalibration.profile`。启动和普通聊天不会预加载 Guard，实际分类时才打开已校验模型。
- [ ] **Step 7：执行真机回归。** 单文档、10 文档、40 文档和 500 文档规模下，同一问题/证据必须得到相同分类决策。
- [ ] **Step 8：提交独立改动。** 只提交代码、聚合报告和脱敏统计；禁止提交私有评测 JSONL。

### Task 2：Groundedness 输出审查生产接入

**Files:**
- Modify: `app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/RagTurnTransaction.kt`
- Existing: `app/src/main/java/com/example/minicpm_v_demo/rag/guard/RagOutputReviewPolicy.kt`
- Test: `app/src/test/java/com/example/minicpm_v_demo/rag/guard/RagOutputReviewPolicyTest.kt`
- Create: `app/src/test/java/com/example/minicpm_v_demo/rag/guard/RagReviewedGenerationTest.kt`

- [x] **Step 1：写成功、重生成、二次失败和取消测试。** 已覆盖候选隐藏、最多一次重生成、模型 SHA 不匹配和取消传播；checkpoint 事务另有独立成功/回滚测试。
- [x] **Step 2：定义 reviewed generation 结果。** 结果只允许 `Accepted` 和 `FallbackToNormalGeneration`；明确审核失败最终转换为不含模型草稿的知识库摘录 `Accepted`，技术故障才使用 fallback。
- [x] **Step 3：在 Ready 路径执行 Groundedness。** 使用用户原文、最终证据快照和完整候选答案分类；普通聊天和 NoEvidence 不运行。
- [x] **Step 4：实现一次受限重生成。** 修正 prompt 只使用同一证据；第一次候选不进入 UI 或稳定历史。
- [x] **Step 5：区分内容冲突与技术故障。** 第二次内容审核仍失败时不显示提示、不暴露模型草稿，直接使用中立化控制标签后的知识库摘录和来源编号；分类器缺失、超时、profile 缺失或模型 SHA 不匹配时恢复 checkpoint，清空 RAG 引用并使用原始问题普通生成。
- [x] **Step 6：v4.2 正式制品真机专项验证。** vivo V2359A 已验证 asset 私有安装、固定 SHA-256、三加四分类契约、30 次稳定推理和覆盖安装持久性。错误金额/日期/伪引用继续作为非阻断质量观测；历史 v3 异常不解释为 v4.2 结果。

### Task 3：证据缩减、token 预算和 prompt 加固

**Files:**
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/retrieval/EvidenceReducer.kt`
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/prompt/RagContextBudgeter.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/retrieval/RagPromptAssembler.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/RagCoordinator.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/LlamaEngine.kt`
- Test: `app/src/test/java/com/example/minicpm_v_demo/rag/retrieval/EvidenceReducerTest.kt`
- Test: `app/src/test/java/com/example/minicpm_v_demo/rag/prompt/RagContextBudgeterTest.kt`

- [x] **Step 1：写句子边界和恶意输入测试。** 已覆盖中英文标点、表格行、条款、金额、日期、emoji、重复句和 XML 闭合标签。
- [x] **Step 2：实现无模型句子窗口 reducer。** 保留最高相关窗口及前后各一句，按规范化文本跨来源去重。
- [x] **Step 3：增加 native token 计数接口。** JNI 使用 MiniCPM 当前模型的 `common_tokenize`，不使用字符数估算；native 构建已通过。
- [x] **Step 4：实现动态预算。** 默认 768、硬上限 900、单来源上限 320，并为回答保留 768、协议和问题保留 256；可用预算不足 128 时返回 NoEvidence。
- [x] **Step 5：加固 prompt。** 文件名、定位和正文均 XML escape，并放入明确的不可信 `<knowledge_base>/<source>` 数据边界。
- [x] **Step 6：运行 JVM 和真机 token 对齐测试。** 真实 MiniCPM tokenizer 已覆盖预算上限、emoji、表格和恶意 XML，实际注入 token 未超过预算。

### Task 4：有界向量后端

**Files:**
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/index/VectorSearchBackend.kt`
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/index/ExactVectorBuffer.kt`
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/index/HnswIndexManager.kt`
- Create: `app/src/main/cpp/rag_hnsw_jni.cpp`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/retrieval/RoomDenseEvidenceRetriever.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/work/FinalizeIndexWorker.kt`
- Test: `app/src/androidTest/java/com/example/minicpm_v_demo/rag/index/VectorSearchBackendInstrumentedTest.kt`

- [x] **Step 1：写统一接口和精确 oracle 测试。** `VectorSearchBackend` 已接入，分页精确结果与连续 exact oracle 一致，稳定按 score/chunk ID 排序。
- [x] **Step 2：实现小库连续 float buffer。** 最多 5000 chunks；缓存键绑定有序知识库集合、模型 SHA、corpusVersion、数量、最大更新时间和 chunk ID 校验和。
- [x] **Step 3：实现大库 HNSW/分区后端。** native hnswlib、文件头/长度/哈希/RSS 校验、分页精确降级和 5001 向量真机检索已通过。
- [x] **Step 4：实现认证原子 generation 和损坏恢复。** 新旧 generation 串行发布，新 generation 验证失败或取消时恢复上一代；损坏索引降级精确检索，查询不返回旧语料结果。
- [x] **Step 5：运行强制中断恢复矩阵。** 构建明文、payload 加密中途、payload 已提交和 metadata 已提交四个真实 `force-stop` 窗口均恢复到唯一认证 generation 且无临时/明文残留；20 次重复 enqueue 收敛到一个请求。证据见 `docs/execution/evidence/hnsw-force-stop-recovery-20260824.md`。
- [x] **Step 6：运行 1k/5k/20k benchmark。** vivo V2359A 的确定性合成语料已完成；20k 生产后端 Recall@10 为 0.9833，P50/P95 为 206.61/216.16 ms，构建 4.87 s，加密索引 33,693,766 bytes，native handle 最终为 0。

### Task 5：全量检索契约、生命周期和 UI

**Files:**
- Modify: `app/src/main/java/com/example/minicpm_v_demo/MiniCPMApplication.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/ChatAdapter.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/RagTurnTransaction.kt`
- Create: `app/src/main/res/layout/item_rag_source_chip.xml`
- Create: `app/src/test/java/com/example/minicpm_v_demo/rag/RagTurnLifecycleTest.kt`
- Create: `app/src/androidTest/java/com/example/minicpm_v_demo/rag/ui/RagAnswerUiTest.kt`

- [x] **Step 1：固化 ALL_QUERIES 正式契约。** 选中 READY 知识库后所有问题检索；所有非 `Ready` 规划状态和无证据路径使用未经修改的原问题普通生成。Groundedness 明确失败不显示固定提示，纠偏后仍失败则替换为知识库摘录；技术故障继续普通生成。
- [x] **Step 2：实现 15 秒阶段 watchdog。** 规划阶段超时直接使用原问题普通回答；Groundedness 分类超时触发现有 checkpoint 回滚和普通回答降级；模型正常生成不受该上限限制。
- [x] **Step 3：完成编辑和会话切换状态矩阵。** 用户编辑截断生成中 RAG 尾部和旧引用，AI 编辑保留引用并标记 edited，多会话隔离及 checkpoint cancel-and-join 已覆盖。
- [x] **Step 4：增加三个 RAG 阶段文案。** 只显示真实检索、整理和生成状态，不显示伪百分比，且不持久化。
- [x] **Step 5：增加来源 chip 和定位。** 来源 chip、当前索引块定位、归档摘录、“来源已删除”和“当前索引不可用”状态已完成；外部 PDF 页/表格单元格二进制深链不作为最小闭环门槛。
- [x] **Step 6：完成无障碍和视觉检查。** 来源 chip 整体可点击并提供 `contentDescription`，阶段态不入历史，沿用淡蓝选择、绿色成功和红色失败体系；本轮未发生生产 UI 改动，不重复既有真机视觉测试。

### Task 6：全链验收、灰度和文档

**Files:**
- Create: `app/src/androidTest/java/com/example/minicpm_v_demo/rag/RagEndToEndPerformanceTest.kt`
- Modify: `README_MODIFIED_zh.md`
- Modify: `docs/architecture/ADR-001-local-rag-stack.md`
- Modify: `docs/architecture/rag-threat-model.md`
- Modify: `docs/superpowers/plans/2026-08-18-minicpm-android-unified-progress-plan.md`

- [x] **Step 1：执行应用功能矩阵。** ALL_QUERIES 三路径、真实 E5/混合检索、原问题降级、编辑/会话隔离、token 加固、来源快照、生命周期和持久化均已覆盖；模型分类质量单独由 Task 1/2 管理。
- [x] **Step 2：执行 checkpoint 压力矩阵。** 100 次成功、50 次取消和 20 次生产 MainActivity 前后台取消均在 vivo V2359A 通过，最终活动 checkpoint 为 0。
- [x] **Step 3：执行性能矩阵。** 0/10/30 轮普通与 RAG prompt 各测 5 次 TTFT/PSS；RAG P95 为 1.836/1.914/2.360 秒。三种检索决策路径由独立 ALL_QUERIES 真机闭环覆盖。
- [x] **Step 4：执行应用安全矩阵。** 隐私、违法、无图、RAG 视觉优先级、提示注入和本地固定提示不入上下文已有回归；Groundedness 伪引用能力留在重训阻塞项，不再刻意试探边界。
- [x] **Step 5：执行固定签名覆盖安装。** 同批次主/测试 APK 使用 `adb install -r` 后，会话、知识库、文档状态、E5/Guard 和 HNSW 聚合指纹完全一致。
- [x] **Step 6：加入 `low_latency_rag_v1` 灰度开关。** 当前进程 checkpoint 自检失败只关闭 RAG，所有非 Ready 状态使用原始问题继续普通聊天。
- [x] **Step 7：更新 README 和发布说明。** v4.2 正式模型身份、实际量化指标、无性能接入门控、frozen test 未读和真机待验收状态已同步。

## 9. 验证命令

### 9.1 Android 构建和 JVM 回归

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:verifyInstallationSigning -x buildGgmlCpu_v86
```

预期：`BUILD SUCCESSFUL`。禁止运行 `connectedCheck` 或任何 `connected*AndroidTest`。

### 9.2 安全真机 instrumentation

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
.\scripts\run-device-instrumentation.ps1 -ClassName <测试类完整名称>
```

执行前必须确认主 APK 和测试 APK 签名一致。脚本参数中的测试类必须替换为本轮明确要运行的类，不允许无筛选执行全部 instrumentation。

### 9.3 Guard Python 回归

```powershell
$env:PYTHONPATH = 'D:\MiniCPM-V\.rag-python-tools;D:\MiniCPM-V\MiniCPM-V-Apps\MiniCPM-V-demo-Android'
& 'C:\Users\mingjun.dong\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m unittest discover -s tools\rag_guard -p 'test_*.py' -v
```

当前 v4.1 本地证据：122 项通过，6 项仅因本机无 PyTorch 而按设计跳过，0 失败；训练主机恢复后先补跑这 6 项再启动 smoke epoch。

## 10. 正式版本完整性检查与后续观测

当前 v4.2 Guard 已作为正式版本接入。以下前两项是后续非阻断质量观测，其余是已经满足的正式制品完整性检查：

- [ ] Answerability 真实办公独立观测并归档聚合结果。
- [ ] Groundedness v4.2 真机错误金额/日期/伪引用专项验收。
- [x] 证据 reducer 和真实 token 预算生效。
- [x] 选中 READY 知识库时所有问题进入检索；只有通过门控的证据进入模型上下文，无证据和失败路径使用原问题普通生成。
- [x] 小库向量缓存完成；大库 HNSW、损坏回退、规模基准和 force-stop 恢复完成。
- [x] 来源 chip、删除来源快照和阶段 UI 完成。
- [x] 生命周期、watchdog、编辑、取消、旋转和人工 UI 矩阵通过。
- [x] 功能、安全、压力、性能和固定签名覆盖安装全部通过。
- [x] README、威胁模型和已知限制与当前代码一致。

## 11. 当前下一步

应用主流程、发布工程、v4.2 数据修复、五轮 E5/NLI calibration-only A/B、E5 FP32/INT8 导出、正式 profile、APK asset、离线验证和真机专项验收均已完成。量化指标已如实归档，不再作为接入门控；frozen test 仍未读取。当前没有阻塞正式版本的剩余工程任务。

经过授权和人工脱敏的真实办公样本仍有价值，但其结果作为后续质量观测与改进输入，不阻塞当前正式版本。

### 11.1 v4.2 数据修复状态（2026-08-26）

- [x] 中英文日期/金额分类、同证据类型匹配关系反例、自然中文跨文档负例。
- [x] 256-token 可见证据窗口与决定性证据 release gate。
- [x] 标点-only 抽取答案拒绝、回答性语言配额和供给约束记录。
- [x] 120,000 Answerability + 150,000 Groundedness 全量语料生成，train/calibration/test = 243,090/13,609/13,301。
- [x] schema/privacy/许可证/配额/家庭隔离/证据可见性审计通过，Graphify 已更新。
- [x] 训练机 PyTorch 回归、v4.2 E1 诊断和 E5/NLI 五轮 calibration-only A/B 已完成；calibration 选择 E5。
- [x] 两组 checkpoint audit、来源/语言内容重分片、远端全量 pytest、SHA-256 和本地私有备份验收完成。
- [ ] 为下一轮训练补充进程内峰值显存遥测；本轮未可靠记录，验收清单明确为 `null`。
- [x] E5 FP32/INT8 正式导出、固定模型哈希、生产 profile 和 Android APK 集成；frozen test 继续保持未读。
- [x] v4.2 真机 instrumentation、私有 asset 安装和覆盖安装持久性验收。

v4.2 详细证据、文件哈希和下一步控制实验见 `tools/rag_guard/DATASET_CARD_V4.md`、`tools/rag_guard/TRAINING_RUN_V4.md` 和 `docs/superpowers/plans/2026-08-26-rag-guard-v4-2-dataset-repair-plan.md`。

## 12. Graphify 持久知识图谱维护

项目知识图谱固定保存在 `graphify-out/`，纳入后续本地开发流程：

- `graph.json`：可查询的原始图谱。
- `GRAPH_REPORT.md`：社区、God Nodes、跨社区关系和建议问题报告。
- `graph.html`：离线交互式可视化。
- `.graphify_labels.json`：社区名称。
- `manifest.json`：增量检测基线。
- `cost.json`：语义提取 token 审计；无法取得子代理 token 遥测时必须明确记为 0 和不可用，不能伪造。

维护规则：

1. 回答代码结构问题前，优先使用 `graphify query/path/explain`。
2. 每次修改代码后执行 `graphify update .`。
3. 每次修改计划、ADR、威胁模型、README 或其他文档后，必须执行语义增量提取；仅运行 AST update 不算完成。
4. 完成任务前执行 `graphify check-update .`，不得留下未说明的 semantic pending 状态。
5. Graphify 提取失败、dangling edge、syntax warning 和 edge-collapse 诊断必须如实记录。
6. `post-commit/post-checkout` Git hook 为推荐补充机制，但不能替代任务结束前的显式检查。

当前首次构建范围为 Android 项目根目录。重复启动图标、TTS 参考音频、构建目录、模型二进制和生成训练语料通过 `.graphifyignore` 排除；它们的架构含义由代码、manifest 和文档表示。
