# RAG Guard 中英文多来源训练集 v3

本数据集用于训练轻量 RAG 守卫，不用于微调 MiniCPM 聊天模型。共享编码器包含两个独立三分类头：

- Answerability：判断“问题 + 检索证据”是 `SUPPORTED / PARTIAL / UNSUPPORTED`。
- Groundedness：判断“问题 + 检索证据 + 候选回答”是 `GROUNDED / PARTIAL / UNGROUNDED`。

## 数据来源

| 来源 | 语言 | 场景 | 许可 | 用途 |
|---|---|---|---|---|
| SQuAD 2.0 | 英文 | 通用阅读问答 | CC BY-SA 4.0 | 文档证据 |
| Doc2Dial 1.0.1 | 英文 | 政务、公共服务 | CC BY 3.0 | 文档证据 |
| CUAD 1.0 | 英文 | 商务合同 | CC BY 4.0 | 文档证据 |
| CMRC2018 | 简体中文 | 通用阅读问答 | CC BY-SA 4.0 | 文档证据 |
| DRCD | 繁体中文 | 通用阅读问答 | CC BY-SA 4.0 | 文档证据 |
| DuReader Robust | 简体中文 | 真实搜索问答 | Apache-2.0 | 文档证据 |
| KdConv | 中文 | 电影、音乐、旅游知识对话 | Apache-2.0 | 文档证据与日常问题 |
| CrossWOZ | 中文 | 餐饮、酒店、景点、交通 | Apache-2.0 | 日常任务型问题 |
| OpenAssistant OASST1 | 中英文 | 问候、生活、开放讨论 | Apache-2.0 | 日常聊天困难负例 |

原始语料与生成 JSONL 只保存在受控训练目录，不提交 Git。生成清单记录输入文件 SHA-256、来源数量、
输出文件 SHA-256 和聚合标签统计，不记录正文。

## 构造规则

文档样本每份生成六条记录：原问题/证据、混合可回答问题、错误文档问题、原答案、夹带无依据条件的
部分答案和错误文档答案。日常问题与同语言的无关知识片段配对，增加 Answerability `UNSUPPORTED`，
用于避免问候和普通聊天被强制套用知识库。

切分以规范化后的源文档 ID 为单位，训练、校准和测试比例为 (90\%/5\%/5\%)。历史公开留出集中的
Doc2Dial/CUAD 文档 ID 在切分前排除。每个任务的三个标签与中英文使用共同最小计数下采样，因此：

$$
N_{zh,label}=N_{en,label},\qquad
N_{SUPPORTED}=N_{PARTIAL}=N_{UNSUPPORTED}
$$

Groundedness 三类同样严格平衡。所有随机选择使用固定 SHA-256 排序，不依赖运行时随机顺序。

## 当前规模

| 切分 | Answerability | Groundedness | 合计 |
|---|---:|---:|---:|
| train | 92,244 | 92,244 | 184,488 |
| calibration | 5,124 | 5,124 | 10,248 |
| test | 5,124 | 5,124 | 10,248 |

每个训练任务中，每个标签分别包含 15,374 条中文和 15,374 条英文记录。训练集实际保留 10,636 条
日常聊天无关证据负例。校准、测试中分别保留 601 和 576 条。

## 数据安全与质量

- ZIP/TAR 拒绝绝对路径、`..`、符号链接、重复成员、异常压缩比和超大展开体积。
- JSON/GZIP 设置文件、成员和单行长度上限，不执行远端代码或不可信反序列化。
- 邮箱、身份证号、中国大陆手机号和带 `+` 的国际电话号码被替换为占位符。
- 日期、金额、普通编号和历史年份保留；相应回归测试防止再次误判为手机号。
- 同一文档不得跨切分；输出 ID 唯一，内容做确定性去重。

## 训练环境

训练复用主机已有 Conda base、PyTorch 2.4.1+cu121 和 CUDA 12.1，不安装或升级 CUDA/PyTorch。
基础模型固定为 `intfloat/multilingual-e5-small` revision
`614241f622f53c4eeff9890bdc4f31cfecc418b3`。tokenizer、SentencePiece 和 tokenizer 配置的 SHA-256
与 Android 已备份版本一致。

## 本轮结果与接入状态

训练完成 2 个 epoch。第 2 轮校准集 Answerability/Groundedness macro-F1 分别为 `0.9975/0.8121`。
一次冻结独立测试结果如下：

| 模型 | Answerability macro-F1 | Groundedness macro-F1 | Groundedness ECE |
|---|---:|---:|---:|
| FP32 | 0.9897 | 0.8128 | 0.0080 |
| INT8 | 0.9885 | 0.8088 | 0.0098 |

INT8 文件为 118,169,267 bytes，SHA-256 为
`6d11400d62b8f15250932e3187aa7b7823809dc0baf0a0ff0a3c157dbe1d35fa`。量化标签一致率为 `0.9921`，
低于冻结门槛 `0.995`；旧回归种子集最大 macro-F1 降幅为 `0.0979`，因此稳定发布门槛失败。

按实验分支约束，本轮不继续重训或重复测试。Android 仅以 `0.95` 的保守 Answerability/Groundedness
阈值启用该包；模型缺失、SHA 不符、概率不足或输出审查失败时，恢复 RAG checkpoint 并重新走普通模型回答。
只有审查通过的回答显示数据库来源标识。训练 checkpoint、INT8 文件和完整指标保存在
`D:\MiniCPM-V\artifacts\rag-guard-dual-head-v3`。
