//
//  MBModelDownloadHelperV2.swift
//  MiniCPM-V-demo
//
//  Created by Alex on 2027/07/18.
//

import Foundation

import llama

/// 大模型下载管理器，可以同时下载 主模型 和 图像识别模型
class MBModelDownloadHelperV2: NSObject {
    
    /// 外部（调用方）传入的引用
    private var mtmdWrapperExample: MTMDWrapperExample

    /// 模型文件名
    private var modelName: String
    
    /// 模型对应服务器下载地址
    public var modelUrl: String

    /// 模型备用下载地址
    public var backupModelUrl: String?
    
    /// 文件名（有扩展名）
    private var filename: String
    
    /// 当前模型的下载状态【没有下载前需要下载】
    public var status: String
    
    private var downloadTask: URLSessionDownloadTask?
    
    /// 下载进度
    private var progress = 0.0
    
    public var observation: NSKeyValueObservation?
    
    // 定义一个闭包类型的属性
    public var completionHandler: ((CGFloat) -> Void)?
    
    /// 当前选中的模型
    private var loadedStatus: Bool
    
    /// 获取模型对应的本地路径
    private static func getFileURL(filename: String) -> URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(filename)
    }
    
    /// 模型下载管理器初始化方法
    /// - Parameters:
    ///   - wrapper: 外部传入的 MTMDWrapperExample 结构体的引用
    ///   - modelName: 模型名字
    ///   - modelUrl: 模型下载 url 地址
    ///   - filename: 本地文件名
    init(wrapper: MTMDWrapperExample, modelName: String, modelUrl: String, filename: String, backupModelUrl: String? = nil) {
        self.mtmdWrapperExample = wrapper
        self.modelName = modelName
        self.modelUrl = modelUrl
        self.backupModelUrl = backupModelUrl
        self.filename = filename
        
        // 获取模型本地 url
        let fileURL = MBModelDownloadHelperV2.getFileURL(filename: filename)
        
        // 模型是否存在
        status = FileManager.default.fileExists(atPath: fileURL.path) ? "downloaded" : "download"
        
        // 模型选中的模型
        loadedStatus = false
    }
}

/// Mutable race state shared by all sources participating in `downloadV2`.
///
/// - `winnerLocked`: a source has been picked, the other(s) should be cancelled
///                   and any further winner-decision callbacks ignored.
/// - `failedCount`:  how many sources have reported failure so far.  Only when
///                   ALL sources fail do we surface "failed" to the caller —
///                   a single source failing during a race is expected (HF
///                   times out behind GFW; the race winner is ModelScope).
/// - `settled`:      success has been delivered or all sources have failed;
///                   any further callbacks must be no-ops to avoid double-
///                   firing the upstream completionBlock.
private final class _DownloadRaceState {
    let lock = NSLock()
    var winnerLocked = false
    var winnerIndex: Int = -1
    var failedCount = 0
    var settled = false
}

extension MBModelDownloadHelperV2 {

    /// 断点续传下载器，**默认走 racing 模式**。
    ///
    /// 当主源 + 备用源都非空时，两者**同时**发起请求；以"谁先收到非零 totalsize
    /// 的 didReceiveResponse"为胜出条件，立刻 cancel 落败一方。胜出方继续下载到
    /// 成功，落败的 cancel 触发的 -999 错误被 race 状态机吞掉。
    ///
    /// 这样就把"国内访问 HF 必 timeout 30s 才 fallback ModelScope"的串行延迟
    /// 完全消掉 — 国内 ModelScope 通常 1.7 s 拿到 response 就可以 cancel 掉
    /// 注定 fail 的 HF 请求；海外 HF 直连 3-5 s 拿到 response 就可以 cancel 掉
    /// 慢得多的 ModelScope。无需机器探测网络环境，真实下载条件直接给答案。
    public func downloadV2(completionBlock: @escaping (String, CGFloat) -> Void) {

        if status == "downloaded" {
            return
        }

        // 收齐非空源（去重防止主备配错时同 url 跑两遍）
        var sources: [String] = []
        if !modelUrl.isEmpty { sources.append(modelUrl) }
        if let backup = backupModelUrl, !backup.isEmpty, backup != modelUrl {
            sources.append(backup)
        }

        guard !sources.isEmpty else {
            DispatchQueue.main.async {
                self.status = "failed"
                completionBlock("failed", 0)
            }
            return
        }

        if sources.count == 1 {
            // 单源退化到简单逻辑（不用 race，也不需要 winnerLocked / cancel 机制）
            launchSingleSource(url: sources[0], completionBlock: completionBlock)
            return
        }

        // ---- 多源 racing ----
        let race = _DownloadRaceState()
        debugLog("-->> 多源 race 启动 (\(sources.count) 源同时跑)")
        for (idx, url) in sources.enumerated() {
            debugLog("-->> race[\(idx)] start: \(url.prefix(120))")
            launchRaceSource(index: idx, url: url, totalSources: sources.count,
                             allUrls: sources, race: race, completionBlock: completionBlock)
        }
    }

