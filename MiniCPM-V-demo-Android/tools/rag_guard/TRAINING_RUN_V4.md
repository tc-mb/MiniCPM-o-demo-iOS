# RAG Guard v4 数据构建与训练运行记录

更新时间：2026-08-27

## 当前状态

- 原始数据预检：通过，8 个必需文件哈希一致。
- schema v2 正式语料：已生成。
- 隐私、许可、重复 ID、族级切分和跨 split 泄漏审计：通过。
- v4 历史训练未通过当时的发布门槛；该历史结论不等于 v4.2 当前部署状态。
- v4.1 已修复输入截断、HoVer 标签边界、固定模板捷径和困难类型覆盖，独立语料及切分后 release 审计通过。
- v4.1 E5 一轮 smoke 和用户要求的五轮诊断训练均已完成；冻结 test 未读取。v4.2 选定 E5 checkpoint 已按 2026-08-28 产品决定取消性能阻断、如实记录量化结果并接入 Android 正式路径。
- v4.2 数据修复、全量生成、族级切分和 release 审计已完成；E5 与固定 NLI 初始化的五轮 calibration-only A/B 已完成，冻结 test 未读取。
- 当前 `generator_commit` 仍为 `244cdedb1095f252f893e5e02ec809c72965a73d`，转换器代码位于未提交工作区；为避免假冒已提交状态，另冻结 30 个生产 Python 文件的 source-bundle SHA-256：`6e4ba9ab93aa632f10d8eeb2f5f1fc1a85c23c5742290c2437aad3d11d542bfc`。

## 生成契约

- schema：2
- transform：`rag-guard-v4-full-corpus-1`
- seed：`rag-guard-v4-full-corpus`
- split seed：`rag-guard-v4-release-split`
- Answerability：120,000 行，`SUPPORTED/PARTIAL/UNSUPPORTED = 48,000/30,000/42,000`
- Groundedness：150,000 行，`GROUNDED/PARTIAL/UNSUPPORTED/CONTRADICTED = 45,000/37,500/30,000/37,500`

## 切分结果

| split | 全部 | Answerability | Groundedness |
|---|---:|---:|---:|
| train | 242,436 | 107,535 | 134,901 |
| calibration | 13,793 | 6,177 | 7,616 |
| test | 13,771 | 6,288 | 7,483 |

`document_id`、`conversation_id`、`mutation_family_id`、`translation_family_id` 和 `near_duplicate_cluster_id` 的跨 split 交集均为 0。三个 split 的 row ID 交集为 0，全局唯一 ID 为 270,000 个。

## 六个训练文件

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `answerability_train.jsonl` | 228,545,847 | `ee7b7845e7d2526310cf56ffb65ab16399fc32a373c0cb949a057aacb159c326` |
| `answerability_calibration.jsonl` | 13,177,081 | `640ed787b24d3e3e40202f810d766988ae663d1ceabd9a5599acf98cac888eec` |
| `answerability_test.jsonl` | 13,251,875 | `179933be9c9d62469b7f1ffa027685810ae9f77849922e2e3a2168388c760e6a` |
| `groundedness_train.jsonl` | 302,222,027 | `2c08d0d066931baddac4f80231ce0b8523943f802884508b21b09d80391cce5d` |
| `groundedness_calibration.jsonl` | 17,249,983 | `5e92e2b15cd5170dd0da44f11f25caa5b84220fab5d9e9af368a2a6cef7e198b` |
| `groundedness_test.jsonl` | 16,758,748 | `bb18ee387edb8fc152781fa1af46c05df741f7955d366340ce50d706dedf17ce` |

## 2026-08-28 v4.2 E5 正式导出与 APK 接入

### 产品决定

选定 E5 checkpoint 继续使用既定 per-tensor 动态 INT8 量化策略。导出器已删除性能发布门控：量化对齐、macro-F1 变化和压缩率只写入制品记录，不再阻止 manifest 生成或 APK 接入。受控路径、冻结 test 边界、tokenizer 身份、模型字节数、SHA-256、ONNX 输入输出契约、私有目录原子安装和 APK 签名仍是强制检查。

### 环境与制品

- 本地隔离环境：Python 3.12、PyTorch 2.4.1+cpu、Transformers 4.53.3、ONNX 1.19.0、ONNX Runtime 1.23.2、NumPy 2.2.6；`pip check` 无损坏依赖。
- checkpoint：`D:\MiniCPM-V\private-training\rag-guard-v4-2\evidence\e5-calibration-e5`。
- 输出目录：`D:\MiniCPM-V\artifacts\rag-guard-v4-2-e5`。
- FP32 ONNX：`470,310,373` bytes。
- INT8 ONNX：`118,171,779` bytes，SHA-256 `d674ef4ef4fb2b4dce37d43c46eeb4b0e8038eb66da7cde1b568ca78dc45e1c2`。
- 压缩率：`0.2512633906971046`。
- manifest：`deployment.channel=production`、`selection_basis=recorded_metrics`、`evaluated_splits=[calibration]`、`test_evaluated=false`、`test=null`。

