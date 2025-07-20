//
//  MBSettingsModelDownloadManager.swift
//  MiniCPM-demo
//
//  Created by Alex on 12/7/2025.
//

import Foundation
import UIKit
import ZipArchive

/// 下载状态枚举
enum DownloadStatus {
    case notStarted
    case downloading
    case paused
    case completed
    case failed
}

/// 下载进度信息结构
struct DownloadProgressInfo {
    let modelName: String
    let status: DownloadStatus
    let progress: CGFloat
    let downloadedBytes: Int64
    let totalBytes: Int64
    let speed: Double // bytes per second
    let estimatedTimeRemaining: TimeInterval
}

/// 4V3B 模型下载管理器单例
class MB4V3BModelDownloadManager: NSObject {
    
    // MARK: - 单例实现
    
    static let shared = MB4V3BModelDownloadManager()
    
    private override init() {
        super.init()
    }
    
    // MARK: - 属性
    
    /// 外部传入的 MTMDWrapperExample 引用
    private var mtmdWrapperExample: MTMDWrapperExample?
    
    /// 4V3B 主模型下载管理器
    private var model4v3b_Q4_K_M_Manager: MBModelDownloadHelperV2?
    
    /// 4V3B mmproj VIT 模型下载管理器
    private var mmproj4v3b_Manager: MBModelDownloadHelperV2?
    
    /// 4V3B ANE 模块下载管理器
    private var mlmodelc4v3b_Manager: MBModelDownloadHelperV2?
    
    /// 下载进度回调
    var progressHandler: ((String, CGFloat) -> Void)?
    
    /// 下载完成回调
    var completionHandler: ((String, Bool) -> Void)?
    
    /// 详细进度信息回调
    var detailedProgressHandler: ((DownloadProgressInfo) -> Void)?
    
    // MARK: - 防重复调用机制
    
    /// 下载状态跟踪
    private var downloadStates: [String: DownloadStatus] = [:]
    
    /// 下载进度缓存
    private var downloadProgressCache: [String: DownloadProgressInfo] = [:]
    
    /// 下载开始时间记录
    private var downloadStartTimes: [String: Date] = [:]
    
    /// 上次下载字节数记录（用于计算速度）
    private var lastDownloadedBytes: [String: Int64] = [:]
    
    /// 防重复调用锁
    private let downloadQueue = DispatchQueue(label: "com.minicpm.4v3b.download", qos: .userInitiated)
    
    /// 检查是否正在下载指定模型
    private func isDownloading(_ modelKey: String) -> Bool {
        return downloadQueue.sync {
            return downloadStates[modelKey] == .downloading
        }
    }
    
    /// 设置下载状态
    private func setDownloadStatus(_ status: DownloadStatus, for modelKey: String) {
        downloadQueue.sync {
            downloadStates[modelKey] = status
            if status == .downloading {
                downloadStartTimes[modelKey] = Date()
            }
        }
    }
    
    /// 更新下载进度
    private func updateDownloadProgress(_ progress: CGFloat, for modelKey: String, modelName: String, downloadedBytes: Int64 = 0, totalBytes: Int64 = 0) {
        downloadQueue.sync {
            let currentTime = Date()
            let startTime = downloadStartTimes[modelKey] ?? currentTime
            let timeElapsed = currentTime.timeIntervalSince(startTime)
            
            // 计算下载速度
            var speed: Double = 0
            if let lastBytes = lastDownloadedBytes[modelKey], timeElapsed > 0 {
                speed = Double(downloadedBytes - lastBytes) / timeElapsed
            }
            lastDownloadedBytes[modelKey] = downloadedBytes
            
            // 计算剩余时间
            var estimatedTimeRemaining: TimeInterval = 0
            if speed > 0 && totalBytes > downloadedBytes {
                estimatedTimeRemaining = Double(totalBytes - downloadedBytes) / speed
            }
            
            let progressInfo = DownloadProgressInfo(
                modelName: modelName,
                status: downloadStates[modelKey] ?? .notStarted,
                progress: progress,
                downloadedBytes: downloadedBytes,
                totalBytes: totalBytes,
                speed: speed,
                estimatedTimeRemaining: estimatedTimeRemaining
            )
            
            downloadProgressCache[modelKey] = progressInfo
            detailedProgressHandler?(progressInfo)
        }
    }
    
