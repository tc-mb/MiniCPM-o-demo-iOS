# RAG Guard v4 dataset card

Status: v4 historical corpus and failed release candidate are archived. The independently
versioned v4.1 correctness rebuild and the v4.2 semantic repair corpus have been generated,
split, hashed, and audited. On 2026-08-27, matched five-epoch E5 and fixed-revision NLI
calibration-only runs completed without reading frozen test. Calibration selected E5. On
2026-08-28, the selected E5 checkpoint was exported to FP32 ONNX, dynamically quantized to
INT8, recorded without a performance-release gate, and integrated into the Android production
path by explicit product decision. Frozen v4.2 test remains unopened.

The target tasks are Answerability three-class and Groundedness four-class, as defined in
`V4_LABEL_CONTRACT.md`. Raw archives stay under
`D:\MiniCPM-V\private-training\rag-guard-v4`; v4.1 generated JSONL stays under
`D:\MiniCPM-V\private-training\rag-guard-v4-1`. Git contains only code, schemas,
aggregate statistics, licenses, versions, and hashes.

A source with `license_status=review_required` is rejected by the v4 dataset validator and
must not enter a training split. Document, conversation, mutation, translation, and
near-duplicate families are split atomically to prevent leakage.

HoVer data is recorded as CC BY-SA 4.0 according to its official dataset homepage. Its
released `NOT_SUPPORTED` label merges REFUTED and NOT-ENOUGH-INFO and therefore is not
directly treated as `CONTRADICTED` in v4.1. ContractNLI is approved under CC BY 4.0; the
user personally accepted its click-through terms on 2026-08-24 and the archive hash passed
preflight. Current hashes and readiness are recorded in `TRAINING_PREFLIGHT_V4.md` and
`TRAINING_RUN_V4.md`.

## v4.2 数据修复发布候选（2026-08-26）

v4.2 是独立于 v4.1 的新 transform；v4.1 语料、切分、模型和冻结 test 均未覆盖或修改。
修复包括：

1. QA 关系反例只从同证据、同粗粒度答案类型中选择，避免把任意段落答案误称为纯实体错误。
2. 英文月份、裸年份和中英文日期格式统一归入 `WRONG_DATE`；金额/单位保持独立切片。
3. 用固定 multilingual-e5-small tokenizer 在生成期构造可见证据窗口，最终句对上限为 256 token；决定性答案不可见或保护句对超长时整族拒绝。
4. 中文 `PARTIAL/UNSUPPORTED` 只使用跨文档自然问题，不再生成参考编号模板；标点-only 抽取答案直接拒绝。

由于批准的中文 QA 原始供给只有 CMRC 2018，v4.2 不复制中文样本：Answerability 每类中文保留 600 条，缺口按同标签转移到英文；Groundedness 中文困难切片按真实供给保留 600/450/40/70/10（否定/关系/金额/日期/单位），英文补足总矛盾 37,500 条。该供给约束使有据性冲突中文占比为 3.12%、最大来源占比为 74.08%，已在 release policy 中显式记录（中文下限 3%、来源上限 80%），没有降低 schema、隐私、证据可见性或家庭配对门禁。

### v4.2 输出与审计

- 根目录：`D:\MiniCPM-V\private-training\rag-guard-v4-2\generated\corpus-e`
- transform：`rag-guard-v4.2-full-corpus-1`
- seed：`rag-guard-v4.2-full-corpus-e`
- tokenizer：固定本地 `multilingual-e5-small`，max length 256
- Answerability：120,000 行（SUPPORTED/PARTIAL/UNSUPPORTED = 48,000/30,000/42,000；每类 zh/en = 600/47,400、600/29,400、600/41,400）
- Groundedness：150,000 行（GROUNDED/PARTIAL/UNSUPPORTED/CONTRADICTED = 45,000/37,500/30,000/37,500）
- token rejected：Answerability 0、Groundedness 453；孤立 contradiction family 440；决定性证据不可见 0；未授权 HoVer contradiction 0。

| split | 全部 | Answerability | Groundedness |
|---|---:|---:|---:|
| train | 243,090 | 108,162 | 134,928 |
| calibration | 13,609 | 5,968 | 7,641 |
| test | 13,301 | 5,870 | 7,431 |

跨 `document_id`、`conversation_id`、`mutation_family_id`、`translation_family_id`、`near_duplicate_cluster_id` 的三组交集均为 0，全局 row ID 唯一。

