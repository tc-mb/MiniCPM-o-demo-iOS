# ADR-001：Android 端本地 RAG 技术栈

- 状态：已接受，分阶段实施
- 日期：2026-08-10
- 适用范围：`MiniCPM-V-demo-Android`

## 背景

应用已经通过 `llama.cpp-omni` 与 JNI 在本地运行 MiniCPM-V。办公场景需要在断网条件下导入用户文档、检索证据、生成带来源的回答，同时避免引入第二套大模型运行时或把文档上传到云端。

## 决策

采用自建的端侧 RAG 流水线：

```text
Storage Access Framework
  -> 私有目录安全复制
  -> 文本解析 / OCR
  -> 结构化切块
  -> multilingual-e5-small INT8 嵌入
  -> Room + SQLCipher + FTS4 + HNSW
  -> 混合检索、RRF 融合、MMR 去重
  -> 临时证据 Prompt
  -> 现有 MiniCPM-V 流式生成
  -> 引用校验与来源查看
```

固定首批组件版本：

| 组件 | 版本 | 用途 |
|---|---:|---|
| Room | 2.8.4 | 关系数据、迁移、FTS4 |
| WorkManager | 2.11.2 | 可恢复的索引任务 |
| SQLCipher Android | 4.17.0 | 数据库静态加密 |
| AndroidX SQLite | 2.6.2 | Room 与 SQLCipher 接口层 |
| ONNX Runtime Android | 1.25.0 | 本地嵌入推理 |
| ONNX Runtime Extensions | 0.13.0 | tokenizer 扩展算子 |
| ML Kit Text Recognition | 16.0.1 | 离线中英文 OCR |
| PDFBox-Android | 2.0.27.0 | PDF 文本提取 |
| hnswlib | 0.9.0 | 向量近邻索引 |

嵌入模型采用 `intfloat/multilingual-e5-small` 的量化 ONNX 包。向量维度固定为 \(384\)，查询和文档分别使用 `query: ` 与 `passage: ` 前缀。模型包、hnswlib 源码和数据库 schema 均必须记录版本与 SHA-256。

## 关键边界

- RAG 证据只在本轮生成时注入，不写入长期会话消息，也不永久留在 KV 缓存。
- 文档内容一律按不可信输入处理，文档中的指令不能覆盖系统提示或用户问题。
- 无足够证据时返回明确的无结果提示；引用必须能映射到真实 chunk。
- 首期不支持旧版二进制 Office 文件；要求用户另存为 OOXML 或 PDF。
- 默认不联网。模型下载或更新必须由用户主动触发并经过哈希校验。

## 未采用方案

- Google AI Edge RAG SDK：已弃用，且会引入与现有 JNI 推理并行的第二套 LLM 架构。
- 只使用生成模型隐藏层做嵌入：难以稳定复现，检索质量和版本控制不足。
- 纯向量检索：对合同编号、料号、人名等精确词不可靠，因此保留 FTS4 混合召回。
- 远程向量数据库或嵌入 API：破坏离线和隐私目标。

## 后果

优势是离线、数据不出端、与现有推理链路兼容且来源可追溯。代价是需要维护文档解析、嵌入模型、native 索引、数据库迁移和端侧性能治理；发布前必须完成恶意文件、安全、迁移、检索质量和长时间索引回归。
