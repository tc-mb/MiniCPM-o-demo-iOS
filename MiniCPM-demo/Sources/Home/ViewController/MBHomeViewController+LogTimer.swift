//
//  MBHomeViewController+LogTimer.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/6/20.
//

import Foundation

/// 性能日志 timer
extension MBHomeViewController {
    
    /// 开启：性能日志 Timer
    func startLogTimer() {
        self.logTimeSecond = 0
        logTimer = Timer.scheduledTimer(timeInterval: 0.1,
                                        target: self,
                                        selector: #selector(logTimerFire),
                                        userInfo: nil,
                                        repeats: true)
    }
    
    /// Timer 更新
    @objc func logTimerFire() {
        
        // find image cell and update log
        DispatchQueue.main.async {
            self.logTimeSecond += 0.1
            if self.dataArray.count > 0 {
                if let latestCell = self.tableView.cellForRow(at: IndexPath(row: self.dataArray.count - 1, section: 0)) as? MBImageTableViewCell {
                    if latestCell.model?.role == "user",
                       latestCell.model?.contentImage != nil {
                        
                        // 更新性能日志
                        
                        if self.totalVideoFrameCount == 0 {
                            // 视频文件 size 大小计算
                            self.totalVideoFrameCount = 1
                        }
                        
                        var size = "0 KB"
                        if self.outputImageFileSize > 0 {
                            if self.outputImageFileSize * UInt64(self.totalVideoFrameCount) / 1000 < 1000 {
                                size = String(format: "%.0f KB", ceil(Double(self.outputImageFileSize * UInt64(self.totalVideoFrameCount)) / 1000.0))
                            } else {
                                size = String(format: "%.0f MB", ceil(Double(self.outputImageFileSize * UInt64(self.totalVideoFrameCount)) / 1000.0 / 1000.0))
                            }
                        }
                        
                        latestCell.model?.performLog = "\(Int(self.outputImageView.image?.size.width ?? 0))x\(Int(self.outputImageView.image?.size.height ?? 0)) (\(size))"
                        
                        // 更新图片处理的进度
                        // 绿色进度条，预测的耗时时长提 (MAX(image.width, image.height) * 2 + 1) * (一 frame 用时 4-8s)s + (llm 用时 8-10)s = 总耗时
                        let imgWidth = self.outputImageView.image?.size.width ?? 0
                        let imgHeight = self.outputImageView.image?.size.height ?? 0
                        
                        // 切片数量
                        let clipCount = floor(max(imgWidth/448, imgHeight/448)) * 1 + 1
                        var totalTime = Int(clipCount * 4 + 7)
                        // 例如切了 5 片，则总时长大约就是 5 * 4 + 6 = 26s
                        // 则我们可以在每 3 秒（可以整除）的时候，更新进度
                        
                        if self.outputImageURL?.absoluteString.hasSuffix("mov") == true ||
                            self.outputImageURL?.absoluteString.hasSuffix("mp4") == true {
                            // 是视频，则总时长应该和视频的大小有关
                            totalTime = Int(self.totalVideoFrameCount * 4 + 7)
                            debugLog("选中的是视频，所有预计总时长为：\(self.logTimeSecond) : \(totalTime)")
                        }
                        
                        // 计算进度百分比
                        var percent = CGFloat(self.logTimeSecond) / CGFloat(totalTime)
                        
                        // 让进度条增长得更快，使用更激进的加速策略
                        // 前期快速增长，后期稍微慢一些，但不会停在90%
                        if percent < 0.2 {
                            // 前20%时间，进度条快速增长到70%
                            percent = percent * 3.5
                        } else if percent < 0.5 {
                            // 20%-50%时间，进度条从70%增长到95%
                            percent = 0.7 + (percent - 0.2) * 0.83
                        } else {
                            // 50%以后，进度条从95%增长到100%
                            percent = 0.95 + (percent - 0.5) * 0.1
                        }
                        
                        // 确保不超过100%
                        percent = min(percent, 1.0)
                        
                        // 更新进度条
                        latestCell.model?.processProgress = percent
                        
                        latestCell.bindImageWith(data: latestCell.model)
                    }
                }
            }
        }
    }
    
    /// 停止 Timer
    func stopLogTimer() {
        logTimer?.invalidate()
        logTimer = nil
        logTimeSecond = 0
    }
}
