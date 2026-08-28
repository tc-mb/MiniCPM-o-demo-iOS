# 端侧混合检索校准证据（2026-08-17）

> **状态：已失效，禁止作为生产启用依据。** 后续单文档真机回归证明绝对 BM25 阈值不能跨知识库规模迁移；生产配置已恢复 fail-closed。本文保留首次结果用于追踪根因，替代方案见下方“复核与纠正”。

## 范围

- 目标设备：vivo `V2359A`。
- 模型：`intfloat/multilingual-e5-small` INT8，SHA-256 `739c8f25bbe6d8a6001cd2f048701da9879140cc67d4e9327716111e869dd717`。
- 语料版本：`1`。
- 数据：40 份纯合成中英文办公文档，不读取用户知识库、聊天记录或真实隐私数据。
- 查询：相关、相似但错误、完全无关、问候、编号、日期、金额和跨文档 8 类，每类 40 条，共 320 条。

生成器位于 `app/src/androidTest/java/com/example/minicpm_v_demo/rag/retrieval/SyntheticOfficeCalibrationCorpus.kt`。每个 case ID 匿名且唯一；测试只输出进度、模型/语料版本、阈值和聚合指标，不输出问题或正文。

## 方法

每条查询均经过生产路径的真实 E5 query embedding、Room FTS4 `matchinfo`、BM25 和 RRF。为了避免文件名、编号与条款的确定性快捷路径抬高语义阈值指标，评分前将候选的 `exactAnchor` 标志清零；精确锚点由独立回归测试覆盖。

证据查询的 Recall@4 定义为：

$$
\operatorname{Recall@4}=\frac{\text{前 4 个已接受候选包含相关块的证据查询数}}{\text{证据查询总数}}
$$

NoEvidence 精确率定义为：

$$
\operatorname{Precision}_{NE}=\frac{\text{正确拒绝的无证据查询数}}{\text{所有预测为 NoEvidence 的查询数}}
$$

NoEvidence 召回率定义为：

$$
\operatorname{Recall}_{NE}=\frac{\text{正确拒绝的无证据查询数}}{\text{无证据查询总数}}
$$

搜索器只接受同时满足 $\operatorname{Recall@4}\ge 0.90$ 与 $\operatorname{Precision}_{NE}\ge 0.95$ 的配置；同指标下优先选择更高的 NoEvidence 召回率和更保守的阈值。

## 首次结果（已失效）

| 项目 | 结果 |
|---|---:|
| 样本数 | 320 |
| high dense | 0.941237 |
| standard dense | 0.827480 |
| minimum lexical | 4.571398 |
| Recall@4 | 0.995000 |
| NoEvidence 精确率 | 0.987805 |
| NoEvidence 召回率 | 0.675000 |

搜索器原始值向上取整后曾写入生产配置，并在相同 40 文档语料上复跑通过；由于该复核没有改变知识库规模，未能发现 BM25 的跨规模漂移，因此不能证明阈值可用于真实知识库。

## 复核与纠正

普通单文档语义问题 `What is the travel reimbursement limit?` 在 vivo `V2359A` 上得到：

| 特征 | 真机值 |
|---|---:|
| dense | 0.85583067 |
| BM25 | 0.86304622 |
| 原 minimum lexical | 4.57139800 |
| 原策略结果 | 错误拒绝 |

根因是 BM25 中的 $operatorname{IDF}(t)$ 依赖文档总数 $N$ 和文档频率 $df_t$；同一问题与证据放入不同规模知识库时，绝对分数不可直接比较。

随后把 lexical 条件替换为 $[0,1]$ 的查询词项覆盖率并重新运行 320 条。没有任何阈值组合同时满足两个质量门槛；在 NoEvidence 精确率不低于 `0.95` 时，最大 Recall@4 只有 `0.88`。这证明词项覆盖率可作为低成本特征，但不能独立识别“主题相关却不包含答案”的片段。

纠正后的生产方向为级联 Answerability 门控：精确锚点直接接受，明显低信号直接拒绝，其余 Top 3 交给本地 `SUPPORTED/PARTIAL/UNSUPPORTED` 分类器；分类器缺失、哈希不符或未通过真机质量与性能门槛时继续 fail-closed。

## 安全解释与限制

- 配置键同时绑定模型 SHA 与语料版本；任一版本不匹配时普通 dense 证据被拒绝。
- 分数必须有限，case ID 必须唯一，校准集少于 300 条、类别缺失或不存在合格配置时失败关闭。
- 首次 NoEvidence 召回率为 67.5%，且该结果已经因跨规模回归失败而失效，不得继续解释为可接受的生产取舍。
- 本结果是合成办公集和单一目标机型的版本化基线，不替代真实用户分布的脱敏灰度评测。

## 复现

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
.\gradlew.bat :app:verifyInstallationSigning --no-daemon
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb install -r .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
.\scripts\run-device-instrumentation.ps1 -TestClass "com.example.minicpm_v_demo.rag.retrieval.RetrievalCalibrationInstrumentedTest" -TimeoutSeconds 900
```
