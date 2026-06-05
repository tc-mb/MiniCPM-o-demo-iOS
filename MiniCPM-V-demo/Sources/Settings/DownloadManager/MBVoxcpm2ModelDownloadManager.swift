//
//  MBVoxcpm2ModelDownloadManager.swift
//  MiniCPM-V-demo
//
//  VoxCPM2 下载管理器（两段下载：BaseLM + Acoustic）
//

import Foundation
import UIKit

/// VoxCPM2 模型下载管理器单例
class MBVoxcpm2ModelDownloadManager: NSObject {

    // MARK: - 单例实现

    static let shared = MBVoxcpm2ModelDownloadManager()

    private override init() {
        super.init()
    }

    // MARK: - 属性

    private var mtmdWrapperExample: MTMDWrapperExample?

    var baseLMManager: MBModelDownloadHelperV2?
    var acousticManager: MBModelDownloadHelperV2?

    var progressHandler: ((String, CGFloat) -> Void)?
    var completionHandler: ((String, Bool) -> Void)?
    var detailedProgressHandler: ((DownloadProgressInfo) -> Void)?

    // MARK: - 防重复调用机制

    private var downloadStates: [String: DownloadStatus] = [:]
    private var downloadProgressCache: [String: DownloadProgressInfo] = [:]
    private var downloadStartTimes: [String: Date] = [:]
    private var lastDownloadedBytes: [String: Int64] = [:]
    private let downloadQueue = DispatchQueue(label: "com.minicpm.voxcpm2.download", qos: .userInitiated)

    private func isDownloading(_ modelKey: String) -> Bool {
        return downloadQueue.sync {
            return downloadStates[modelKey] == .downloading
        }
    }

    private func setDownloadStatus(_ status: DownloadStatus, for modelKey: String) {
        downloadQueue.sync {
            downloadStates[modelKey] = status
            if status == .downloading {
                downloadStartTimes[modelKey] = Date()
            }
        }
    }

    private func updateDownloadProgress(_ progress: CGFloat, for modelKey: String, modelName: String, downloadedBytes: Int64 = 0, totalBytes: Int64 = 0) {
        downloadQueue.async { [weak self] in
            guard let self = self else { return }
            let currentTime = Date()
            let startTime = self.downloadStartTimes[modelKey] ?? currentTime
            let timeElapsed = currentTime.timeIntervalSince(startTime)

            var speed: Double = 0
            if let lastBytes = self.lastDownloadedBytes[modelKey], timeElapsed > 0 {
                speed = Double(downloadedBytes - lastBytes) / timeElapsed
            }
            self.lastDownloadedBytes[modelKey] = downloadedBytes

            var estimatedTimeRemaining: TimeInterval = 0
            if speed > 0 && totalBytes > downloadedBytes {
                estimatedTimeRemaining = Double(totalBytes - downloadedBytes) / speed
            }

            let progressInfo = DownloadProgressInfo(
                modelName: modelName,
                status: self.downloadStates[modelKey] ?? .notStarted,
                progress: progress,
                downloadedBytes: downloadedBytes,
                totalBytes: totalBytes,
                speed: speed,
                estimatedTimeRemaining: estimatedTimeRemaining
            )

            self.downloadProgressCache[modelKey] = progressInfo
            DispatchQueue.main.async {
                self.detailedProgressHandler?(progressInfo)
            }
        }
    }

    // MARK: - 公共方法

    func setupDownloadManager(with wrapper: MTMDWrapperExample) {
        self.mtmdWrapperExample = wrapper
        setupModels()
    }

    private func setupModels() {
        guard mtmdWrapperExample != nil else { return }

        baseLMManager = MBModelDownloadHelperV2(
            wrapper: mtmdWrapperExample!,
            modelName: MiniCPMModelConst.voxcpm2_BaseLMFileName,
            modelUrl: MiniCPMModelConst.voxcpm2_BaseLMURLString,
            filename: MiniCPMModelConst.voxcpm2_BaseLMFileName,
            backupModelUrl: MiniCPMModelConst.voxcpm2_BaseLMBackupURLString
        )

        acousticManager = MBModelDownloadHelperV2(
            wrapper: mtmdWrapperExample!,
            modelName: MiniCPMModelConst.voxcpm2_AcousticFileName,
            modelUrl: MiniCPMModelConst.voxcpm2_AcousticURLString,
            filename: MiniCPMModelConst.voxcpm2_AcousticFileName,
            backupModelUrl: MiniCPMModelConst.voxcpm2_AcousticBackupURLString
        )

        reconcileStatusFromDisk()
        restoreDownloadProgress()
    }