### 量化观测结果

| 指标 | 结果 |
|---|---:|
| PyTorch/FP32 最大绝对差 | `0.000008821487426757812` |
| INT8/FP32 标签一致率 | `0.9693585127489162` |
| 最大 calibration macro-F1 降幅 | `0.01078691295800005` |
| INT8/FP32 最大 logit 差 | `5.933208465576172` |
| INT8/FP32 平均 logit 差 | `0.24869035184383392` |
| FP32 Answerability macro-F1 | `0.9076910259` |
| INT8 Answerability macro-F1 | `0.8969041130` |
| FP32 Groundedness macro-F1 | `0.9569909766` |
| INT8 Groundedness macro-F1 | `0.9510476835` |

这些结果不被解释为“通过/失败”门槛，只作为当前正式制品的可追溯观测。导出全过程只读取 calibration，未读取或评估 v4.2 frozen test。

### Android 与 APK 验证

- Android runtime 已升级为 Answerability 三分类与 Groundedness 四分类，复现训练期 XLM-R 双序列格式，Answerability 第四 logit 校验为 `-10000` 填充。
- Gradle 从外部制品目录生成未压缩 ONNX asset；应用首次使用时复制到私有 v4.2 目录，执行同目录临时文件、`fsync`、大小/SHA-256 校验和原子替换。
- Python 全量回归：`143 passed, 9 subtests passed`。
- Android JVM 全量回归：`BUILD SUCCESSFUL`。
- `verifyInstallationSigning` 与 `assembleDebug`：`BUILD SUCCESSFUL`。
- APK：`app\build\outputs\apk\debug\app-debug.apk`，`202,952,355` bytes，SHA-256 `7b5c242dde8a1bdf939f3a2070b8b2bb337e1bce0e7fcc562b297a0b39f991c1`。
- APK 中 `assets/rag_guard_v4_2/model.int8.onnx` 为未压缩条目，大小与 SHA-256 均和正式 INT8 制品一致。
- APK Signature Scheme v2 验证通过；证书 SHA-256 `12befeda42fecfe1f9a268466b85906e0b18e13c960b7217487fc6145166eb85`。
- vivo V2359A 真机已验证私有 asset 为 `118,171,779` bytes，SHA-256 `d674ef4ef4fb2b4dce37d43c46eeb4b0e8038eb66da7cde1b568ca78dc45e1c2`。
- 固定签名 `adb install -r` 覆盖安装成功；会话 1、消息 19、知识库 1、READY 文档 2、E5 哈希和 HNSW 聚合指纹保持一致，Guard 从旧 v3 受控迁移到 v4.2。持久性基线探针已删除。
- `RagGuardInstrumentedTest` 通过：CPU 模型打开 `1441.170 ms`；Answerability P50/P95 `8.245/8.475 ms`；Groundedness P50/P95 `10.505/11.755 ms`；30 次无标签漂移。

## 2026-08-26 v4.1 correctness rebuild

### 根因修复

1. 输入改为受保护句对：第一序列为 `query + candidate answer`，第二序列为 evidence；只允许 `truncation="only_second"`。
2. HoVer 发布版 `NOT_SUPPORTED` 合并了 REFUTED 和 NOT-ENOUGH-INFO，不再直接映射为 `CONTRADICTED`；冲突由可靠 SUPPORTED 正例的可证明最小变异生成。
3. 删除 Groundedness 固定元提示答案；QA 来源即使没有原生 impossible question，也生成完整 Answerability 三分类对照族。
4. 八种困难类型全部进入 pair sampler 和 hard-slice 指标；同 family 多个冲突 sibling 按 epoch 轮换。
5. release 审计新增受保护 token、固定答案占比、来源标签相关性和不可信 HoVer 映射门禁。

### v4.1 生成契约

- 根目录：`D:\MiniCPM-V\private-training\rag-guard-v4-1`
- transform：`rag-guard-v4.1-full-corpus-1`
- corpus seed：`rag-guard-v4.1-full-corpus`
- split seed：`minicpm-rag-guard-v4.1-release`
- tokenizer：固定本地 `multilingual-e5-small`，max length 256
- token 过滤：Answerability 0 行、Groundedness 453 行；连带清除 440 个失去 GROUNDED sibling 的 family。
- token-rejected ID SHA-256：`7e3991f7fccb33ba75db25b4839658595771e324573fab2e39644231b70ff672`
- orphan-family SHA-256：`4d087fae96589d99f55be7733568ac947b2d0cb25fa9d90b07f8d0e3ecc79e99`

