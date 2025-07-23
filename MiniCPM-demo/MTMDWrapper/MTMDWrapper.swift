//
//  MTMDWrapper.swift
//  MiniCPM-demo
//
//  Created by AI Assistant on 2024/12/19.
//

import Foundation
import Combine
import llama

// TODO: @sgl 这个就是 LibLlama 的角色

/// MTMD 多模态推理包装器
@MainActor
public class MTMDWrapper: ObservableObject {
    
    // MARK: - Published Properties
    
    /// 当前输出 Token
    @Published public private(set) var currentToken: MTMDToken = .empty
    
    /// 完整的输出内容
    @Published public private(set) var fullOutput: String = ""
    
    /// 生成状态
    @Published public private(set) var generationState: MTMDGenerationState = .idle
    
    /// 初始化状态
    @Published public private(set) var initializationState: MTMDInitializationState = .notInitialized
    
    /// 是否有内容可以生成
    @Published public private(set) var hasContent: Bool = false
    
    // MARK: - Private Properties
    
    /// MTMD 上下文指针
    private var context: OpaquePointer?
    
    /// 生成参数
    private var params: MTMDParams?
    
    /// 生成任务
    private var generationTask: Task<Void, Never>?
    
    /// 生成队列
    private let generationQueue = DispatchQueue(label: "com.mtmd.generation", qos: .userInitiated)
    
    /// 线程锁
    private let lock = NSLock()
    
    // MARK: - Initialization
    
    public init() {
        print("MTMDWrapper: 初始化")
    }
    
    deinit {
        // 在 deinit 中同步清理资源
        generationTask?.cancel()
        generationTask = nil
        
        // 清理资源
        if let ctx = context {
            mtmd_ios_free(ctx)
            context = nil
        }
        
        print("MTMDWrapper: 析构函数清理完成")
    }
    
    // MARK: - Public Methods
    