    // MARK: - private internals

    /// 单源下载（无 race，无 fallback）。
    private func launchSingleSource(url: String,
                                    completionBlock: @escaping (String, CGFloat) -> Void) {
        FDownLoaderManager.shareInstance().downLoader(URL(string: url)) { totalSize in
            debugLog("-->> totalsize = \(totalSize)")
        } progress: { [weak self] progress in
            self?.progress = Double(progress)
            completionBlock(self?.status ?? "", CGFloat(progress))
        } success: { [weak self] cachePath in
            self?.handleDownloadSuccess(cachePath: cachePath, completionBlock: completionBlock)
        } failed: { [weak self] in
            FDownLoaderManager.shareInstance().downLoaderInfo.removeAllObjects()
            debugLog("-->> 下载失败.")
            DispatchQueue.main.async {
                self?.status = "failed"
                completionBlock("failed", -1)
            }
        }
    }

    /// 启动 race 中的一个源；所有共享状态通过 `race` 同步。
    private func launchRaceSource(index: Int,
                                  url: String,
                                  totalSources: Int,
                                  allUrls: [String],
                                  race: _DownloadRaceState,
                                  completionBlock: @escaping (String, CGFloat) -> Void) {
        FDownLoaderManager.shareInstance().downLoader(URL(string: url)) { totalSize in
            debugLog("-->> race[\(index)] totalsize = \(totalSize)")

            // 第一个拿到非零 totalsize 的胜出。0-byte 响应（错误页 / redirect 等）
            // 不算赢——继续等其他源。
            guard totalSize > 0 else { return }

            race.lock.lock()
            let winThisCallback = !race.winnerLocked
            if winThisCallback {
                race.winnerLocked = true
                race.winnerIndex = index
            }
            race.lock.unlock()

            if winThisCallback {
                debugLog("-->> race winner: index=\(index) totalsize=\(totalSize)，cancel 其余源")
                for (otherIdx, otherUrl) in allUrls.enumerated() where otherIdx != index {
                    if let otherURL = URL(string: otherUrl) {
                        FDownLoaderManager.shareInstance().cancle(with: otherURL)
                    }
                }
            }
        } progress: { [weak self] progress in
            // 只有胜出方的 progress 会真正递增；落败方在 cancel 后不再 fire。
            // 但保险起见 — 如果两源同时上报，只跟踪 winner 的进度。
            race.lock.lock()
            let isWinner = race.winnerLocked && race.winnerIndex == index
            race.lock.unlock()
            guard isWinner else { return }

            self?.progress = Double(progress)
            completionBlock(self?.status ?? "", CGFloat(progress))
        } success: { [weak self] cachePath in
            // 任何一个源 success 就锁定 settled，吞掉之后落败方的回调
            race.lock.lock()
            if race.settled {
                race.lock.unlock()
                return
            }
            race.settled = true
            race.lock.unlock()

            debugLog("-->> race[\(index)] success (winner)")
            self?.handleDownloadSuccess(cachePath: cachePath, completionBlock: completionBlock)
        } failed: { [weak self] in
            race.lock.lock()
            race.failedCount += 1
            let allFailed = race.failedCount >= totalSources && !race.settled
            if allFailed { race.settled = true }
            race.lock.unlock()

            if allFailed {
                debugLog("-->> race 全部 \(totalSources) 个源都失败")
                FDownLoaderManager.shareInstance().downLoaderInfo.removeAllObjects()
                DispatchQueue.main.async {
                    self?.status = "failed"
                    completionBlock("failed", -1)
                }
            } else {
                debugLog("-->> race[\(index)] failed (其他源仍在跑或已胜出)")
            }
        }
    }