    func reconcileStatusFromDisk() {
        let docs = getDocumentsDirectory()
        let fm = FileManager.default

        let baseLMPath = docs.appendingPathComponent(MiniCPMModelConst.voxcpm2_BaseLMFileName).path
        baseLMManager?.status = fm.fileExists(atPath: baseLMPath) ? "downloaded" : "download"

        let acousticPath = docs.appendingPathComponent(MiniCPMModelConst.voxcpm2_AcousticFileName).path
        acousticManager?.status = fm.fileExists(atPath: acousticPath) ? "downloaded" : "download"
    }

    private func restoreDownloadProgress() {
        guard let info = FDownLoaderManager.shareInstance().downLoaderInfo else { return }

        let baseLMKey = String(stringLiteral: MiniCPMModelConst.voxcpm2_BaseLMURLString).md5() ?? ""
        if let obj = info[baseLMKey] as? FDownLoader, obj.state == .downLoading {
            downloadBaseLM()
        }

        let acousticKey = String(stringLiteral: MiniCPMModelConst.voxcpm2_AcousticURLString).md5() ?? ""
        if let obj = info[acousticKey] as? FDownLoader, obj.state == .downLoading {
            downloadAcoustic()
        }
    }

    // MARK: - 状态查询

    func getBaseLMStatus() -> String {
        return baseLMManager?.status ?? "download"
    }

    func getAcousticStatus() -> String {
        return acousticManager?.status ?? "download"
    }

    func hasAnyDownloadActive() -> Bool {
        return isDownloading("voxcpm2_baselm") || isDownloading("voxcpm2_acoustic")
    }

    // MARK: - 下载方法

    func downloadBaseLM(onComplete: (() -> Void)? = nil) {
        let modelKey = "voxcpm2_baselm"

        guard !isDownloading(modelKey) else {
            debugLog("-->> VoxCPM2 BaseLM 正在下载中，忽略重复调用")
            return
        }
        if getBaseLMStatus() == "downloaded" {
            debugLog("-->> VoxCPM2 BaseLM 已下载完成")
            onComplete?()
            return
        }

        setDownloadStatus(.downloading, for: modelKey)
        debugLog("-->> VoxCPM2 BaseLM 开始下载")
        var lastLoggedPct = -1

        baseLMManager?.downloadV2(completionBlock: { [weak self] status, progress in
            guard let self = self else { return }

            DispatchQueue.main.async {
                if progress >= 1 {
                    debugLog("-->> VoxCPM2 BaseLM 下载完成")
                    self.setDownloadStatus(.completed, for: modelKey)
                    self.progressHandler?(MiniCPMModelConst.voxcpm2_BaseLMDisplayedName, 1.0)
                    self.completionHandler?(MiniCPMModelConst.voxcpm2_BaseLMDisplayedName, true)
                    self.updateDownloadProgress(1.0, for: modelKey, modelName: MiniCPMModelConst.voxcpm2_BaseLMDisplayedName)
                    onComplete?()
                } else {
                    if status == "failed" {
                        debugLog("-->> VoxCPM2 BaseLM 下载失败")
                        self.setDownloadStatus(.failed, for: modelKey)
                        self.progressHandler?(MiniCPMModelConst.voxcpm2_BaseLMDisplayedName + L.Download.progressFailedSuffix.loc, -1)
                        self.completionHandler?(MiniCPMModelConst.voxcpm2_BaseLMDisplayedName, false)
                    } else {
                        let pct = Int(progress * 100)
                        if pct != lastLoggedPct && pct % 5 == 0 {
                            debugLog("-->> VoxCPM2 BaseLM 下载进度: \(pct)%")
                            lastLoggedPct = pct
                        }
                        self.progressHandler?(MiniCPMModelConst.voxcpm2_BaseLMDisplayedName, progress)
                        self.updateDownloadProgress(progress, for: modelKey, modelName: MiniCPMModelConst.voxcpm2_BaseLMDisplayedName)
                    }
                }
            }
        })
    }

