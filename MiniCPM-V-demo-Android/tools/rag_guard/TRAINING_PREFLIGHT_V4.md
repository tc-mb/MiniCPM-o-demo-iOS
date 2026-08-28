# RAG Guard v4 训练前状态

更新时间：2026-08-24

## 已完成

- v3 与 v4 标签契约隔离：现有 v3 继续使用 Groundedness 三分类，v4 显式使用 Answerability 三分类和 Groundedness 四分类。
- schema v2、许可登记、来源追踪、SHA-256、原子断言和隐私检查。
- Answerability 与 Groundedness 的构造器、最小对变异器、确定性近重复聚类和族级切分。
- 四维统一输出的 3+4 双头模型代码；Answerability 第四位固定屏蔽。
- 三类/四类独立交叉熵、困难最小对排序损失和每批困难对采样器。
- checkpoint 硬门槛：Answerability macro-F1、Groundedness macro-F1、`CONTRADICTED` precision、最差困难组 recall 和 ECE。
- fail-closed 训练输入预检：文件未完整、许可未批准、大小或 SHA-256 未冻结时禁止构建训练集。

## 当前自动化验证

- `tools/rag_guard`：71 项测试通过，4 项依赖 PyTorch/Transformers 的张量测试因本机未安装训练依赖而跳过。
- `model.py`、`train.py` 和 v4 工具均通过 Python 语法编译。
- 训练前预检当前应返回 `ready_for_dataset_build=false`，这是预期结果，不是程序故障。

## 已完整下载并校验

| 来源 | 文件 | 字节数 | SHA-256 |
|---|---|---:|---|
| CMRC 2018 | `cmrc2018_train.json` | 7,408,757 | `5497aa2f81908e31d6b0e27d99b1f90ab63a8f58fa92fffe5d17cf62eba0c212` |
| CMRC 2018 | `cmrc2018_dev.json` | 3,367,259 | `b522907e2beb8e4de711d5c84026921bd189cd47f40599caf3f77c6e52f35993` |
| HoVer | `hover_dev_release_v1.1.json` | 2,153,439 | `67c14858f2d7fcdb96b6fe3d538ffcd6f76e3ba594aa2c0cd4359f601101e89d` |
| HoVer | `hover_train_release_v1.1.json` | 9,205,582 | `1f1cd57abd616fa00c70bdc575ce77c16fc6cf1a6cffd5ff87c208030a336bb6` |
| HoVer | `wiki_wo_links.db` | 2,156,273,664 | `c37ee397916ec0bffacfe8902db454a5cda88a7a188409217b2e15231fe5ee2f` |
| SQuAD 2.0 | `dev-v2.0.json` | 4,370,528 | `80a5225e94905956a6446d296ca1093975c4d3b3260f1d6c8f68bc2ab77182d8` |
| SQuAD 2.0 | `train-v2.0.json` | 42,123,633 | `68dcfbb971bd3e96d5b46c7177b16c1a4e7d4bdef19fb204502738552dede002` |
| ContractNLI | `contract-nli.zip` | 65,362,913 | `e03fc77bbf8b53e2976a250e81d8a294bc3d5e5fb014521e477dee9340d6287b` |

ContractNLI 条款已由用户于 2026-08-24 明确确认，使用范围为本项目模型训练与评测；registry 不保存身份信息。

## 原始数据验收完成

全部必需来源均已下载、归档并冻结哈希。HoVer SQLite 数据库通过 `PRAGMA quick_check`，包含 5,233,329 篇非空文档；训练与开发集的 18,299 个唯一 supporting-fact 标题在 Unicode NFD 规范化后覆盖率为 100%。SQuAD 的 `.part` 文件只是旧的未完成片段，不参与构建。

## 下载后执行顺序

1. 运行 `prepare_training_v4`，计算并冻结所有完整文件的大小和 SHA-256。
2. 解析各来源并生成 schema v2 JSONL；原始正文和生成数据均留在 `D:\MiniCPM-V\private-training`，不提交 Git。
3. 执行隐私、许可、去重和族级切分审计，要求全部跨 split 交集为零。
4. 在具有既定 PyTorch/CUDA 环境的训练主机运行四项张量级测试。
5. 到此才允许启动三组消融和正式训练。本文件之前的任何步骤都不构成模型训练。

## 2026-08-24 正式候选语料

已生成 120,000 行 Answerability 和 150,000 行 Groundedness，完成 90/5/5 族级切分和全量审计。详细数量、异常与六个文件哈希见 `TRAINING_RUN_V4.md`。当前转换器仍在未提交工作区，因此训练前需提交代码并用最终 commit 再生发布语料。
