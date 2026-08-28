# RAG Guard v4.1 E5 smoke calibration 错例审计

更新时间：2026-08-26

## 审计边界

- 模型：`e5-smoke-e1`
- checkpoint SHA-256：`7605e2cb0dc5a7fd001f5f8970a82aa09a52750b52e8afef7d5451c9a7e4d7ad`
- 仅评估 calibration：13,693 行
- `test_evaluated=false`；未读取冻结 test
- 文本无关错例清单位于私有训练目录，不提交原始问题、证据或答案文本
- `calibration-errors.jsonl`：1,491 行，SHA-256 `9d0b5ebd1fd3b606e221b211c29e7f17ec5dce2e673033947fa9a8f9f8d0d78a`
- `calibration-slice-audit.json`：SHA-256 `9904124fc2e52ced10e3668267103529f0d07f993da8f7cf0635d092456885d9`

私有证据目录：`D:\MiniCPM-V\private-training\rag-guard-v4-1\evidence\e5-smoke-e1`

## 错例总览

- 总误判：1,491
- Answerability：860
- Groundedness：631
- `WRONG_ENTITY`：485 行中误判 264 行，全部误判为 `GROUNDED`
- `WRONG_DATE`：45 行中误判 11 行，全部误判为 `GROUNDED`

`WRONG_ENTITY` 的 264 条误判来自 SQuAD 2.0 英文 199 条、CMRC 2018 中文 65 条。错误和正确样本中的候选答案内容都出现在证据中，因此简单词面共现无法区分两类。粗粒度答案类型对照显示，误判中约 63.3% 为 text-to-text 同类型替换；类型不匹配比例为 27.3%，低于正确识别样本的 37.6%。这支持“问题、候选答案和证据之间的关系绑定不足”，而不是单纯实体类型识别失败。

## 困难类型生成边界问题

### `WRONG_ENTITY` 名称过窄

QA 生成器从同一段落的 `answer_pool` 直接选择第一个不同答案，没有做答案类型、问题类型或实体类别匹配。因此该切片实际表示“同证据内错误答案/错误关系绑定”，其中包含数字、日期、短语和实体；不得把当前 recall 解释为纯命名实体替换能力。

### 英文日期被大量计入 `WRONG_AMOUNT`

当前日期规则只识别 `day/week/month/year` 词面和中文年月日，不识别英文月份名与裸年份。训练 split 的英文 `WRONG_AMOUNT` 共 6,286 条，其中 2,959 条具有月份名或明显年份模式，占约 47.1%。calibration 内容重分片为：

| 内容切片 | 数量 | 误判 | recall |
|---|---:|---:|---:|
| 声明为 `WRONG_DATE` | 45 | 11 | 0.755556 |
| `WRONG_AMOUNT` 中日期样式 | 184 | 26 | 0.858696 |
| `WRONG_AMOUNT` 中非日期样式 | 218 | 25 | 0.885321 |

原 `WRONG_DATE` calibration 由中文 44 条、英文 1 条组成，不能用于判断总体英文日期能力。该问题不改变主标签 `CONTRADICTED`，但会污染困难类型配额、采样解释和 release slice 指标。

## 已排除的假设

抽样输出曾在 Windows 终端显示中文乱码。三层检查确认：

1. CMRC 原始 train/dev JSON 均可严格按 UTF-8 解码，替换字符为 0；
2. v4.1 Groundedness calibration 同样可严格按 UTF-8 解码，替换字符为 0；
3. 使用 `ensure_ascii=true` 后汉字码点正确。

根因是 Windows 控制台代码页错误解释 Python UTF-8 stdout，不是语料损坏，因此不得据此重建或重新下载 CMRC。

## 当前因果假设与五轮判据

保持数据、基础模型、seed、batch、学习率和 token budget 不变，只增加 epoch：

- 若 `WRONG_ENTITY` 内容重分片 recall 随 epoch 持续提升，则主要瓶颈包含欠拟合；
- 若它平台化或下降，而总体 Groundedness 继续提高，则关系绑定数据构造或模型初始化更可能是主因；
- 日期结果必须同时报告声明切片与内容重分片，不能只报告原 `WRONG_DATE`；
- 五轮结束前不修复生成器，以免改变实验变量；五轮结果归档后再按 TDD 修复类型匹配与日期识别并重建新版本数据。

## 五轮观察结果

五轮诊断已完成，自动排名保存 epoch 4 checkpoint；epoch 5 只有 history 指标。关系绑定 recall 的五轮轨迹为 `0.432990 -> 0.632990 -> 0.773196 -> 0.762887 -> 0.800000`，说明增加 epoch 能显著缓解欠拟合，但第 4 轮已有轻微回落，不能据此认定生成语义正确。声明日期轨迹为 `0.755556 -> 0.777778 -> 0.688889 -> 0.733333 -> 0.755556`，没有稳定提升。

