//
//  SwiftyCameraMainViewController+LiveStream.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/7/25.
//

import Foundation
import UIKit

extension SwiftyCameraMainViewController {
    
    /// 启动 live stream 定时器
    func startLiveStreamTimer() {

        MBLiveStreamVideoFrameManager.shared.capturedImageArray.removeAll()

        MBLiveStreamVideoFrameManager.shared.captureVideoFrameStatus = true
        
        // 不使用 while(true)，改为使用 timer
        MBLiveStreamVideoFrameManager.shared.startLoopProcessImageV2(wrapper: self.llamaState)

        // embed 完成回调在这儿
        MBLiveStreamVideoFrameManager.shared.completionBlock = { [weak self] ret in
            
            guard let self else {
                return
            }
            
            self.embeddingCount += 1
            
            self.processing = false
            
            debugLog("-->> embed, embeddingCount = \(self.embeddingCount)")
        }
        
        // 每 1 秒抽 1 帧
        liveStreamTimer = Timer.scheduledTimer(timeInterval: 1,
                                               target: self,
                                               selector: #selector(liveStreamTimerFired),
                                               userInfo: nil,
                                               repeats: true)
    }
    
    /// 结束 live stream 定时器
    public func invalidateLiveStreamTimer() {
        liveStreamTimer?.invalidate()
        liveStreamTimer = nil
        
        triggeredTime = 0
        
        // 清空队列
        MBLiveStreamVideoFrameManager.shared.captureVideoFrameStatus = false

        // 停止 v2 内部 timer
        MBLiveStreamVideoFrameManager.shared.stopTimer()
    }
    
    /// live stream 抽帧
    @objc fileprivate func liveStreamTimerFired() {
        
        // 到达指定时间，停止抽帧，目前是 180 秒
        if triggeredTime == 180 {
            invalidateLiveStreamTimer()
            // 超时后，再点一下拍摄按钮（停止）
            buttonWasTapped()
            self.showErrorTips("为避免上下文超长，请重启会话", delay: 3)
        }

        // 默认 1 帧后提问
        var embedThreshold = 1
        if let str = self.presetQuestionView.currentGapTitle {
            embedThreshold = Int(str) ?? 1
        }

        // 已经 embed 了 \(embedThreshold) 帧，可以提问了
        if (self.embeddingCount == embedThreshold) && (self.processing == false) {
            debugLog("-->> 配置为 \(embedThreshold) 帧后提问。")
            startLiveStreamQuestion()
        } else {
            // 抽帧
            if self.processing == false {
                self.processing = true
                debugLog("processing = false，embedCount = \(self.embeddingCount)")
                let img = self.capturedFrameImage
                // @sgl new sample buffer
                processLiveStreamPhotoLogic(photo: img)
            } else {
                // debugLog("processing = true，embedCount = \(self.embeddingCount)")
            }
        }

        triggeredTime += 1
    }
    
    // MARK: - live stream 图片处理
    
    /// 定时处理 live stream 图片
    func processLiveStreamPhotoLogic(photo: UIImage?) {
        
        guard let photo else {
            return
        }

        if MBLiveStreamVideoFrameManager.shared.capturedImageArray.count == 0 {
            debugLog("-->> 开始入队列处理图片。")
            MBLiveStreamVideoFrameManager.shared.appendImageToArray(img: photo)
        } else {
            debugLog("-->> 还有未处理完的帧，跳过。")
        }
    }
    
    /// live stream 指定提问的时间到了，就进行去提问
    func startLiveStreamQuestion() {
        
        guard let llamaState else {
            return
        }
        
        if self.isVideoRecording == false {
            return
        }
        
        // 有帧在 embedding & input 时不能 loop() 吗？
        if processing {
            return
        }

        // 当前提问的词
        guard var str = self.presetQuestionView.currentSelectedButtonTitle else {
            return
        }
        
        debugLog("-->> 第\(triggeredTime)秒，开始提问。")

        // 清空之前的输出
        llamaState.outputText = ""

        self.processing = true
        self.liveOutputTextLabel.text = ""
        self.liveOutputTextLabel.attributedText = nil
        
        // 包含用户自己配置过的 prompt
        let extraPrompt = self.presetQuestionView.currentExtraPrompt ?? ""
        
        // 组装好完整的提问问题
        if extraPrompt.isEmpty {
            // 没有用户自定义
            str = str + "在接下来的输出中避免使用‘画面’、‘快照’、‘图片’、‘场景’等词汇，全部用‘视频’代替，并确保生成的文本不超过100字。"
        } else {
            str = extraPrompt
        }

        Task {
            debugLog("-->> live.stream.插入提示词：\(str)")
            let ret = await llamaState.addTextInBackground(str)
            debugLog("-->> live.stream.插入提示词成功。")

            if ret {
                debugLog("-->> 开始提问")
                await llamaState.startGeneration()
            } else {
                self.processing = false
                self.embeddingCount = 0
                self.invalidateLiveStreamTimer()
                self.showErrorTips("运行时间及久，超出上下文，请重启进入。", delay: 3)
            }
        }

    }
    
}