    // MARK: - 公共方法
    
    /// 初始化下载管理器
    /// - Parameter llamaState: llama状态管理器
    func setupDownloadManager(with wrapper: MTMDWrapperExample) {
        self.mtmdWrapperExample = wrapper
        setupModels()
    }
    
    /// 配置所有4V3B模型
    private func setupModels() {
        guard let mtmdWrapperExample = mtmdWrapperExample else { return }
        
        // 4V3B 主模型
        let model4v3b_Q4_K_M_URLString = MiniCPMModelConst.model4v3b_Q4_K_M_URLString
        model4v3b_Q4_K_M_Manager = MBModelDownloadHelperV2(
            wrapper: mtmdWrapperExample,
            modelName: MiniCPMModelConst.model4v3b_Q4_K_M_FileName,
            modelUrl: model4v3b_Q4_K_M_URLString,
            filename: MiniCPMModelConst.model4v3b_Q4_K_M_FileName
        )
        
        // 4V3B mmproj VIT 模型
        let mmproj4v3b_URLString = MiniCPMModelConst.mmproj4v3b_URLString
        mmproj4v3b_Manager = MBModelDownloadHelperV2(
            wrapper: mtmdWrapperExample,
            modelName: MiniCPMModelConst.mmproj4v3b_FileName,
            modelUrl: mmproj4v3b_URLString,
            filename: MiniCPMModelConst.mmproj4v3b_FileName
        )
        
        // 4V3B ANE 模块
        let mlmodelc4v3b_URLString = MiniCPMModelConst.mlmodelc4v3b_ZipFileURLString
        mlmodelc4v3b_Manager = MBModelDownloadHelperV2(
            wrapper: mtmdWrapperExample,
            modelName: MiniCPMModelConst.mlmodelc4v3b_ZipFileName,
            modelUrl: mlmodelc4v3b_URLString,
            filename: MiniCPMModelConst.mlmodelc4v3b_ZipFileName
        )
        
        // 恢复断点续传
        restoreDownloadProgress()
    }
    
    /// 恢复下载进度
    private func restoreDownloadProgress() {
        guard let info = FDownLoaderManager.shareInstance().downLoaderInfo else { return }
        
        // 恢复 4V3B 主模型下载进度
        let model4v3b_Q4_K_M_FileName = String(stringLiteral: MiniCPMModelConst.model4v3b_Q4_K_M_URLString).md5() ?? ""
        if let obj = info[model4v3b_Q4_K_M_FileName] as? FDownLoader {
            if obj.state == .downLoading {
                downloadModel4v3b_Q4_K_M()
            }
        }
        
        // 恢复 4V3B mmproj 模型下载进度
        let mmproj4v3b_FileName = String(stringLiteral: MiniCPMModelConst.mmproj4v3b_URLString).md5() ?? ""
        if let obj = info[mmproj4v3b_FileName] as? FDownLoader {
            if obj.state == .downLoading {
                downloadMMProj4v3b()
            }
        }
        
        // 恢复 4V3B ANE 模块下载进度
        let mlmodelc4v3b_FileName = String(stringLiteral: MiniCPMModelConst.mlmodelc4v3b_ZipFileURLString).md5() ?? ""
        if let obj = info[mlmodelc4v3b_FileName] as? FDownLoader {
            if obj.state == .downLoading {
                downloadMLModelc4v3b()
            }
        }
    }
    
    // MARK: - 下载方法（带防重复调用）
    