    /// 共享的"下载完成 → 移到 Documents/" 路径。
    /// 单源 / racing 都共用一份逻辑。
    private func handleDownloadSuccess(cachePath: String?,
                                       completionBlock: @escaping (String, CGFloat) -> Void) {
        guard let cachePath = cachePath else { return }
        debugLog("-->> cachePath = \(cachePath)")
        let fileURL = MBModelDownloadHelperV2.getFileURL(filename: self.filename)
        // 用 fileURLWithPath 更稳，避免路径里出现 % 或非 ASCII 时 URL(string:) parse 失败
        let cacheURL = URL(fileURLWithPath: cachePath)
        debugLog("-->> fileURL = \(fileURL.path)")

        // 兜底确保 Documents 目录存在（理论上沙盒一定有，但实际遇到过 race）
        let docsURL = fileURL.deletingLastPathComponent()
        if !FileManager.default.fileExists(atPath: docsURL.path) {
            try? FileManager.default.createDirectory(at: docsURL, withIntermediateDirectories: true)
        }

        // 目标若已存在（旧 zip / 旧 gguf 残留）先删除，否则 moveItem 会抛
        // NSFileWriteFileExistsError，文案在 iOS 上常被本地化成 "no permission to access Documents"。
        if FileManager.default.fileExists(atPath: fileURL.path) {
            do {
                try FileManager.default.removeItem(at: fileURL)
                debugLog("-->> 已清理旧文件 \(fileURL.lastPathComponent)")
            } catch {
                debugLog("-->> 清理旧文件失败: \(error.localizedDescription)")
            }
        }

        do {
            try FileManager.default.moveItem(at: cacheURL, to: fileURL)
            debugLog("Writing to \(self.filename) completed")
            DispatchQueue.main.async {
                let model = ModelV2(name: self.modelName, url: self.modelUrl, filename: self.filename, status: "downloaded")
                self.mtmdWrapperExample.downloadedModels.append(model)
                self.status = "downloaded"
                completionBlock(self.status, 1.0)
            }
        } catch {
            // moveItem 偶尔会因为 cache 与 documents 跨卷或 sandbox 限制失败，回退到 copy + delete
            let nserr = error as NSError
            debugLog("Error: moveItem 失败 domain=\(nserr.domain) code=\(nserr.code) desc=\(nserr.localizedDescription)，尝试 copyItem 回退")
            do {
                // copy 之前再确保目标不存在
                if FileManager.default.fileExists(atPath: fileURL.path) {
                    try? FileManager.default.removeItem(at: fileURL)
                }
                try FileManager.default.copyItem(at: cacheURL, to: fileURL)
                try? FileManager.default.removeItem(at: cacheURL)
                debugLog("Writing to \(self.filename) completed (via copy)")
                DispatchQueue.main.async {
                    let model = ModelV2(name: self.modelName, url: self.modelUrl, filename: self.filename, status: "downloaded")
                    self.mtmdWrapperExample.downloadedModels.append(model)
                    self.status = "downloaded"
                    completionBlock(self.status, 1.0)
                }
            } catch {
                let e2 = error as NSError
                debugLog("Error: copy 回退仍失败 domain=\(e2.domain) code=\(e2.code) desc=\(e2.localizedDescription)")
                // 失败必须回传，避免 UI 卡 downloading
                DispatchQueue.main.async {
                    self.status = "failed"
                    completionBlock("failed", 0)
                }
            }
        }
    }
}