| 文件 | SHA-256 |
|---|---|
| `corpus-e/answerability.jsonl` | `7a46a838e23c91e5027866a9c25d950d5eb6ad394581d99d3cbbb4d68bfb8fd0` |
| `corpus-e/groundedness.jsonl` | `2b4b3b8f5331d552e05a0e49fe91d114ca1ab15dd2029d848c68b9e2ce30fa48` |
| `corpus-e/corpus-manifest.json` | `296d64f1dc61481caea2e5d3288a5d68201bb660c32aae36abfbd5711777c694` |
| `splits-e/all_train.jsonl` | `c19e6f8ac3bfe17931eb89ee051ea127248879ec226b066f4d0c8bc70a24ee9` |
| `splits-e/all_calibration.jsonl` | `659b90a8f33adc3d652b5abcefe36847fee976d25ba08c9e802b58cdf6df790c` |
| `splits-e/all_test.jsonl` | `6128e373b18252052fc9807ab4ee9514767084d2673a6e4bf87b1cde32d3c130` |

任务级 split 文件也已冻结：

| 文件 | SHA-256 |
|---|---|
| `splits-e/answerability_train.jsonl` | `3035504a2ec927ac80567918e242f5c1141c5f7d158865a35e5951ba57fea987` |
| `splits-e/answerability_calibration.jsonl` | `ff81eb20e547f8df66849796d7167158201cdfd220cdc94bad274145c1f20198` |
| `splits-e/answerability_test.jsonl` | `000c26509c5b21045b5687eda7f20d215021ccab67fa941bf3d751d11bebf306` |
| `splits-e/groundedness_train.jsonl` | `5a8523237c81fd099a7ff1e234a4745d28bbf6505fb9c923892ab94e6253a1c2` |
| `splits-e/groundedness_calibration.jsonl` | `b49a07a0aa5e70f9123fae7bddc8913d3a53041ad30df44b2e0aa333c750ef20` |
| `splits-e/groundedness_test.jsonl` | `2da8ec31f0b4c74c3f4062e94883f23fb90f43f0e24bd4f309e6a18d0167f950` |

完整 release audit：`D:\MiniCPM-V\private-training\rag-guard-v4-2\audits\full-release-audit.json`，SHA-256 `3f003f4879d733a0792011486128101edcf3409889263aafab99142340604fbd`；本地回归 `139 passed, 6 skipped`（仅本机未安装 PyTorch 的张量测试跳过）。训练机全量回归为 `139 passed, 9 subtests passed`。

### v4.2 calibration-only 训练状态（2026-08-27）

固定相同数据、seed、batch、学习率、token budget 和 5 epoch 后，E5 保存 checkpoint 的 Answerability/Groundedness macro-F1 为 `0.907691/0.956991`，NLI 为 `0.890556/0.954531`；对应 CONTRADICTED precision/recall 为 `0.951683/0.913497` 与 `0.941842/0.911412`。因此只按 calibration 选择 E5。两组 `metrics.test=null`、`test_evaluated=false`，manifest 同样声明未评估 test。

内容重分片保留真实语义：v4.2 `WRONG_ENTITY` 表示同证据、同粗粒度答案类型的关系绑定冲突；E5/NLI 从独立 E1 的 `0.318519/0.316667` 提升到 `0.729630/0.733333`。英文 `WRONG_AMOUNT` 中日期样式共 151 条，五轮 E5/NLI recall 为 `0.966887/0.993377`；中文同切片只有 1 条，不作为中文泛化证据。完整五轮历史、来源/语言重分片、哈希和备份位置见 `TRAINING_RUN_V4.md`。

### v4.2 E5 Android 正式制品（2026-08-28）

- 任务契约：Answerability 三分类；Groundedness 四分类；共享输出为 `[batch,4]`，Answerability 第四 logit 固定填充 `-10000`。
- FP32 ONNX：`470,310,373` bytes。
- INT8 ONNX：`118,171,779` bytes，SHA-256 `d674ef4ef4fb2b4dce37d43c46eeb4b0e8038eb66da7cde1b568ca78dc45e1c2`，体积比 `0.2512633907`。
- PyTorch/FP32 最大绝对差：`0.0000088215`；INT8/FP32 标签一致率：`0.9693585127`；最大 calibration macro-F1 降幅：`0.0107869130`。
- INT8 calibration：Answerability macro-F1 `0.8969041130`，Groundedness macro-F1 `0.9510476835`。
- manifest 明确记录 `deployment.channel=production`、`selection_basis=recorded_metrics`、`evaluated_splits=[calibration]`、`test_evaluated=false` 和 `test=null`。
- 上述性能数字只如实记录，不作为导出或 APK 接入的阻断门槛；模型字节数、SHA-256、受控路径、ONNX 输入输出契约和 APK 签名仍是强制完整性检查。
- vivo V2359A 已完成正式制品真机验收：私有模型大小/哈希一致，固定签名覆盖安装保留用户数据并完成 v3 到 v4.2 受控迁移，双头 30 次推理稳定。