    /// 下载 4V3B 主模型
    func downloadModel4v3b_Q4_K_M() {
        let modelKey = "4v3b_main_model"
        
        // 防重复调用检查
        guard !isDownloading(modelKey) else {
            debugLog("-->> 4V3B主模型正在下载中，忽略重复调用")
            return
        }
        
        // 检查是否已下载
        if getModel4v3b_Q4_K_M_Status() == "downloaded" {
            debugLog("-->> 4V3B主模型已下载完成")
            return
        }
        
        setDownloadStatus(.downloading, for: modelKey)
        
        model4v3b_Q4_K_M_Manager?.downloadV2(completionBlock: { [weak self] status, progress in
            guard let self = self else { return }
            
            DispatchQueue.main.async {
                if progress >= 1 {
                    // 下载完成，进行MD5校验
                    self.setDownloadStatus(.completed, for: modelKey)
                    self.verifyModel4v3b_Q4_K_M_MD5()
                    self.progressHandler?(MiniCPMModelConst.model4v3b_Q4_K_M_DisplayedName, 1.0)
                    self.completionHandler?(MiniCPMModelConst.model4v3b_Q4_K_M_DisplayedName, true)
                    self.updateDownloadProgress(1.0, for: modelKey, modelName: MiniCPMModelConst.model4v3b_Q4_K_M_DisplayedName)
                } else {
                    if status == "failed" {
                        self.setDownloadStatus(.failed, for: modelKey)
                        self.progressHandler?(MiniCPMModelConst.model4v3b_Q4_K_M_DisplayedName + "下载失败", -1)
                        self.completionHandler?(MiniCPMModelConst.model4v3b_Q4_K_M_DisplayedName, false)
                    } else {
                        self.progressHandler?(MiniCPMModelConst.model4v3b_Q4_K_M_DisplayedName, progress)
                        self.updateDownloadProgress(progress, for: modelKey, modelName: MiniCPMModelConst.model4v3b_Q4_K_M_DisplayedName)
                    }
                }
            }
        })
    }
    
    /// 下载 4V3B mmproj VIT 模型
    func downloadMMProj4v3b() {
        let modelKey = "4v3b_mmproj_model"
        
        // 防重复调用检查
        guard !isDownloading(modelKey) else {
            debugLog("-->> 4V3B VIT模型正在下载中，忽略重复调用")
            return
        }
        
        // 检查是否已下载
        if getMMProj4v3b_Status() == "downloaded" {
            debugLog("-->> 4V3B VIT模型已下载完成")
            return
        }
        
        setDownloadStatus(.downloading, for: modelKey)
        
        mmproj4v3b_Manager?.downloadV2(completionBlock: { [weak self] status, progress in
            guard let self = self else { return }
            
            DispatchQueue.main.async {
                if progress >= 1 {
                    // 下载完成，进行MD5校验
                    self.setDownloadStatus(.completed, for: modelKey)
                    self.verifyMMProj4v3b_MD5()
                    self.progressHandler?(MiniCPMModelConst.modelMMProj4v3b_DisplayedName, 1.0)
                    self.completionHandler?(MiniCPMModelConst.modelMMProj4v3b_DisplayedName, true)
                    self.updateDownloadProgress(1.0, for: modelKey, modelName: MiniCPMModelConst.modelMMProj4v3b_DisplayedName)
                } else {
                    if status == "failed" {
                        self.setDownloadStatus(.failed, for: modelKey)
                        self.progressHandler?(MiniCPMModelConst.modelMMProj4v3b_DisplayedName + "下载失败", -1)
                        self.completionHandler?(MiniCPMModelConst.modelMMProj4v3b_DisplayedName, false)
                    } else {
                        self.progressHandler?(MiniCPMModelConst.modelMMProj4v3b_DisplayedName, progress)
                        self.updateDownloadProgress(progress, for: modelKey, modelName: MiniCPMModelConst.modelMMProj4v3b_DisplayedName)
                    }
                }
            }
        })
    }
    
    /// 下载 4V3B ANE 模块
    func downloadMLModelc4v3b() {
        let modelKey = "4v3b_ane_module"
        
        // 防重复调用检查
        guard !isDownloading(modelKey) else {
            debugLog("-->> 4V3B ANE模块正在下载中，忽略重复调用")
            return
        }
        
        // 检查是否已下载
        if getMLModelc4v3b_Status() == "downloaded" {
            debugLog("-->> 4V3B ANE模块已下载完成")
            return
        }
        
        setDownloadStatus(.downloading, for: modelKey)
        
        mlmodelc4v3b_Manager?.downloadV2(completionBlock: { [weak self] status, progress in
            guard let self = self else { return }
            
            DispatchQueue.main.async {
                if progress >= 1 {
                    // 下载完成，进行MD5校验和解压缩
                    self.setDownloadStatus(.completed, for: modelKey)
                    self.verifyAndExtractMLModelc4v3b()
                    self.progressHandler?(MiniCPMModelConst.mlmodelc4v3b_ZipFileDisplayedName, 1.0)
                    self.completionHandler?(MiniCPMModelConst.mlmodelc4v3b_ZipFileDisplayedName, true)
                    self.updateDownloadProgress(1.0, for: modelKey, modelName: MiniCPMModelConst.mlmodelc4v3b_ZipFileDisplayedName)
                } else {
                    if status == "failed" {
                        self.setDownloadStatus(.failed, for: modelKey)
                        self.progressHandler?(MiniCPMModelConst.mlmodelc4v3b_ZipFileDisplayedName + "下载失败", -1)
                        self.completionHandler?(MiniCPMModelConst.mlmodelc4v3b_ZipFileDisplayedName, false)
                    } else {
                        self.progressHandler?(MiniCPMModelConst.mlmodelc4v3b_ZipFileDisplayedName, progress)
                        self.updateDownloadProgress(progress, for: modelKey, modelName: MiniCPMModelConst.mlmodelc4v3b_ZipFileDisplayedName)
                    }
                }
            }
        })
    }
    