    /// 初始化 MTMD 上下文
    /// - Parameter params: 初始化参数
    public func initialize(with params: MTMDParams) async throws {
        guard initializationState != .initializing else {
            throw MTMDError.alreadyInitializing
        }
        
        guard initializationState != .initialized else {
            throw MTMDError.alreadyInitialized
        }
        
        updateInitializationState(.initializing)
        
        // 在后台线程执行初始化
        return try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                var cParams = params.toCParams()
                let ctx = mtmd_ios_init(&cParams)
                
                if ctx == nil {
                    continuation.resume(throwing: MTMDError.initializationFailed("无法创建 MTMD 上下文"))
                    return
                }
                
                // 回到主线程更新状态
                Task { @MainActor in
                    self.context = ctx
                    self.params = params
                    self.initializationState = .initialized
                    print("MTMDWrapper: 初始化成功")
                    continuation.resume()
                }
            }
        }
    }

    /// 在后台线程中添加图片（非 @MainActor 版本）
    /// - Parameter imagePath: 图片路径
    public func addImageInBackground(_ imagePath: String) async throws {
        guard initializationState == .initialized else {
            throw MTMDError.contextNotInitialized
        }
        
        guard let ctx = context else {
            throw MTMDError.contextNotInitialized
        }
        
        // 在后台线程执行 C 函数调用
        return try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                let result = mtmd_ios_prefill_image(ctx, std.string(imagePath))
                
                if result != 0 {
                    let errorMessage = mtmd_ios_get_last_error(ctx)
                    let error = errorMessage != nil ? String(cString: errorMessage!) : "Unknown error"
                    continuation.resume(throwing: MTMDError.imageLoadFailed(error))
                } else {
                    // 回到主线程更新状态
                    Task { @MainActor in
                        self.hasContent = true
                        continuation.resume()
                    }
                }
            }
        }
    }

    public func addFrameInBackground(_ imagePath: String) async throws {
        guard initializationState == .initialized else {
            throw MTMDError.contextNotInitialized
        }
        
        guard let ctx = context else {
            throw MTMDError.contextNotInitialized
        }
        
        // 在后台线程执行 C 函数调用
        return try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                let result = mtmd_ios_prefill_frame(ctx, std.string(imagePath))
                
                if result != 0 {
                    let errorMessage = mtmd_ios_get_last_error(ctx)
                    let error = errorMessage != nil ? String(cString: errorMessage!) : "Unknown error"
                    continuation.resume(throwing: MTMDError.imageLoadFailed(error))
                } else {
                    // 回到主线程更新状态
                    Task { @MainActor in
                        self.hasContent = true
                        continuation.resume()
                    }
                }
            }
        }
    }
    
    /// 在后台线程中添加文本（非 @MainActor 版本）
    /// - Parameters:
    ///   - text: 文本内容
    ///   - role: 角色（user/assistant）
    public func addTextInBackground(_ text: String, role: String = "user") async throws {
        guard initializationState == .initialized else {
            throw MTMDError.contextNotInitialized
        }
        
        guard let ctx = context else {
            throw MTMDError.contextNotInitialized
        }
        
        // 在后台线程执行 C 函数调用
        return try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                let result = mtmd_ios_prefill_text(ctx, std.string(text), std.string(role))
                
                if result != 0 {
                    let errorMessage = mtmd_ios_get_last_error(ctx)
                    let error = errorMessage != nil ? String(cString: errorMessage!) : "Unknown error"
                    continuation.resume(throwing: MTMDError.textAddFailed(error))
                } else {
                    // 回到主线程更新状态
                    Task { @MainActor in
                        self.hasContent = true
                        print("MTMDWrapper: 文本添加成功（后台线程）: \(text)")
                        continuation.resume()
                    }
                }
            }
        }
    }
    
    /// 开始生成
    public func startGeneration() async throws {
        guard initializationState == .initialized else {
            throw MTMDError.contextNotInitialized
        }
        
        guard hasContent else {
            throw MTMDError.noContentToGenerate
        }
        
        // 允许在空闲或已完成状态下重新开始生成
        guard generationState == .idle || generationState == .completed else {
            throw MTMDError.generationInProgress
        }
        
        updateGenerationState(.generating)
        
        // 取消之前的生成任务
        generationTask?.cancel()
        
        // 创建新的生成任务
        generationTask = Task {
            await performGeneration()
        }
    }
    
    /// 停止生成
    public func stopGeneration() {
        generationTask?.cancel()
        generationTask = nil
        // 只有在当前状态不是 completed 时才重置为 idle
        if generationState != .completed {
            updateGenerationState(.completed)
        }
        print("MTMDWrapper: 生成已停止")
    }
    
    /// 重置上下文
    public func reset() async {
        stopGeneration()
        
        // 清理资源
        if let ctx = context {
            mtmd_ios_free(ctx)
            context = nil
        }
        
        // 重置状态
        initializationState = .notInitialized
        generationState = .idle
        currentToken = .empty
        fullOutput = ""
        hasContent = false
        params = nil
        
        print("MTMDWrapper: 上下文已重置")
    }
    
    /// 清理资源
    public func cleanup() async {
        await reset()
    }
    
    // MARK: - Private Methods
    
    /// 执行生成
    private func performGeneration() async {
        guard let ctx = context else {
            updateGenerationState(.failed(.contextNotInitialized))
            return
        }
        
        fullOutput = ""
        
        // 生成循环
        while !Task.isCancelled {
            
            // 在后台线程执行 C 函数调用
            let cToken = await withCheckedContinuation { continuation in
                DispatchQueue.global(qos: .userInitiated).async {
                    let token = mtmd_ios_loop(ctx)
                    continuation.resume(returning: token)
                }
            }
            
            let tokenString = cToken.token != nil ? String(cString: cToken.token) : ""
            
            // 在主线程更新状态
            currentToken = MTMDToken(content: tokenString, isEnd: cToken.is_end)
            fullOutput += tokenString
            
            // 释放 C 字符串
            if cToken.token != nil {
                // mtmd_ios_string_free(cToken.token)
            }
            
            // 检查是否生成完成
            if cToken.is_end {
                updateGenerationState(.completed)
                print("MTMDWrapper: 生成完成: \(fullOutput)")
                // 清理任务引用但不重置状态，让状态保持为 completed
                generationTask = nil
                mtmd_ios_clean_kv_cache(ctx)
                return
            }
            
            // 避免过度占用 CPU
            try? await Task.sleep(nanoseconds: 10_000_000) // 10ms
        }
    }
    
    /// 更新初始化状态
    private func updateInitializationState(_ state: MTMDInitializationState) {
        initializationState = state
    }
    
    /// 更新生成状态
    private func updateGenerationState(_ state: MTMDGenerationState) {
        generationState = state
    }
}