### 完整语料和切分

| split | 全部 |
|---|---:|
| train | 243,367 |
| calibration | 13,693 |
| test | 12,940 |
| total | 270,000 |

Groundedness 标签仍为 `45,000 / 37,500 / 30,000 / 37,500`；冲突中文 10,000、英文 27,500。八类冲突为：否定 10,000、错误实体 9,650、金额 7,750、日期 1,050、单位 500、多跳 7,500、合同矛盾 700、范围翻转 350。

### v4.1 九个冻结 split 文件

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `all_train.jsonl` | 533,178,711 | `ca4097f5671c911bbe608cea6973e508cf50891ef3c4ca974a395f354298e409` |
| `all_calibration.jsonl` | 30,384,233 | `766ec0a076a1b14edfa0932b57230ca53b1371ea73e703ec368291921caa36bd` |
| `all_test.jsonl` | 27,857,626 | `1f1abf49476c0a3a913e4369a88ba93d3f92deb28a8ce7fe9a69ebbe82157c99` |
| `answerability_train.jsonl` | 220,835,975 | `65ae60d12dbd12b90878d5a8ffc8a13a01feaa81298409045791e7116ce738ac` |
| `answerability_calibration.jsonl` | 12,632,702 | `8e614f1fb3701937068df006dbba95fd4e50889a9665389e78a8bc76d71a7b81` |
| `answerability_test.jsonl` | 11,713,822 | `07d5c0e9cc4aeac142325e5ae952905c61243c270d2ed9d1e8c3e0880aa4a891` |
| `groundedness_train.jsonl` | 312,342,736 | `3604bdc8c1e4c5f1a05848e8c2fcc012b8e8b5990631b8f6422070c359a97a68` |
| `groundedness_calibration.jsonl` | 17,751,531 | `459d7376fc4c50104b0d0de4c6ff1e5f046e09aa82ed8f000a798d45d418c965` |
| `groundedness_test.jsonl` | 16,143,804 | `bc8c5a5ea93ff823124ac9646639ceb82e8b17a9a865354b5f6baa16c57479ad` |

### 审计证据

- 原始输入预检：通过；SHA-256 `577b506a63f2930e62646c94458c3b1039fd1abfb5a89be6b065236026e2249d`。
- corpus manifest：SHA-256 `629f7dd729fd8b9217467f6d8acbc3846514c56f4480aea1a8b281fdb42a21ab`。
- 完整候选 release audit：通过；SHA-256 `a8758323a95af5e4c1726066a52dafb966185670649d687477ae6a0e81f51b5e`。
- 切分后 release audit：通过；SHA-256 `c5cae2bd4d93e3586707f930c9d8f1967b51ed3d10bc7874374c9349741251fb`。
- 受保护输入超限 0；不可信 HoVer 冲突 0；最大固定答案占比 0.933%；最大来源单标签占比 64.344%；跨 split family 泄漏 0。
- 本地回归：125 passed，6 skipped only because local PyTorch is absent；训练机全量回归：125 passed。

### 2026-08-26 E5 一轮 smoke 结果

- 远端目录：`/root/autodl-fs/rag-guard-v4-1/runs/e5-smoke-e1`
- checkpoint SHA-256：`7605e2cb0dc5a7fd001f5f8970a82aa09a52750b52e8afef7d5451c9a7e4d7ad`
- calibration slice audit SHA-256：`9904124fc2e52ced10e3668267103529f0d07f993da8f7cf0635d092456885d9`
- `test_evaluated=false`，`metrics.test=null`；本轮没有打开冻结 test。

| 指标 | v4 历史最终结果 | v4.1 E5 smoke calibration |
|---|---:|---:|
| Answerability macro-F1 | 0.877685 | 0.870028 |
| Groundedness macro-F1 | 0.768803 | 0.922468 |
| CONTRADICTED precision | 0.697967 | 0.909976 |
| CONTRADICTED recall | 0.769477 | 0.802575 |

语言切片：英文 Groundedness macro-F1 `0.922332`，中文 `0.926380`；中文冲突 precision/recall 为 `0.895377/0.821429`，英文为 `0.914842/0.796610`。主要困难类型 recall：否定 `1.000000`、范围翻转 `1.000000`、合同冲突 `0.962963`、单位 `0.947368`、多跳 `0.890710`、金额 `0.873134`、日期 `0.755556`、实体 `0.455670`。

