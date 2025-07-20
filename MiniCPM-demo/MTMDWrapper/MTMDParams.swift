//
//  MTMDParams.swift
//  MiniCPM-demo
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
    
    /// 初始化方法
    /// - Parameters:
    ///   - modelPath: 模型路径
    ///   - mmprojPath: 多模态投影模型路径
    ///   - nPredict: 预测长度，默认 100
    ///   - nCtx: 上下文长度，默认 4096
    ///   - nThreads: 线程数，默认 4
    ///   - temperature: 温度参数，默认 0.7
    ///   - useGPU: 是否使用 GPU，默认 false
    ///   - mmprojUseGPU: 多模态投影是否使用 GPU，默认 false
    ///   - warmup: 是否预热，默认 true
    public init(
        modelPath: String,
        mmprojPath: String,
        nPredict: Int = 100,
        nCtx: Int = 4096,
        nThreads: Int = 4,
        temperature: Float = 0.7,
        useGPU: Bool = true,
        mmprojUseGPU: Bool = true,
        warmup: Bool = true
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
    
    /// 转换为 C 结构体
    internal func toCParams() -> mtmd_ios_params {
        var params = mtmd_ios_params_default()
        params.model_path = std.string(modelPath)
        params.mmproj_path = std.string(mmprojPath)
        params.n_predict = Int32(nPredict)
        params.n_ctx = Int32(nCtx)
        params.n_threads = Int32(nThreads)
        params.temperature = temperature
        params.use_gpu = useGPU
        params.mmproj_use_gpu = mmprojUseGPU
        params.warmup = warmup
        return params
    }
} 
