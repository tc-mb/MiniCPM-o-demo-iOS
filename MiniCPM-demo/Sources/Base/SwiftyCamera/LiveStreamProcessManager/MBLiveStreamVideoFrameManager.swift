//
//  MBLiveStreamVideoFrameManager.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/7/19.
//

import Foundation
import UIKit
import llama

/// 实时并行视频抽帧、embed 和 input 逻辑（内部是一个串行队列）
class MBLiveStreamVideoFrameManager {
    
    // Singleton
    static let shared = MBLiveStreamVideoFrameManager()
    
    // 创建一个全局的串行队列
    private let serialQueue = DispatchQueue(label: "com.modelbest.minicpmv.demo.live.stream.video.frame")
    
    // 这个地方应该加锁
    var capturedImageArray = [UIImage]()
    
    var captureVideoFrameStatus = true
    
    // 一次一帧
    var processRunning = false
    
    fileprivate var timer: Timer?
    
    // 外部传入的 llama mtmd state machine
    weak var mtmdWrapperExample: MTMDWrapperExample?
    
    /// 每次 embed 完的回调
    var completionBlock: ((Bool) -> Void)?
    
    var logTimeCount = 0
    
    /// 启动 live stream 定时器
    func startTimer() {
        // every 0.3s embedding & input image to model
        timer = Timer.scheduledTimer(timeInterval: 0.3,
                                     target: self,
                                     selector: #selector(timerFired),
                                     userInfo: nil,
                                     repeats: true)
    }
    
    /// 结束 live stream 定时器
    func stopTimer() {
        timer?.invalidate()
        timer = nil
    }
    
    /// live stream 抽帧
    @objc public func timerFired() {
        
        Task {
            
            if self.captureVideoFrameStatus == false {
                // 超时结束
                self.capturedImageArray.removeAll()
                debugLog("-->> 结束 live.stream.timerFired().")
                return
            }
            
            if self.capturedImageArray.count > 0 {
                
                if self.processRunning == true {
                    return
                }
                
                self.processRunning = true
                
                // 取一帧
                let photo = self.capturedImageArray.first
                
                if let photo = photo {
                    debugLog("-->> 抽帧开始，取到一帧： image.size = \(photo.mb_covertToData().count)")
                }
                
                // 实时录像时，抽到的帧放在串行队列中去处理
                let ret = await MBVideoSerialProcessQueueManager.shared.serialProcessVideoFrame(image: photo,
                                                                                                index: logTimeCount,
                                                                                                mtmdWrapperExample: mtmdWrapperExample,
                                                                                                embedType: .LiveVideoFrame)
                if ret {
                    debugLog("-->> 抽帧完成，live.stream。")
                } else {
                    debugLog("-->> 抽帧错误，live.stream.请重置会话再试。")
                }
                
                // 删除
                if self.capturedImageArray.count > 0 {
                    self.removeFirstImage()
                }
                
                debugLog("-->> 抽帧剩余 \(self.capturedImageArray.count)")
                
                self.processRunning = false
                
                // 完成回调
                self.completionBlock?(true)
                
                logTimeCount += 1
            }
        }
    }
    
    func startLoopProcessImageV2(wrapper: MTMDWrapperExample?) {
        
        guard let wrapper else {
            return
        }
        
        self.mtmdWrapperExample = wrapper
        
        startTimer()
    }
    
    func appendImageToArray(img: UIImage?) {
        
        guard let img else {
            return
        }
        
        serialQueue.sync {
            self.capturedImageArray.append(img)
        }
    }
    
    func removeFirstImage() {
        let _ = serialQueue.sync {
            if self.capturedImageArray.count > 0 {
                self.capturedImageArray.removeFirst()
            }
        }
    }
    
}