结论：标签边界、模板捷径和大部分困难冲突的系统性问题已明显改善，但 `WRONG_ENTITY` 和 `WRONG_DATE` 尚未修复到发布水平；`eligible=false`，不得导出或部署。用户要求启动的 5 epoch 新运行已在首轮完成前停止，未生成 checkpoint，也未污染 smoke 目录。

逐条错例与内容重分片审计见 `SMOKE_ERROR_AUDIT_V4_1.md`。该审计确认当前 `WRONG_ENTITY` 实际混合了同段落任意错误答案，英文日期又大量被归入 `WRONG_AMOUNT`；五轮对照完成前保持生成器不变，避免混淆“增加 epoch”这一单一干预。

### 2026-08-26 E5 五轮诊断训练

用户明确要求在相同数据、基础模型、seed、batch、学习率和 token budget 下运行五轮，以隔离“增加 epoch”的影响。该诊断超过原计划的四轮发布上限，不视为放宽发布策略，也不触发 frozen test。

- 远端目录：`/root/autodl-fs/rag-guard-v4-1/runs/e5-full-e5-v2`
- 本地备份：`D:\MiniCPM-V\private-training\rag-guard-v4-1\evidence\e5-full-e5-v2`
- `test_evaluated=false`，`metrics.test=null`
- 远端全量回归：126 passed

| epoch | train loss | Answerability macro-F1 | Groundedness macro-F1 | 冲突 precision | 冲突 recall | 关系绑定 recall | 声明日期 recall |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 1.144648 | 0.867784 | 0.921928 | 0.906457 | 0.805794 | 0.432990 | 0.755556 |
| 2 | 0.533491 | 0.895206 | 0.943061 | 0.947115 | 0.845494 | 0.632990 | 0.777778 |
| 3 | 0.412474 | 0.903602 | 0.948813 | 0.926722 | 0.902361 | 0.773196 | 0.688889 |
| 4 | 0.341607 | 0.907461 | 0.952547 | 0.935000 | 0.902897 | 0.762887 | 0.733333 |
| 5 | 0.299007 | 0.911266 | 0.955183 | 0.930359 | 0.917382 | 0.800000 | 0.755556 |

自动 checkpoint 排名选择 epoch 4，而不是最后一轮；因此目录中的 `model.safetensors` 和最终 `calibration` 指标对应 epoch 4。epoch 5 只保留在 history 中，没有独立 checkpoint。最佳 checkpoint 的八个声明困难切片 recall 为：合同 `0.962963`、多跳 `0.923497`、否定 `1.000000`、范围 `1.000000`、金额 `0.937811`、日期 `0.733333`、关系绑定（旧名 `WRONG_ENTITY`）`0.762887`、单位 `1.000000`。

最佳 checkpoint 的内容重分片结果：英文日期样式（被归入 `WRONG_AMOUNT`）recall `0.945652`，非日期金额样式 `0.931193`，声明日期 `0.733333`，关系绑定 `0.762887`。相对独立一轮 smoke，关系绑定从 `0.455670` 提升 `0.307216`，英文日期样式从 `0.858696` 提升 `0.086956`；声明日期从 `0.755556` 降至 `0.733333`。结论是增加 epoch 明显缓解关系绑定欠拟合，但中文日期切片仍不稳定，且冲突 precision `0.935` 未达到发布要求；不得导出部署或评估 frozen test。

### 五轮制品 SHA-256

| 文件 | SHA-256 |
|---|---|
| `model.safetensors` | `82b9f49c95cd7115108999d48442690dc266392dc744547777cfccf391bfcb65` |
| `metrics.json` | `99819e6d1cce9f36a7d9de4785a894c452a2df3ef8e30534010f682a96c324f0` |
| `manifest.json` | `b8df3459a5c94713d91c02e49009d9138e73dbf156209cec323f4bbfa5f40355` |
| `calibration-slice-audit.json` | `8e0e5c7d5cdec2e526f7b68039670d78b0918029139cb7ae37d5081ed6e4d21c` |
| `calibration-errors.jsonl` | `1b74481ecf98daf118b254e6f1bf9fad15f90f82beacb33eadab5d4154089c9e` |

### 下一步（当前暂停点）

1. 按 `SMOKE_ERROR_AUDIT_V4_1.md` 的证据，以新 transform 版本修复同段落错误答案类型匹配和英文日期识别；旧 v4.1 split 与五轮结果保持冻结。
2. 重建并重新审计新数据后，进入固定 NLI 初始化模型 A/B；架构比较仍只允许使用 calibration。
3. 在 architecture 和阈值锁定前不得评估 v4.1 frozen test。

### 已执行的 E5 smoke 命令

训练主机完成代码、数据和基础模型哈希复核后执行：

