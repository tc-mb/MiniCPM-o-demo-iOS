# E5 执行提供程序真机选型（2026-08-21）

- 设备：vivo V2359A，SoC MT6989，Android API 36。
- 模型：固定 `multilingual-e5-small` INT8 ONNX；tokenizer 始终使用 CPU/ORT Extensions。
- 每档预热 5 次，测量 30 次；NNAPI 两档均设置 `CPU_DISABLED`，禁止静默退回 CPU。
- 测试查询覆盖中英文；结果只保存聚合指标，不保存查询正文。

| 配置 | 打开耗时 | P50 | P95 | 与 CPU 余弦 | 温度变化 |
|---|---:|---:|---:|---:|---:|
| CPU，2 threads | 1098.94 ms | 3.76 ms | 4.17 ms | 基线 | 34.7°C → 34.7°C |
| NNAPI | 3210.08 ms | 72.33 ms | 74.61 ms | 1.00000 | 34.7°C → 34.7°C |
| NNAPI FP16 | 2475.92 ms | 37.80 ms | 38.46 ms | 0.99698 | 34.7°C → 34.7°C |

三档均可运行且输出范数正常，但 CPU 比 NNAPI FP16 快约 9 倍、比普通 NNAPI 快约 18 倍，打开耗时也最低。因此生产固定 `E5ExecutionProfile.CPU`，不启用 NNAPI fallback。

E5 session 不再在 App 冷启动维护中创建：启动只校验固定模型文件 SHA-256；第一次实际知识库检索时懒加载，之后常驻。App 后台超过五分钟且收到系统内存回收等级后才关闭 session，下一次检索重新懒加载。

原始数据见 [e5-execution-provider-benchmark-20260821.json](e5-execution-provider-benchmark-20260821.json)。
