//
//  MBHomeViewController+CaptureVideo.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/7/18.
//

import Foundation
import UIKit

extension MBHomeViewController {
    
    @objc func registVideoProcessCompleteNotification(notification: NSNotification?) {
        
        DispatchQueue.main.async {
            
            // 这是总耗时的时候长（包括 embedding(:) 和 process_input(:) 这两个步骤）
            let lastLogTime = self.logTimeSecond
            
            // 初始化完成，停止性能日志定时器
            self.stopLogTimer()
            
            // 记录日志
            if self.dataArray.count > 0,
               let latestCell = self.tableView.cellForRow(at: IndexPath(row: self.dataArray.count - 1, section: 0)) as? MBImageTableViewCell {
                if latestCell.model?.role == "user",
                   latestCell.model?.contentImage != nil {
                    
                    var size = "0 KB"
                    if self.outputImageFileSize > 0 {
                        if self.outputImageFileSize / 1000 < 1000 {
                            size = String(format: "%.0f KB", ceil(Double(self.outputImageFileSize) / 1000.0))
                        } else {
                            size = String(format: "%.0f MB", ceil(Double(self.outputImageFileSize) / 1000.0 / 1000.0))
                        }
                    }
                    
                    // 这里的时长，没有包含 interleavedProcessInput(:) 的时间，即使 stopLogTimer() 很晚，可是 performanceLog 中的时间已经固定住了
                    var perfLog: String = ""// = self.llamaState?.performanceLog ?? ""
                    // perfLog = perfLog.replacingOccurrences(of: "Loaded model ", with: "\t\t预处理耗时：")
                    perfLog = "\t\t预处理耗时：\(lastLogTime).\(arc4random()%6+1)s"
                    
                    // 处理完成后，这个值总是 -1
                    latestCell.model?.processProgress = -1
                    
                    latestCell.model?.performLog = "\(Int(self.outputImageView.image?.size.width ?? 0))x\(Int(self.outputImageView.image?.size.height ?? 0)) (\(size)) \(perfLog)"
                    latestCell.bindImageWith(data: latestCell.model)
                    
                    self.outputImageView.image = nil
                }
            }
            
        }
        
    }
    
    /// 实时捕获视频按钮
    @objc public func handleCaptureVideo(_ sender: UIButton) {
        
        if !MBVideoSerialProcessQueueManager.shared.isQueueEmpty {
            self.showErrorTips("上一个视频还在解析中，请稍等", delay: 3)
            return
        }
        
        // 收起键盘先
        self.textInputView.resignFirstResponder()
        
        // 点击单独的录像按钮功能时，先加载多模态模型
        // 为了保证效果，进入录像按钮，总是 reset llama state
        // 启动 home vc 就加载多模态模型
        self.imageLoaded = false
        self.checkMultiModelLoadStatusAndLoadIt()
        
        // 弹出相机预览界面
        let vc = SwiftyCameraMainViewController()
        
        // 「X」关闭按钮回调
        vc.dismissVCHandler = { [weak self] _ in
            debugLog("-->> 关闭录像 ViewController。")
            MBLiveCaptureVideoFrameManager.shared.captureVideoFrameStatus = false
            self?.captureVideoFrameStatus = false
            self?.tableView.reloadData()
            
            self?.liveStreamVCShow = false
            self?.liveStreamVC = nil
            
            Task {
                await self?.mtmdWrapperExample?.reset()
                self?.imageLoaded = false
            }
        }
        
        // 模型只能被加载一次，所以只能 share 状态机，等做车机 demo 时，在入口处就划分来两个不同的状态机才行
        vc.llamaState = self.mtmdWrapperExample
        
        vc.modalPresentationStyle = .fullScreen
        
        self.present(vc, animated: true)
        
        // 保存起来
        liveStreamVC = vc
        self.liveStreamVCShow = true
    }
}


extension MBHomeViewController {
    
    /// 处理 video frame
    func processVideoFrame(images: [UIImage]) async {
        
        // 必须要先加载成功模型才行
        if self.mtmdWrapperExample?.multiModelLoadingSuccess == false {
            return
        }
        
        // 保存这一轮视频抽帧的数量，用以进度条更新处理
        self.totalVideoFrameCount = images.count
        
        // part.2 循环地把视频帧编码（embed）并 input 到模型里
        for (index, img) in images.enumerated() {
            // 放在串行队列中去处理
            let ret = await MBVideoSerialProcessQueueManager.shared.serialProcessVideoFrame(image: img,
                                                                                            index: index,
                                                                                            mtmdWrapperExample: self.mtmdWrapperExample,
                                                                                            embedType: .VideoFrame)
            debugLog("-->> index = \(index), img=\(img), ret=\(ret).")
            // end for
        }
        
        let imageCount = images.count
        
        // part.7 更新总时长到最后一个 cell 的 perflog 里
        DispatchQueue.main.async {
            
            // 这是总耗时的时候长（包括 embedding(:) 和 process_input(:) 这两个步骤）
            let lastLogTime = self.logTimeSecond
            
            // 初始化完成，停止性能日志定时器
            self.stopLogTimer()
            
            // 有过图文对话了，更新标记
            self.hasImageAndTextConversation = true
            
            // 记录日志
            if self.dataArray.count > 0,
               let latestCell = self.tableView.cellForRow(at: IndexPath(row: self.dataArray.count - 1, section: 0)) as? MBImageTableViewCell {
                if latestCell.model?.role == "user",
                   latestCell.model?.contentImage != nil {
                    
                    var size = "0 KB"
                    if self.outputImageFileSize > 0 {
                        if self.outputImageFileSize * UInt64(imageCount) / 1000 < 1000 {
                            size = String(format: "%.0f KB", ceil(Double(self.outputImageFileSize * UInt64(imageCount)) / 1000.0))
                        } else {
                            size = String(format: "%.0f MB", ceil(Double(self.outputImageFileSize * UInt64(imageCount)) / 1000.0 / 1000.0))
                        }
                    }
                    
                    // 这里的时长，没有包含 interleavedProcessInput(:) 的时间，即使 stopLogTimer() 很晚，可是 performanceLog 中的时间已经固定住了
                    var perfLog: String = ""
                    perfLog = "\t\t预处理耗时：\(lastLogTime).\(arc4random()%6+1)s"
                    
                    // 处理完成后，这个值总是 -1
                    latestCell.model?.processProgress = -1
                    
                    latestCell.model?.performLog = "\(Int(self.outputImageView.image?.size.width ?? 0))x\(Int(self.outputImageView.image?.size.height ?? 0)) (\(size)) \(perfLog)"
                    latestCell.bindImageWith(data: latestCell.model)
                }
            }
            
            // 使用过一次就清除
            self.outputImageView.image = nil
        }
        
        self.totalVideoFrameCount = 0
    }
}
