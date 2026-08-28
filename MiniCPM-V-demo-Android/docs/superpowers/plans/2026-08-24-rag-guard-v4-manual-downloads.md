# RAG Guard v4 手动下载清单

所有文件放在 `D:\MiniCPM-V\private-training\rag-guard-v4\raw` 下。请保留原文件名，不要解压到 Git 仓库中。

## 1. ContractNLI（已完成）

1. 打开官方页面：<https://stanfordnlp.github.io/contract-nli/>
2. 阅读页面底部 Terms and Conditions of Use；只有你本人同意后才点击 `DOWNLOAD`。
3. `contract-nli.zip` 已完整下载、通过 ZIP 安全检查并归档。
4. 用户已于 2026-08-24 明确确认接受条款；来源已设为 `enabled=true`，用途限定为本项目模型训练和评测。

不要由自动化工具代替用户接受该点击条款。

## 2. SQuAD 2.0

- 训练集已接收并校验，无需再次下载。
- 开发集已通过附件接收并校验，无需再次下载。
- 保存目录：`D:\MiniCPM-V\private-training\rag-guard-v4\raw\squad_2`
- 文件名必须分别为 `train-v2.0.json`、`dev-v2.0.json`。

目录里的 `train-v2.0.json.part` 是历史不完整片段，不参与构建；正式训练集已经归档。

## 3. CMRC 2018

`cmrc2018_train.json` 与 `cmrc2018_dev.json` 均已下载并校验，无需再次下载。

- 开发集哈希：`b522907e2beb8e4de711d5c84026921bd189cd47f40599caf3f77c6e52f35993`

## 4. HoVer（已完成）

`hover_dev_release_v1.1.json`、`hover_train_release_v1.1.json` 和 `wiki_wo_links.db` 均已下载、校验并归档。

- 保存目录：`D:\MiniCPM-V\private-training\rag-guard-v4\raw\hover`
- `wiki_wo_links.db` 大小为 2,156,273,664 字节，SHA-256 为 `c37ee397916ec0bffacfe8902db454a5cda88a7a188409217b2e15231fe5ee2f`。

此前全文粘贴的 HoVer 文件发生尾部截断，随后提供的 9,205,582 字节原始文件已替代该附件。

HoVer 的发布 JSON 只保存 supporting-fact 标题和句子编号；构造真实证据文本必须同时有官方 `wiki_wo_links.db`。

## 下载完成后的自检

在 PowerShell 执行：

```powershell
Get-ChildItem 'D:\MiniCPM-V\private-training\rag-guard-v4\raw' -Recurse -File |
    Select-Object FullName, Length
```

全部 SHA-256 已由本地工具计算并写回 registry。下一步运行完整数据转换、族级切分和 fail-closed 数据集审计。