```bash
cd /root/autodl-fs/rag-guard-v4-1
python -m pytest \
  tools/rag_guard/test_model.py \
  tools/rag_guard/test_training_pipeline.py \
  tools/rag_guard/test_hard_types_v4.py \
  tools/rag_guard/test_training_data.py -q

python -m tools.rag_guard.train \
  --model /root/autodl-fs/rag-guard-v4/model-base/multilingual-e5-small \
  --data-dir /root/autodl-fs/rag-guard-v4-1/generated/splits \
  --output-dir /root/autodl-fs/rag-guard-v4-1/runs/e5-smoke-e1 \
  --epochs 1 \
  --batch-size 16 \
  --eval-batch-size 32 \
  --gradient-accumulation 2 \
  --max-length 256 \
  --learning-rate 2e-5 \
  --seed 42 \
  --bf16
```

该命令已在训练机完成，输出和哈希见本节结果；命令未包含 `--evaluate-test`。

## 本轮发现并修复的异常

1. 隐私审计误扫生成 ID 中的随机数字为手机号：修正为只扫描问题、证据、回答和原子断言正文。
2. HoVer 同一 `hpqa_id` 多个 positive sibling 重复输出同一 negative UID：改为每个 negative UID 每 family 只生成一次。
3. ContractNLI 句末邮箱后接句号未被旧正则脱敏：统一为审计器使用的邮箱模式并加入回归测试。
4. 原 MinHash 实现会保留所有字符 shingle 并执行 32 重哈希，不适合 27 万行：改为有界 bottom-k 词组签名，同时保留全部强制族级同组规则。

## 下一步

1. 提交并冻结转换器代码，使用新 commit 重新生成最终发布语料和哈希。
2. 在训练主机现有 PyTorch/CUDA 环境运行 3+4 双头张量测试。
3. 执行三组预定义消融并开始正式训练；不得更改冻结 test 或降低硬门槛。

## 2026-08-26 v4.2 数据修复与审计

### 修复内容

- 新增 `qa_repairs_v4_2.py`：中英文日期/金额分类、同证据类型匹配 distractor、offset/tokenizer 可见证据窗口。
- QA 生成器拒绝标点-only 抽取答案；中文跨文档负例不再使用参考编号模板；关系反例在无法与真答案共存于 256 token 窗口时只移除关系 sibling，不丢弃整个答案族。
- release correctness gate 新增 `decisive_qa_evidence_not_visible_rows`；full audit 必须为 0。
- Groundedness release balance 按批准数据真实供给记录 v4.2 约束：来源上限 80%、中文冲突下限 3%；没有复制中文样本或降低 token/privacy/family 门禁。

### 生成与切分

- 根目录：`D:\MiniCPM-V\private-training\rag-guard-v4-2\generated\corpus-e`
- transform：`rag-guard-v4.2-full-corpus-1`
- seed：`rag-guard-v4.2-full-corpus-e`；split seed：`rag-guard-v4.2-split-e`
- Answerability 120,000：SUPPORTED/PARTIAL/UNSUPPORTED = 48,000/30,000/42,000；中文每类 600。
- Groundedness 150,000：GROUNDED/PARTIAL/UNSUPPORTED/CONTRADICTED = 45,000/37,500/30,000/37,500。
- split：train 243,090、calibration 13,609、test 13,301；全局 ID 唯一，五个保护族跨 split 交集均为 0。
- token rejected：Answerability 0、Groundedness 453；orphaned contradiction family 440；决定性证据不可见 0。

### 发布审计与哈希

- full release audit：通过，报告 `D:\MiniCPM-V\private-training\rag-guard-v4-2\audits\full-release-audit.json`，SHA-256 `3f003f4879d733a0792011486128101edcf3409889263aafab99142340604fbd`。
- post-split schema/privacy audits：train/calibration/test 均通过；报告 SHA-256 分别为 `cf079ef4e75431f7f76e8c0dda2a18135139ec81faadfbb12a3eb44ae534977`、`5ec44976de93c41e510f1d29eef81f3b6476b7dddd1d5c112cc2bac40af46f31`、`7e2e7246aaa39b7f76dd86eb22276653cf07f3437bdc582bf1b445e4b0f0dc75`。
- corpus：`answerability.jsonl` `7a46a838e23c91e5027866a9c25d950d5eb6ad394581d99d3cbbb4d68bfb8fd0`；`groundedness.jsonl` `2b4b3b8f5331d552e05a0e49fe91d114ca1ab15dd2029d848c68b9e2ce30fa48`；manifest `296d64f1dc61481caea2e5d3288a5d68201bb660c32aae36abfbd5711777c694`。
- aggregate splits：train `c19e6f8ac3bfe17931eb89ee051ea127248879ec226b066f4d0c8bc70a24ee9`；calibration `659b90a8f33adc3d652b5abcefe36847fee976d25ba08c9e802b58cdf6df790c`；test `6128e373b18252052fc9807ab4ee9514767084d2673a6e4bf87b1cde32d3c130`。
- full local regression：`139 passed, 6 skipped`，仅因本机未安装 PyTorch 的张量测试跳过；不得据此启动重训。

