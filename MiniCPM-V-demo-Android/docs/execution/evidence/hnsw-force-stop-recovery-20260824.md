# HNSW 真实 force-stop 恢复矩阵（2026-08-24）

设备：vivo V2359A。每个场景只在 `noBackupFilesDir/rag/hnsw-force-stop-test` 使用合成字节、临时 AES 测试密钥和独立索引名；未读取或修改用户知识库与正式 HNSW 索引。

执行方式为两个进程阶段：测试进程写入并 `fsync` 就绪标记后永久等待，主机确认标记并执行 `adb shell am force-stop com.example.minicpm_v_demo`，随后由新 instrumentation 进程验证恢复哈希、临时文件和明文清理。

| 中断窗口 | 恢复结果 | 临时残留 |
|---|---|---|
| 构建阶段明文候选 | 生产白名单清理候选 | 无 |
| AES-GCM payload 加密中途 | 恢复上一代认证索引 | 无 |
| payload 已原子提交、metadata 未提交 | 恢复上一代认证索引 | 无 |
| payload 与 metadata 已提交、finalize 前 | 接受新一代认证索引 | 无 |

首次“加密中途”验证发现 Android `AtomicFile` 留下 0 字节 `.new` 文件；索引哈希恢复正确，但清理断言失败。`HnswIndexPublisher` 已在成功验证或恢复后删除当前受管 payload/metadata 的精确 `.new/.bak` 残留，并使用同一中断现场复验通过。

新增发布阶段回调时还发现 Kotlin 尾随 lambda 一度被绑定到错误参数；已恢复 `shouldContinue` 为最后一个参数。修复后直接受影响的 `HnswIndexPublicationInstrumentedTest` 为 `8/8` 通过，取消、恢复、认证、并发与 finalize 语义均保持不变。所有 force-stop 测试目录已删除。