    // MARK: - 下载进度查看方法
    
    /// 获取指定模型的下载进度信息
    /// - Parameter modelKey: 模型标识符
    /// - Returns: 下载进度信息
    func getDownloadProgress(for modelKey: String) -> DownloadProgressInfo? {
        return downloadQueue.sync {
            return downloadProgressCache[modelKey]
        }
    }
    
    /// 获取所有模型的下载进度信息
    /// - Returns: 所有模型的下载进度信息字典
    func getAllDownloadProgress() -> [String: DownloadProgressInfo] {
        return downloadQueue.sync {
            return downloadProgressCache
        }
    }
    
    /// 获取指定模型的下载状态
    /// - Parameter modelKey: 模型标识符
    /// - Returns: 下载状态
    func getDownloadStatus(for modelKey: String) -> DownloadStatus {
        return downloadQueue.sync {
            return downloadStates[modelKey] ?? .notStarted
        }
    }
    
    /// 获取所有模型的下载状态
    /// - Returns: 所有模型的下载状态字典
    func getAllDownloadStatus() -> [String: DownloadStatus] {
        return downloadQueue.sync {
            return downloadStates
        }
    }
    
    /// 格式化下载速度显示
    /// - Parameter bytesPerSecond: 每秒字节数
    /// - Returns: 格式化的速度字符串
    func formatDownloadSpeed(_ bytesPerSecond: Double) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useKB, .useMB, .useGB]
        formatter.countStyle = .binary
        return formatter.string(fromByteCount: Int64(bytesPerSecond)) + "/s"
    }
    
    /// 格式化剩余时间显示
    /// - Parameter timeInterval: 时间间隔（秒）
    /// - Returns: 格式化的时间字符串
    func formatTimeRemaining(_ timeInterval: TimeInterval) -> String {
        if timeInterval.isInfinite || timeInterval.isNaN || timeInterval <= 0 {
            return "计算中..."
        }
        
        let hours = Int(timeInterval) / 3600
        let minutes = Int(timeInterval) % 3600 / 60
        let seconds = Int(timeInterval) % 60
        
        if hours > 0 {
            return String(format: "%d小时%d分钟", hours, minutes)
        } else if minutes > 0 {
            return String(format: "%d分钟%d秒", minutes, seconds)
        } else {
            return String(format: "%d秒", seconds)
        }
    }
    
    /// 获取文件大小显示
    /// - Parameter bytes: 字节数
    /// - Returns: 格式化的文件大小字符串
    func formatFileSize(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useKB, .useMB, .useGB]
        formatter.countStyle = .binary
        return formatter.string(fromByteCount: bytes)
    }
    
    // MARK: - MD5 校验方法
    
    /// 校验 4V3B 主模型 MD5
    private func verifyModel4v3b_Q4_K_M_MD5() {
        let fileURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(MiniCPMModelConst.model4v3b_Q4_K_M_FileName)
        
        if FileManager.default.fileExists(atPath: fileURL.path) {
            if let checksum = MBUtils.md5(for: fileURL) {
                debugLog("-->> 4V3B主模型 实际MD5值: \(checksum)")
                debugLog("-->> 4V3B主模型 期望MD5值: \(MiniCPMModelConst.model4v3b_Q4_K_M_MD5)")
                
                if checksum == MiniCPMModelConst.model4v3b_Q4_K_M_MD5 {
                    debugLog("-->> 4V3B主模型 MD5校验成功: \(checksum)")
                    model4v3b_Q4_K_M_Manager?.status = "downloaded"
                } else {
                    debugLog("-->> 4V3B主模型 MD5校验失败")
                    model4v3b_Q4_K_M_Manager?.status = "download"
                    deleteModel4v3b_Q4_K_M()
                }
            } else {
                debugLog("-->> 4V3B主模型 MD5计算失败")
                model4v3b_Q4_K_M_Manager?.status = "download"
                deleteModel4v3b_Q4_K_M()
            }
        }
    }
    
    /// 校验 4V3B mmproj 模型 MD5
    private func verifyMMProj4v3b_MD5() {
        let fileURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(MiniCPMModelConst.mmproj4v3b_FileName)
        
        if FileManager.default.fileExists(atPath: fileURL.path) {
            if let checksum = MBUtils.md5(for: fileURL) {
                debugLog("-->> 4V3B VIT模型 实际MD5值: \(checksum)")
                debugLog("-->> 4V3B VIT模型 期望MD5值: \(MiniCPMModelConst.modelMMProj4v3b_MD5)")
                
                if checksum == MiniCPMModelConst.modelMMProj4v3b_MD5 {
                    debugLog("-->> 4V3B VIT模型 MD5校验成功: \(checksum)")
                    mmproj4v3b_Manager?.status = "downloaded"
                } else {
                    debugLog("-->> 4V3B VIT模型 MD5校验失败")
                    mmproj4v3b_Manager?.status = "download"
                    deleteMMProj4v3b()
                }
            } else {
                debugLog("-->> 4V3B VIT模型 MD5计算失败")
                mmproj4v3b_Manager?.status = "download"
                deleteMMProj4v3b()
            }
        }
    }
    
    /// 校验并解压 4V3B ANE 模块
    private func verifyAndExtractMLModelc4v3b() {
        let fileURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(MiniCPMModelConst.mlmodelc4v3b_ZipFileName)
        
        if FileManager.default.fileExists(atPath: fileURL.path) {
            if let checksum = MBUtils.md5(for: fileURL) {
                debugLog("-->> 4V3B ANE模块 实际MD5值: \(checksum)")
                debugLog("-->> 4V3B ANE模块 期望MD5值: \(MiniCPMModelConst.mlmodelc4v3b_ZipFileMD5)")
                
                if checksum == MiniCPMModelConst.mlmodelc4v3b_ZipFileMD5 {
                    debugLog("-->> 4V3B ANE模块 MD5校验成功: \(checksum)")
                    
                    // 解压缩
                    let destPath = getDocumentsDirectory().path
                    if !destPath.isEmpty {
                        var error: NSError?
                        SSZipArchive.unzipFile(
                            atPath: fileURL.path,
                            toDestination: destPath,
                            preserveAttributes: true,
                            overwrite: true,
                            password: nil,
                            error: &error,
                            delegate: nil
                        )
                        
                        if let error = error {
                            debugLog("-->> 4V3B ANE模块解压失败: \(error.localizedDescription)")
                            mlmodelc4v3b_Manager?.status = "download"
                            deleteMLModelc4v3b()
                        } else {
                            debugLog("-->> 4V3B ANE模块解压成功")
                            mlmodelc4v3b_Manager?.status = "downloaded"
                        }
                    }
                } else {
                    debugLog("-->> 4V3B ANE模块 MD5校验失败")
                    mlmodelc4v3b_Manager?.status = "download"
                    deleteMLModelc4v3b()
                }
            } else {
                debugLog("-->> 4V3B ANE模块 MD5计算失败")
                mlmodelc4v3b_Manager?.status = "download"
                deleteMLModelc4v3b()
            }
        }
    }
    
    // MARK: - 删除方法
    
    /// 删除 4V3B 主模型
    func deleteModel4v3b_Q4_K_M() {
        let fileURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(MiniCPMModelConst.model4v3b_Q4_K_M_FileName)
        
        do {
            try FileManager.default.removeItem(at: fileURL)
            model4v3b_Q4_K_M_Manager?.status = "download"
            setDownloadStatus(.notStarted, for: "4v3b_main_model")
            debugLog("-->> 4V3B主模型删除成功")
        } catch {
            debugLog("-->> 4V3B主模型删除失败: \(error.localizedDescription)")
        }
    }
    
    /// 删除 4V3B mmproj 模型
    func deleteMMProj4v3b() {
        let fileURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(MiniCPMModelConst.mmproj4v3b_FileName)
        
        do {
            try FileManager.default.removeItem(at: fileURL)
            mmproj4v3b_Manager?.status = "download"
            setDownloadStatus(.notStarted, for: "4v3b_mmproj_model")
            debugLog("-->> 4V3B VIT模型删除成功")
        } catch {
            debugLog("-->> 4V3B VIT模型删除失败: \(error.localizedDescription)")
        }
    }
    
    /// 删除 4V3B ANE 模块
    func deleteMLModelc4v3b() {
        let fileURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(MiniCPMModelConst.mlmodelc4v3b_ZipFileName)
        
        do {
            try FileManager.default.removeItem(at: fileURL)
            mlmodelc4v3b_Manager?.status = "download"
            setDownloadStatus(.notStarted, for: "4v3b_ane_module")
            debugLog("-->> 4V3B ANE模块删除成功")
        } catch {
            debugLog("-->> 4V3B ANE模块删除失败: \(error.localizedDescription)")
        }
    }
    
    // MARK: - 状态查询方法
    
    /// 获取 4V3B 主模型状态
    func getModel4v3b_Q4_K_M_Status() -> String {
        return model4v3b_Q4_K_M_Manager?.status ?? "download"
    }
    
    /// 获取 4V3B mmproj 模型状态
    func getMMProj4v3b_Status() -> String {
        return mmproj4v3b_Manager?.status ?? "download"
    }
    
    /// 获取 4V3B ANE 模块状态
    func getMLModelc4v3b_Status() -> String {
        return mlmodelc4v3b_Manager?.status ?? "download"
    }
    
    /// 检查是否有正在进行的下载任务
    func hasActiveDownloads() -> Bool {
        guard let info = FDownLoaderManager.shareInstance().downLoaderInfo else { return false }
        return !info.allKeys.isEmpty
    }
    
    /// 检查是否有任何模型正在下载
    func hasAnyModelDownloading() -> Bool {
        return downloadQueue.sync {
            return downloadStates.values.contains(.downloading)
        }
    }
    
    // MARK: - 工具方法
    
    /// 获取 Documents 目录
    private func getDocumentsDirectory() -> URL {
        return FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }
    
    /// 暂停所有下载
    func pauseAllDownloads() {
        FDownLoaderManager.shareInstance().pauseAll()
        downloadQueue.sync {
            for key in downloadStates.keys {
                if downloadStates[key] == .downloading {
                    downloadStates[key] = .paused
                }
            }
        }
    }
    
    /// 恢复所有下载
    func resumeAllDownloads() {
        FDownLoaderManager.shareInstance().resumeAll()
        downloadQueue.sync {
            for key in downloadStates.keys {
                if downloadStates[key] == .paused {
                    downloadStates[key] = .downloading
                }
            }
        }
    }
    
    /// 取消所有下载
    func cancelAllDownloads() {
        FDownLoaderManager.shareInstance().downLoaderInfo.removeAllObjects()
        downloadQueue.sync {
            downloadStates.removeAll()
            downloadProgressCache.removeAll()
            downloadStartTimes.removeAll()
            lastDownloadedBytes.removeAll()
        }
    }
    
    /// 重置下载状态
    func resetDownloadStates() {
        downloadQueue.sync {
            downloadStates.removeAll()
            downloadProgressCache.removeAll()
            downloadStartTimes.removeAll()
            lastDownloadedBytes.removeAll()
        }
    }
    
    // MARK: - 公共访问方法
    
    /// 获取主模型下载管理器（用于重置状态）
    var mainModelManager: MBModelDownloadHelperV2? {
        return model4v3b_Q4_K_M_Manager
    }
    
    /// 获取VIT模型下载管理器（用于重置状态）
    var vitModelManager: MBModelDownloadHelperV2? {
        return mmproj4v3b_Manager
    }
    
    /// 获取ANE模块下载管理器（用于重置状态）
    var aneModelManager: MBModelDownloadHelperV2? {
        return mlmodelc4v3b_Manager
    }
}