v4.2 当前状态为“数据可训练候选，首轮 E1 calibration-only 已完成但未达到发布门槛”；冻结 test 仍未读取。

### v4.2 E1 calibration-only 训练（2026-08-27）

- 训练主机：RTX 3080 10 GB；Python 3.10.8、PyTorch 2.4.1+cu121、Transformers 4.53.3、CUDA 可用。
- 远端目录：`/root/autodl-tmp/rag-guard-v4-2/runs/e5-calibration-e1`。
- 固定参数：E1、batch 16、eval batch 32、gradient accumulation 2、max length 256、learning rate `2e-5`、seed 42、BF16；未传入 `--evaluate-test`。
- 远程 pytest：`139 passed, 9 subtests passed`；`pip check` 通过。
- `manifest.test_evaluated=false`、`metrics.test=null`、`release_eligible=false`。

| 指标 | E1 calibration |
|---|---:|
| train loss | 0.953636 |
| Answerability macro-F1 | 0.875083 |
| Groundedness macro-F1 | 0.923570 |
| CONTRADICTED precision | 0.939547 |
| CONTRADICTED recall | 0.777488 |
| WRONG_DATE recall | 0.901478 |
| WRONG_ENTITY（关系绑定）recall | 0.318519 |

八个困难切片 recall：合同 `0.937500`、多跳 `0.940000`、否定 `1.000000`、范围 `1.000000`、金额 `0.936000`、日期 `0.901478`、关系绑定 `0.318519`、单位 `1.000000`。关系绑定仍是主要瓶颈，因此本轮只作为 calibration 诊断，不导出 Android 或评估 frozen test。

远端制品 SHA-256：`model.safetensors` `5985e8121caba7bae579b750016040305cddd526f5fe50fb6549fb41aa3c32a0`；`metrics.json` `5b51b1bf5031a3f1b45230cc4627b12e0db754925ef4b2ab5c7fd81b8c4335f0`；`manifest.json` `021fc5b33c104749056a1521bc828dff1755a9978338defbe55c52bc993bd89a`；`training-dynamics.json` `f3d831b443f1f15d03bfdfb336418e7e9a566b4258c70ec673e13e84a2f19d23`；`review.jsonl` `fb07d72ab31b7c780c00777f2bc716e39b54e64d535998841d896f561ef603d3`。

## 2026-08-27 v4.2 五轮 E5/NLI calibration-only A/B

### 实验边界

- 远端根目录：`/root/autodl-tmp/rag-guard-v4-2`；RTX 3080 10 GB。
- 两组共用 `splits-e`、seed 42、batch 16、eval batch 32、gradient accumulation 2、max length 256、learning rate `2e-5`、BF16 和 5 epoch；只改变 encoder 初始化。
- E5：固定本地 `multilingual-e5-small`，基础权重 SHA-256 `1a55775f53449dac10a2bcbc312469fac40b96d53198c407081a831f81c98477`。
- NLI：`MoritzLaurer/multilingual-MiniLMv2-L6-mnli-xnli`，固定 commit `0a71e92a985b6e1ad1828cf67ce9c459639c1dca`，基础权重 SHA-256 `91b323ccf247ec1e3b5925d566230bae7c52de8147e6062b42e250089a3fc80b`。
- 两组 `metrics.json` 均为 `test_evaluated=false`、`test=null`；两组 `manifest.json` 均为 `test_evaluated=false`。未加载、读取或评估 frozen test。
- 远端全量回归：`139 passed, 9 subtests passed`。

### 五轮历史