    func downloadAcoustic(onComplete: (() -> Void)? = nil) {
        let modelKey = "voxcpm2_acoustic"

        guard !isDownloading(modelKey) else {
            debugLog("-->> VoxCPM2 Acoustic 正在下载中，忽略重复调用")
            return
        }
        if getAcousticStatus() == "downloaded" {
            debugLog("-->> VoxCPM2 Acoustic 已下载完成")
            onComplete?()
            return
        }

        setDownloadStatus(.downloading, for: modelKey)
        debugLog("-->> VoxCPM2 Acoustic 开始下载")
        var lastLoggedPct = -1

        acousticManager?.downloadV2(completionBlock: { [weak self] status, progress in
            guard let self = self else { return }

            DispatchQueue.main.async {
                if progress >= 1 {
                    debugLog("-->> VoxCPM2 Acoustic 下载完成")
                    self.setDownloadStatus(.completed, for: modelKey)
                    self.progressHandler?(MiniCPMModelConst.voxcpm2_AcousticDisplayedName, 1.0)
                    self.completionHandler?(MiniCPMModelConst.voxcpm2_AcousticDisplayedName, true)
                    self.updateDownloadProgress(1.0, for: modelKey, modelName: MiniCPMModelConst.voxcpm2_AcousticDisplayedName)
                    onComplete?()
                } else {
                    if status == "failed" {
                        debugLog("-->> VoxCPM2 Acoustic 下载失败")
                        self.setDownloadStatus(.failed, for: modelKey)
                        self.progressHandler?(MiniCPMModelConst.voxcpm2_AcousticDisplayedName + L.Download.progressFailedSuffix.loc, -1)
                        self.completionHandler?(MiniCPMModelConst.voxcpm2_AcousticDisplayedName, false)
                    } else {
                        let pct = Int(progress * 100)
                        if pct != lastLoggedPct && pct % 5 == 0 {
                            debugLog("-->> VoxCPM2 Acoustic 下载进度: \(pct)%")
                            lastLoggedPct = pct
                        }
                        self.progressHandler?(MiniCPMModelConst.voxcpm2_AcousticDisplayedName, progress)
                        self.updateDownloadProgress(progress, for: modelKey, modelName: MiniCPMModelConst.voxcpm2_AcousticDisplayedName)
                    }
                }
            }
        })
    }

    // MARK: - 一键下载

    func downloadAll() {
        reconcileStatusFromDisk()
        debugLog("-->> VoxCPM2 一键下载：串行 BaseLM → Acoustic（避免带宽竞争）")
        downloadBaseLM { [weak self] in
            guard let self = self else { return }
            debugLog("-->> VoxCPM2 BaseLM 完成，开始下载 Acoustic")
            self.downloadAcoustic()
        }
    }

    func overallProgress() -> CGFloat {
        let baseLMBytes: Int64  = 1_002_374_880  // ~956 MB (from OBS Content-Length)
        let acousticBytes: Int64 = 1_825_096_352  // ~1.74 GB (from OBS Content-Length)
        let total = baseLMBytes + acousticBytes

        let baseLMProg    = progress(for: "voxcpm2_baselm",     downloadedWhenDone: baseLMBytes)
        let acousticProg  = progress(for: "voxcpm2_acoustic",   downloadedWhenDone: acousticBytes)

        let weighted = baseLMProg * CGFloat(baseLMBytes)
                     + acousticProg * CGFloat(acousticBytes)

        return total > 0 ? weighted / CGFloat(total) : 0
    }

    private func progress(for key: String, downloadedWhenDone _: Int64) -> CGFloat {
        let status = downloadQueue.sync { downloadStates[key] }
        let cached = downloadQueue.sync { downloadProgressCache[key] }
        if status == .completed { return 1.0 }
        return cached?.progress ?? 0.0
    }

    func resetDownloadStates() {
        downloadQueue.sync {
            downloadStates.removeAll()
            downloadProgressCache.removeAll()
            lastDownloadedBytes.removeAll()
        }
    }

    func deleteAllDownloadedFiles() {
        let fileManager = FileManager.default
        let documentsPath = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]

        let files = [
            MiniCPMModelConst.voxcpm2_BaseLMFileName,
            MiniCPMModelConst.voxcpm2_AcousticFileName
        ]
        for fileName in files {
            let fileURL = documentsPath.appendingPathComponent(fileName)
            if fileManager.fileExists(atPath: fileURL.path) {
                try? fileManager.removeItem(at: fileURL)
            }
        }
    }

    func cleanupCacheFiles() {
        let fileManager = FileManager.default
        let cachePath = fileManager.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let tmpPath = URL(fileURLWithPath: NSTemporaryDirectory())

        let files = [
            MiniCPMModelConst.voxcpm2_BaseLMFileName,
            MiniCPMModelConst.voxcpm2_AcousticFileName
        ]
        for fileName in files {
            let cacheFileURL = cachePath.appendingPathComponent(fileName)
            if fileManager.fileExists(atPath: cacheFileURL.path) {
                try? fileManager.removeItem(at: cacheFileURL)
            }
            let tmpFileURL = tmpPath.appendingPathComponent(fileName)
            if fileManager.fileExists(atPath: tmpFileURL.path) {
                try? fileManager.removeItem(at: tmpFileURL)
            }
        }
    }

    private func getDocumentsDirectory() -> URL {
        let paths = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
        return paths[0]
    }
}
