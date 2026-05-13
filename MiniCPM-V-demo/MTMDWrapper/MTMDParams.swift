//
//  MTMDParams.swift
//  MiniCPM-V-demo
//
//  Created by AI Assistant on 2024/12/19.
//

import Foundation
import llama

/// MTMD 参数配置
@frozen public struct MTMDParams: Sendable {

    /// 模型路径
    public let modelPath: String

    /// 多模态投影模型路径
    public let mmprojPath: String

    /// 预测长度
    public let nPredict: Int

    /// 上下文长度
    public let nCtx: Int

    /// 线程数
    public let nThreads: Int

    /// 温度参数
    public let temperature: Float

    /// 是否使用 GPU
    public let useGPU: Bool

    /// 多模态投影是否使用 GPU
    public let mmprojUseGPU: Bool

    /// 是否预热
    public let warmup: Bool

    /// llama_context 的 physical batch（n_ubatch）。
    ///
    /// 0 = 沿用 native bridge 内部默认（当前 512）；> 0 则覆盖。建议由调用方
    /// 通过 `MBDeviceMemoryProbe.recommendedUbatch` 按机型挑档：
    ///
    ///    tier=tiny   n_ubatch=128  → ~120 MiB MTL0 compute（4 GB iPhone）
    ///    tier=small  n_ubatch=256  → ~243 MiB           （6 GB iPhone）
    ///    tier=medium n_ubatch=512  → ~487 MiB           （8 GB iPhone Pro / iPad）
    ///    tier=large  n_ubatch=1024 → ~970 MiB           （12+ GB Pro / Mac）
    ///
    /// 老的 mtmd-ios.cpp 里 hardcoded n_ubatch=2048（~1.95 GB compute），是
    /// "新机器跑得飞快但旧机器闪退" 的根因。
    public let nUbatch: Int

    /// 单张图最大切片数（仅对 llava-uhd 风格模型生效，例如 MiniCPM-V）。
    /// - `-1`：按模型默认（MiniCPM-V 当前 9 片）
    /// - `1`：不切图（仅 overview，~9× 更少图像 token，速度最快但丢细节）
    /// - `2..9`：用户在对话页用滑条选的档位
    ///
    /// 注意：这是 demo UI 层的"slice 数"概念，**单位是 slice 数不是 token 数**。
    /// 迁移到 upstream master 后此字段**不直接透传给 bridge**（master mtmd 没有
    /// slice cap API，相关 knob 是单位为 token 数的 image_max_tokens，跟这里
    /// 单位不同）。运行时滑条调整目前是 no-op，需要 reset 模型才生效；
    /// 实际起作用的低端机内存保护走 `imageMaxTokens` 字段（见下）。
    public let imageMaxSliceNums: Int

    /// 单张图最大 token 数（master mtmd 的 image_max_tokens；单位 token 不是 slice）。
    ///
    /// 这是限制 vision encoder 最多产出多少 patch token 的 knob，间接控制
    /// minicpmv slicing 的 grid size：image_max_tokens 越小，mtmd 把图
    /// downscale 得越狠，模型选的 slice 数越少，prefill 期间 ViT 中间张量
    /// 的内存峰值也越小。
    ///
    /// 默认 `-1` 表示按模型默认（V4.6 上一般 9 个 slice，最大 ~600 token）。
    /// 低端机（`MBDeviceMemoryProbe.tier == .tiny`）上由 LoadModel 路径填
    /// 64 或 256 的小值，避免在多 slice 大图 prefill 时被 jetsam 杀掉。
    public let imageMaxTokens: Int

    /// 初始化方法
    /// - Parameters:
    ///   - modelPath: 模型路径
    ///   - mmprojPath: 多模态投影模型路径
    ///   - nPredict: 预测长度，默认 100
    ///   - nCtx: 上下文长度，默认 4096（V2.6 / V4.0 用此默认，避免高 KV 内存压力）。
    ///     V4.6 视频路径调用方应显式传入 8192：v46 在 slice=1 下每帧编码
    ///     ~64 visual tokens，64 帧 × 64 = 4096 token 恰好顶死 4096 上下文，
    ///     再叠加 system prompt + chat template wrapper 必然溢出 KV cache。
    ///     v46 模型本身 max_position_embeddings ≥ 32K，远高于 8192；
    ///     KV 多占约 270 MB，iPhone 14 Pro 及以上完全 hold 得住。
    ///   - nThreads: 线程数，默认 4
    ///   - temperature: 温度参数，默认 0.7（对齐模型 generation_config.json：
    ///     do_sample=true, temperature=0.7, top_k=0, top_p=1.0, repetition_penalty=1.0；
    ///     top_k 与 top_p 由 MBMtmd.mm 内部统一设为禁用值，纯温度采样）
    ///   - useGPU: 是否使用 GPU，默认 false
    ///   - mmprojUseGPU: 多模态投影是否使用 GPU，默认 false
    ///   - warmup: 是否预热，默认 true
    ///   - imageMaxSliceNums: 单张图最大切片数，默认 -1（按模型默认）。
    ///     迁移到 master 后此值仅在 init 时生效（见字段注释）。
    public init(
        modelPath: String,
        mmprojPath: String,
        nPredict: Int = 100,
        nCtx: Int = 4096,
        nThreads: Int = 4,
        temperature: Float = 0.7,
        useGPU: Bool = true,
        mmprojUseGPU: Bool = true,
        warmup: Bool = true,
        nUbatch: Int = 0,
        imageMaxSliceNums: Int = -1,
        imageMaxTokens: Int = -1
    ) {
        self.modelPath = modelPath
        self.mmprojPath = mmprojPath
        self.nPredict = nPredict
        self.nCtx = nCtx
        self.nThreads = nThreads
        self.temperature = temperature
        self.useGPU = useGPU
        self.mmprojUseGPU = mmprojUseGPU
        self.warmup = warmup
        self.nUbatch = nUbatch
        self.imageMaxSliceNums = imageMaxSliceNums
        self.imageMaxTokens = imageMaxTokens
    }

    /// 创建默认参数
    /// - Parameters:
    ///   - modelPath: 模型路径
    ///   - mmprojPath: 多模态投影模型路径
    /// - Returns: 默认参数配置
    public static func `default`(modelPath: String, mmprojPath: String) -> MTMDParams {
        return MTMDParams(
            modelPath: modelPath,
            mmprojPath: mmprojPath
        )
    }

    /// 转换为 native bridge 的 C 结构体（不含路径，路径作为 mb_mtmd_init 的独立参数传）。
    internal func toCParams() -> mb_mtmd_params {
        var params = mb_mtmd_params_default()
        params.n_predict        = Int32(nPredict)
        params.n_ctx            = Int32(nCtx)
        params.n_ubatch         = Int32(nUbatch)
        params.n_threads        = Int32(nThreads)
        params.temperature      = temperature
        params.use_gpu          = useGPU
        params.mmproj_use_gpu   = mmprojUseGPU
        params.warmup           = warmup
        // image_max_tokens 与 imageMaxSliceNums 单位**不同**（前者 token、后者 slice），
        // 因此用单独的 imageMaxTokens 字段透传给 bridge：
        //   imageMaxTokens == -1  → 让 minicpmv 用模型默认（V4.6 ~9 slice）
        //   imageMaxTokens == 64  → tier=tiny 走 overview-only，避免多 slice 爆内存
        //   imageMaxTokens == 256 → tier=small 限制 ~2x2 grid + overview
        params.image_max_tokens = Int32(imageMaxTokens)
        return params
    }
}
