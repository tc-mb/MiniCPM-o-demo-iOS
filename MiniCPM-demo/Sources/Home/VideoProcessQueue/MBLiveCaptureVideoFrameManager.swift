//
//  MBLiveCaptureVideoFrameManager.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/7/19.
//

import Foundation
import UIKit
import llama

/// 实时并行视频抽帧、embed 和 input 逻辑（内部是一个串行队列）
class MBLiveCaptureVideoFrameManager {
    
    // Singleton
    static let shared = MBLiveCaptureVideoFrameManager()
    
    // 这个地方应该加锁
    var capturedImageArray = [UIImage]()
    
    var captureVideoFrameStatus = true
    
    // 一次一帧
    var processRunning = false
    
    // 创建一个全局的串行队列
    private let serialQueue = DispatchQueue(label: "com.modelbest.minicpmv.demo.live.capture.video.frame")
    
    func startLoopProcessImage(mtmdWrapperExample: MTMDWrapperExample?) {
        
        guard let mtmdWrapperExample else {
            return
        }
        
        Task {
            while(true) {
                
                if self.capturedImageArray.count > 0 {
                    
                    if self.processRunning == true {
                        continue
                    }
                    
                    self.processRunning = true
                    
                    let photo = self.capturedImageArray.first
                    
                    if let photo = photo {
                        debugLog("-->> 抽帧开始，获取到的照片分别如下： \(photo)")
                    }
                    
                    // 实时录像时，抽到的帧放在串行队列中去处理
                    let ret = await MBVideoSerialProcessQueueManager.shared.serialProcessVideoFrame(image: photo,
                                                                                                    index: 0,
                                                                                                    mtmdWrapperExample: mtmdWrapperExample,
                                                                                                    embedType: .VideoFrame)
                    if ret {
                        debugLog("-->> 抽帧完成，获取到的照片分别如下。")
                    } else {
                        debugLog("-->> 视频解析错误，请重置会话再试。")
                        return
                    }
                    
                    // 删除
                    self.capturedImageArray.removeFirst()
                    
                    debugLog("-->> 抽帧剩余 \(self.capturedImageArray.count)")
                    
                    self.processRunning = false
                    
                    if self.captureVideoFrameStatus == false && self.capturedImageArray.count == 0 {
                        debugLog("-->> 抽帧全部完成，可以继续提问了。if")
                        NotificationCenter.default.post(name: NSNotification.Name("video.process.complete"), object: nil, userInfo: nil)
                        return
                    } else {
                        debugLog("--> 还没有结束。")
                    }
                    
                } else {
                    if self.captureVideoFrameStatus == false && self.capturedImageArray.count == 0 {
                        // 这时，UI 层可以通过这个来进行继续输入的逻辑
                        debugLog("-->> 对列中没有待处理的视频帧。else")
                    }
                }
            }
        }
    }
}