保存的 epoch 4 checkpoint 内容重分片结果为：英文日期样式 `0.945652`、非日期金额样式 `0.931193`、声明日期 `0.733333`、关系绑定 `0.762887`。相对独立 smoke，关系绑定提升明显，英文日期样式也改善，但以中文为主的声明日期切片反而下降。当前证据支持两个并存根因：关系绑定存在欠拟合；困难类型生成语义和语言配额仍需重建。不得用追加 epoch 替代生成器修复。

## v4.2 修复 smoke 与全量门禁结果

v4.2 使用新的 transform `rag-guard-v4.2-full-corpus-1`，不修改 v4.1 语料或五轮 checkpoint。三次 bounded smoke 先验证了窗口策略：第三次 smoke 的 QA 2,825 行中，超过 256 token 为 0、决定性证据不可见为 0、参考编号模板为 0。全量 `corpus-e` 随后重新检查并修复了 2 条标点-only SQuAD 抽取答案（原形如 `The answer is ..`）；修复后重新生成，不保留旧 corpus-d。

全量 v4.2 release audit：

- Answerability 120,000，Groundedness 150,000；标签配额精确满足。
- `protected_input_overflow_rows=0`、`decisive_qa_evidence_not_visible_rows=0`、`untrusted_hover_contradicted_rows=0`。
- 中文 Answerability 每类 600 条，全部来自 CMRC 自然问题；未发现生成参考编号。
- 关系反例只保留同证据、同粗粒度类型候选；不能与真答案共存于 256 token 窗口时，关系 sibling 被移除并保留其余 family。
- 中英文日期识别已纳入英文月份名、裸年份和中文年月日；`WRONG_DATE` 不再依赖英文 `day/week/month/year` 词面。
- 由于批准中文原始供给有限，Groundedness 冲突中文占比为 3.12%，英文承担同硬类型缺口；该约束已显式写入 v4.2 data card 和 balance policy，不将低中文供给误读为模型能力。

切分后的 train/calibration/test 规模为 `243090/13609/13301`，五个保护族跨 split 交集均为 0。full audit SHA-256 为 `3f003f4879d733a0792011486128101edcf3409889263aafab99142340604fbd`。这证明数据构造和输入可见性问题已处理，但不等价于 frozen test 已评估。

2026-08-27 已完成 v4.2 E1 calibration-only 诊断：Answerability macro-F1 `0.875083`、Groundedness macro-F1 `0.923570`、CONTRADICTED precision/recall `0.939547/0.777488`；关系绑定（`WRONG_ENTITY`）recall `0.318519`，仍是主要瓶颈。该运行 `test_evaluated=false`、`metrics.test=null`、`release_eligible=false`，因此不导出部署；下一步是固定数据和 calibration，执行 E5 与 NLI 初始化的受控对照。

## v4.2 五轮 A/B 内容重分片结论（2026-08-27）

两组都只审计 calibration，`test_evaluated=false`。E5 保存 epoch 4 checkpoint，NLI 保存 epoch 5 checkpoint。

| 内容切片 | E5 E1 | E5 保存 checkpoint | NLI E1 | NLI 保存 checkpoint |
|---|---:|---:|---:|---:|
| 同证据关系绑定（全部 540） | 0.318519 | 0.729630 | 0.316667 | 0.733333 |
| 关系绑定英文/SQuAD（518） | 0.312741 | 0.722008 | 0.312741 | 0.735521 |
| 关系绑定中文/CMRC（22） | 0.454545 | 0.909091 | 0.409091 | 0.681818 |
| `WRONG_AMOUNT` 英文日期样式（151） | 0.933775 | 0.966887 | 0.953642 | 0.993377 |
| `WRONG_AMOUNT` 英文非日期（98） | 0.938776 | 1.000000 | 0.979592 | 1.000000 |

关系绑定随轮次显著改善，说明欠拟合确实是 E1 的重要成因；但保存 checkpoint 仍有约 27% 关系冲突未识别，不能把追加 epoch 当作完整修复。英文日期样式同样改善，NLI 更强；中文对应内容切片仅 1 条，样本不足。结合总体 Answerability、Groundedness 和 CONTRADICTED precision/recall，最终仍选择 E5，NLI 只在关系绑定和英文日期内容切片上略占优。

本节中的 `WRONG_ENTITY` 始终解释为关系绑定能力，不解释为纯命名实体能力；`WRONG_DATE` 声明切片和 `WRONG_AMOUNT` 内容日期切片始终分开报告。