| 模型 | epoch | train loss | Answerability macro-F1 | Groundedness macro-F1 | CONTRADICTED precision | CONTRADICTED recall |
|---|---:|---:|---:|---:|---:|---:|
| E5 | 1 | 1.161001 | 0.869195 | 0.916222 | 0.964286 | 0.731631 |
| E5 | 2 | 0.560645 | 0.891866 | 0.947368 | 0.945444 | 0.875977 |
| E5 | 3 | 0.436557 | 0.897869 | 0.949927 | 0.943839 | 0.902032 |
| E5 | 4 | 0.364896 | 0.907691 | 0.956991 | 0.951683 | 0.913497 |
| E5 | 5 | 0.317286 | 0.907157 | 0.958618 | 0.949652 | 0.923919 |
| NLI | 1 | 1.499174 | 0.842124 | 0.923511 | 0.930657 | 0.797290 |
| NLI | 2 | 0.641985 | 0.872487 | 0.945043 | 0.931319 | 0.883273 |
| NLI | 3 | 0.526526 | 0.881703 | 0.947969 | 0.929412 | 0.905680 |
| NLI | 4 | 0.463478 | 0.885535 | 0.952122 | 0.945634 | 0.897342 |
| NLI | 5 | 0.425436 | 0.890710 | 0.954544 | 0.942349 | 0.911412 |

自动选模保存 E5 epoch 4 和 NLI epoch 5。对保存 checkpoint 重新运行 `checkpoint_audit_v4`，只读取 calibration：

| 保存 checkpoint | Answerability macro-F1 | Groundedness macro-F1 | CONTRADICTED precision/recall | 合同 | 多跳 | 否定 | 范围 | 金额 | 日期 | 关系绑定 | 单位 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| E5 epoch 4 | 0.907691 | 0.956991 | 0.951683 / 0.913497 | 0.937500 | 0.977143 | 1.000000 | 1.000000 | 0.980000 | 0.975369 | 0.729630 | 1.000000 |
| NLI epoch 5 | 0.890556 | 0.954531 | 0.941842 / 0.911412 | 0.968750 | 0.960000 | 0.998004 | 1.000000 | 0.996000 | 0.955665 | 0.733333 | 1.000000 |

### 内容重分片与架构选择

`WRONG_ENTITY` 仅按 v4.2 的真实生成语义解释为“同证据、同粗粒度答案类型的关系绑定冲突”。E5 从独立 E1 的 `0.318519` 提升到 `0.729630`，NLI 从 `0.316667` 提升到 `0.733333`，证明两种初始化都随训练轮次显著改善关系绑定；NLI 只领先 `0.003704`，不构成整体优势。

`WRONG_AMOUNT` 内容日期样式按语言和来源重新统计：英文/SQuAD 2.0 共 151 条，E5 从 E1 `0.933775` 提升到 `0.966887`，NLI 从 `0.953642` 提升到 `0.993377`；中文/CMRC 只有 1 条，不能据此推断中文日期泛化。英文非日期金额 98 条，E5/NLI 五轮均为 `1.000000`。声明 `WRONG_DATE` 仍单独报告，不能与该内容切片混用。

按 calibration-only 主指标选择 E5 作为后续架构：相对 NLI 保存 checkpoint，Answerability macro-F1 高 `0.017135`、Groundedness macro-F1 高 `0.002460`、CONTRADICTED precision 高 `0.009841`、recall 高 `0.002084`。NLI 的关系绑定与英文日期样式略好，但不足以抵消 Answerability 和冲突 precision 的下降。两组当前仍 `release_eligible=false`，所以只冻结架构选择，不导出 Android、不固定生产 profile，也不打开 frozen test。

### 耗时、显存与制品

- E5：2026-08-27 11:12:09 至 12:18:21 CST，约 3,972 秒。
- NLI：2026-08-27 12:18:46 至 13:01:24 CST，约 2,558 秒。
- 串行流水线：约 6,555 秒（1 小时 49 分 15 秒）。
- 本轮训练启动器没有记录 `torch.cuda.max_memory_allocated()`，训练结束后无法可靠还原峰值显存；验收记录明确保存为 `null`，不以瞬时 `nvidia-smi` 或估算值替代。下一轮启动器必须在进程内记录峰值。
- E5 输出模型 SHA-256：`df1cca834ff8d37fb286221ed8a9cc67bc7c91ee30e0757913dccd766acf87850`。
- NLI 输出模型 SHA-256：`c592453af598667378bc6df0a5c4cfe751572299f46ae625c37d21852da329f3`。
- 聚合内容重分片 SHA-256：`c06bb7da13262cb4e9c40307ce6a019666c1fd1c35c79c840894c2feeee005bcb`。
- E5/NLI 错例清单 SHA-256：`520c771843d3f02d34a542c85ce288a45935929e6b85aa3017f390af80219eec6` / `8f1cc5e1ad7f0c5f3fb87328c2f6c870acea007f8d6eee921e6f42f3952977adb`。
- 私有备份：`D:\MiniCPM-V\private-training\rag-guard-v4-2\evidence\e5-calibration-e5`、`...\nli-calibration-e5`、`...\aggregate`；本地对 16 个关键制品复算 SHA-256，全部与远端验收清单一致。

下一步仅围绕选定 E5 架构做阈值校准和生产导出准备；在架构、阈值与真实办公评测协议冻结前，继续保持 frozen test 未读。

