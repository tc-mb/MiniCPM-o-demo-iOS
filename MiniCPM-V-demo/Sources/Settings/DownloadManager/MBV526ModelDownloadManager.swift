//
//  MBV526ModelDownloadManager.swift
//  MiniCPM-V-demo
//
//  MiniCPM5-2B 纯文本模型下载管理器（只需 LLM，无 mmproj / ANE）
//

import Foundation
import UIKit

class MBV526ModelDownloadManager: NSObject {

    static let shared = MBV526ModelDownloadManager()

    private override init() {
        super.init()
    }

    private var mtmdWrapperExample: MTMDWrapperExample?

    private var modelv526_Manager: MBModelDownloadHelperV2?

    var progressHandler: ((String, CGFloat) -> Void)?
    var completionHandler: ((String, Bool) -> Void)?
    var detailedProgressHandler: ((DownloadProgressInfo) -> Void)?

    private var downloadStates: [String: DownloadStatus] = [:]
    private var downloadProgressCache: [String: DownloadProgressInfo] = [:]
    private var downloadStartTimes: [String: Date] = [:]
    private var lastDownloadedBytes: [String: Int64] = [:]
    private let downloadQueue = DispatchQueue(label: "com.minicpm.v526.download", qos: .userInitiated)

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
        downloadQueue.sync {
            let currentTime = Date()
            let startTime = downloadStartTimes[modelKey] ?? currentTime
            let timeElapsed = currentTime.timeIntervalSince(startTime)

            var speed: Double = 0
            if let lastBytes = lastDownloadedBytes[modelKey], timeElapsed > 0 {
                speed = Double(downloadedBytes - lastBytes) / timeElapsed
            }
            lastDownloadedBytes[modelKey] = downloadedBytes

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

    func setupDownloadManager(with wrapper: MTMDWrapperExample) {
        self.mtmdWrapperExample = wrapper
        setupModels()
    }

    private func setupModels() {
        guard let mtmdWrapperExample = mtmdWrapperExample else { return }

        modelv526_Manager = MBModelDownloadHelperV2(
            wrapper: mtmdWrapperExample,
            modelName: MiniCPMModelConst.modelv526_FileName,
            modelUrl: MiniCPMModelConst.modelv526_URLString,
            filename: MiniCPMModelConst.modelv526_FileName,
            backupModelUrl: MiniCPMModelConst.modelv526_BackUpURLString
        )

        reconcileStatusFromDisk()
        restoreDownloadProgress()
    }

    func reconcileStatusFromDisk() {
        let docs = getDocumentsDirectory()
        let fm = FileManager.default

        let llmPath = docs.appendingPathComponent(MiniCPMModelConst.modelv526_FileName).path
        modelv526_Manager?.status = fm.fileExists(atPath: llmPath) ? "downloaded" : "download"
    }

    private func restoreDownloadProgress() {
        guard let info = FDownLoaderManager.shareInstance().downLoaderInfo else { return }

        let llmKey = String(stringLiteral: MiniCPMModelConst.modelv526_URLString).md5() ?? ""
        if let obj = info[llmKey] as? FDownLoader, obj.state == .downLoading {
            downloadModelv526()
        }
    }

    func downloadAll() {
        reconcileStatusFromDisk()
        debugLog("-->> V526 一键下载：拉起 LLM（纯文本模型，无 VPM / ANE）")
        downloadModelv526()
    }

    func overallProgress() -> CGFloat {
        return progress(forKey: "v526_main_model")
    }

    private func progress(forKey modelKey: String) -> CGFloat {
        let status = getModelv526_Status()
        if status == "downloaded" { return 1.0 }
        if let info = downloadQueue.sync(execute: { downloadProgressCache[modelKey] }) {
            return info.progress
        }
        return 0
    }

    func hasAnyDownloadActive() -> Bool {
        reconcileStatusFromDisk()
        let mainDone = (modelv526_Manager?.status == "downloaded")
        return downloadQueue.sync {
            for (key, state) in downloadStates {
                guard state == .downloading || state == .paused else { continue }
                if key == "v526_main_model" && mainDone { continue }
                return true
            }
            return false
        }
    }

    func downloadModelv526() {
        let modelKey = "v526_main_model"

        guard !isDownloading(modelKey) else {
            debugLog("-->> V526 主模型正在下载中，忽略重复调用")
            return
        }
        if getModelv526_Status() == "downloaded" {
            debugLog("-->> V526 主模型已下载完成")
            return
        }

        setDownloadStatus(.downloading, for: modelKey)

        modelv526_Manager?.downloadV2(completionBlock: { [weak self] status, progress in
            guard let self = self else { return }

            DispatchQueue.main.async {
                if progress >= 1 {
                    self.setDownloadStatus(.completed, for: modelKey)
                    self.verifyModelv526_MD5()
                    self.progressHandler?(MiniCPMModelConst.modelv526_DisplayedName, 1.0)
                    self.completionHandler?(MiniCPMModelConst.modelv526_DisplayedName, true)
                    self.updateDownloadProgress(1.0, for: modelKey, modelName: MiniCPMModelConst.modelv526_DisplayedName)
                } else {
                    if status == "failed" {
                        self.setDownloadStatus(.failed, for: modelKey)
                        self.progressHandler?(MiniCPMModelConst.modelv526_DisplayedName + L.Download.progressFailedSuffix.loc, -1)
                        self.completionHandler?(MiniCPMModelConst.modelv526_DisplayedName, false)
                    } else {
                        self.progressHandler?(MiniCPMModelConst.modelv526_DisplayedName, progress)
                        self.updateDownloadProgress(progress, for: modelKey, modelName: MiniCPMModelConst.modelv526_DisplayedName)
                    }
                }
            }
        })
    }

    private func verifyModelv526_MD5() {
        let fileURL = getDocumentsDirectory().appendingPathComponent(MiniCPMModelConst.modelv526_FileName)
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return }

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let checksum = MBUtils.md5(for: fileURL)
            DispatchQueue.main.async {
                guard let self = self else { return }
                if let checksum = checksum {
                    debugLog("-->> V526 主模型 实际 MD5: \(checksum)")
                    debugLog("-->> V526 主模型 期望 MD5: \(MiniCPMModelConst.modelv526_MD5)")
                    if checksum == MiniCPMModelConst.modelv526_MD5 {
                        debugLog("-->> V526 主模型 MD5 校验成功")
                        self.modelv526_Manager?.status = "downloaded"
                        self.setDownloadStatus(.completed, for: "v526_main_model")
                    } else {
                        debugLog("-->> V526 主模型 MD5 校验失败")
                        self.modelv526_Manager?.status = "download"
                        self.deleteModelv526()
                    }
                } else {
                    debugLog("-->> V526 主模型 MD5 计算失败")
                    self.modelv526_Manager?.status = "download"
                    self.deleteModelv526()
                }
            }
        }
    }

    func deleteModelv526() {
        let fileURL = getDocumentsDirectory().appendingPathComponent(MiniCPMModelConst.modelv526_FileName)
        do {
            try FileManager.default.removeItem(at: fileURL)
            modelv526_Manager?.status = "download"
            setDownloadStatus(.notStarted, for: "v526_main_model")
            debugLog("-->> V526 主模型删除成功")
        } catch {
            debugLog("-->> V526 主模型删除失败: \(error.localizedDescription)")
        }
    }

    func getModelv526_Status() -> String {
        return modelv526_Manager?.status ?? "download"
    }

    func hasAnyModelDownloading() -> Bool {
        return downloadQueue.sync { downloadStates.values.contains(.downloading) }
    }

    private func getDocumentsDirectory() -> URL {
        return FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    func pauseAllDownloads() {
        FDownLoaderManager.shareInstance().pauseAll()
        downloadQueue.sync {
            for key in downloadStates.keys where downloadStates[key] == .downloading {
                downloadStates[key] = .paused
            }
        }
    }

    func resumeAllDownloads() {
        FDownLoaderManager.shareInstance().resumeAll()
        downloadQueue.sync {
            for key in downloadStates.keys where downloadStates[key] == .paused {
                downloadStates[key] = .downloading
            }
        }
    }

    func cancelAllDownloads() {
        FDownLoaderManager.shareInstance().downLoaderInfo.removeAllObjects()
        downloadQueue.sync {
            downloadStates.removeAll()
            downloadProgressCache.removeAll()
            downloadStartTimes.removeAll()
            lastDownloadedBytes.removeAll()
        }
    }

    func resetDownloadStates() {
        downloadQueue.sync {
            downloadStates.removeAll()
            downloadProgressCache.removeAll()
            downloadStartTimes.removeAll()
            lastDownloadedBytes.removeAll()
        }
    }

    var mainModelManager: MBModelDownloadHelperV2? { modelv526_Manager }
}