## 2026-08-25 训练运行

### 首轮 2 epoch 基线

- 环境：RTX 3080 Ti 12 GB、PyTorch 2.4.1+cu121、Transformers 4.53.3、BF16。
- 总耗时：约 30 分 25 秒。
- Epoch 1：Answerability macro-F1 `0.8660`，Groundedness macro-F1 `0.7998`，`CONTRADICTED` precision `0.9428`。
- Epoch 2：Answerability macro-F1 `0.8751`，Groundedness macro-F1 `0.8028`，`CONTRADICTED` precision `0.9196`。
- 结果：退出码 1。两轮均未满足冻结发布门槛；旧逻辑只保存合格 checkpoint，因此本轮没有模型文件。

### 加权 4 epoch 运行

- 启动时间：2026-08-25 10:42:45 CST。
- 输出目录：`/root/autodl-fs/rag-guard-v4/runs/full-v4-weighted-e4`。
- 固定参数：seed 42、max length 256、batch 16、gradient accumulation 2、learning rate `2e-5`、BF16。
- 受控调整：Groundedness loss 权重由 `1.5` 调为 `2.0`，困难对排序损失权重由 `0.25` 调为 `0.5`。
- 选模调整：发布门槛保持不变；每轮均计算诊断选模顺序并保存 calibration 最优 checkpoint，manifest 和 metrics 显式记录 `release_eligible`。
- 最终 test 只在 calibration 完成选模后评估，不参与调参。
- 最佳 checkpoint：Epoch 3；`release_eligible=false`。
- 冻结 test：Answerability macro-F1 `0.8750`，Groundedness macro-F1 `0.8016`，`CONTRADICTED` precision `0.9113`。

### 稳定化数据 4 epoch 运行

- 启动时间：2026-08-25 15:02:07 CST。
- 输出目录：`/root/autodl-fs/rag-guard-v4/runs/full-v4-stable-data-e4`。
- 唯一实验变量：替换为稳定化 train/calibration；基础模型、冻结 test、seed 42、max length 256、batch 16、gradient accumulation 2、learning rate `2e-5` 和基线 loss 权重保持不变。
- Groundedness 矛盾切片：否定 10,000、错误实体 9,650、金额 7,750、日期 1,000、单位 400、多跳 7,500、合同矛盾 850、范围翻转 350。
- 矛盾语言：中文 10,000、英文 27,500；最大来源占比 `0.5013`；GROUNDED sibling 覆盖率 `1.0`。
- 冻结 test：`all_test.jsonl`、`answerability_test.jsonl`、`groundedness_test.jsonl` 的大小和 SHA-256 与上一版本逐字节一致。
- 最终切分：train 243,949、calibration 12,382、test 13,771；排除与旧 test family 或短文本近重复相连的新增候选后共 270,102 行。
- 训练动态：每轮 calibration 记录 row ID、gold probability、预测标签和翻转次数；输出不包含问题、证据或回答正文。
- 退出码：`0`；自动选择 Epoch 2；`release_eligible=false`。
- 冻结 test：Answerability macro-F1 `0.877685`，Groundedness macro-F1 `0.768803`，`CONTRADICTED` precision `0.697967`、recall `0.769477`。
- 模型 SHA-256：`cc38a1c58b109f9a1c26c705a2a8342fa5e7266a5b9b66bedea1a6b4b20b84f0`。
- 训练动态筛出 3,725 行，其中 Answerability 991、Groundedness 2,734。后续审计确认其主要成因是候选回答位于长证据之后并被右截断，而不是 epoch 不足。

#### 稳定化六文件 SHA-256

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `answerability_train.jsonl` | 229,582,269 | `5c407bb69481f1438963c37d111e72b74c01edac225477eba799f4c52fa7b2d1` |
| `answerability_calibration.jsonl` | 11,565,100 | `31fc83df39f39b06275764b7545945480f59183ac21eaae849d93aea1cb4ff23` |
| `answerability_test.jsonl` | 13,251,875 | `179933be9c9d62469b7f1ffa027685810ae9f77849922e2e3a2168388c760e6a` |
| `groundedness_train.jsonl` | 315,990,211 | `b89c926a642705c307f4c52a685b155d51340a5c63033cef169d0d0143d0e282` |
| `groundedness_calibration.jsonl` | 15,586,761 | `ed0c3f8c2044f3900455de8fb9a893e14a86f2c8b1d8aef7d7f88624e10032ab` |
| `groundedness_test.jsonl` | 16,758,748 | `bb18ee387edb8fc152781fa1af46c05df741f7955d366340ce50d706dedf17ce` |
