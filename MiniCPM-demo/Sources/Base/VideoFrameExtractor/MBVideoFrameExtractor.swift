//
//  MBVideoFrameExtractor.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/7/15.
//

import Foundation
import AVFoundation
import UIKit

/// 视频（mp4）抽帧
@MainActor
class MBVideoFrameExtractor: Sendable {
    let videoURL: URL
    
    /// 采样率（默认每秒采样 1 帧）
    var framesPerSecond = 1
    
    /// 最大支持多少帧（默认最多支持 小于等于 16 帧）
    var maxSupportedFrames = 16

    // 抽取的视频帧
    var frameImages = [UIImage]()

    init(videoURL: URL, fps: Int, supportTotalFrames: Int = 16) {
        self.videoURL = videoURL
        self.framesPerSecond = fps
        if supportTotalFrames > 0 {
            self.maxSupportedFrames = supportTotalFrames
        }
    }
    
    /// 抽取视频帧，[images] 在回调的数组里
    func extractFrames(handler: (([UIImage]?)->Void)?) async {
        
        frameImages.removeAll()

        let asset = AVAsset(url: videoURL)
        
        let imageGenerator = AVAssetImageGenerator(asset: asset)
        
        imageGenerator.appliesPreferredTrackTransform = true
        imageGenerator.requestedTimeToleranceBefore = .zero
        imageGenerator.requestedTimeToleranceAfter = .zero
        
        // = asset.duration
        var duration: CMTime?
        
        do {
            // 因为这是耗时操作
            duration = try await asset.load(.duration)
        } catch let err {
            debugLog("extractFrames.err = \(err)")
        }
        
        guard let duration = duration else {
            return
        }

        if framesPerSecond <= 0 {
            // 至少保证每秒1帧
            framesPerSecond = 1
        }
                
        let totalSeconds = CMTimeGetSeconds(duration)
        
        var shouldTakeFrames = 0
        
        // 总长小于 16 秒
        if Int(totalSeconds) <= maxSupportedFrames {
            framesPerSecond = 1
            shouldTakeFrames = Int(totalSeconds)
        } else {
            // 如果总长大于 16 秒
            framesPerSecond = Int(totalSeconds) / maxSupportedFrames
            shouldTakeFrames = maxSupportedFrames
        }

        // let totalFrames = Int(totalSeconds * Double(framesPerSecond))
        let totalFrames = shouldTakeFrames
        
        var times = [NSValue]()
        
        for i in 0..<totalFrames {
            // 找出应该在第几秒进行抽帧
            let time = CMTimeMake(value: Int64(i), timescale: Int32(framesPerSecond))
            times.append(NSValue(time: time))
        }

        debugLog("-->> 视频总有 \(totalSeconds) 秒，所有应该是每 \(framesPerSecond) 秒抽一帧，预计有 \(times.count) 帧。")

        debugLog("begin generate.")

        // 创建一个本地数组来收集图像
        var localFrameImages = [UIImage]()
        
        imageGenerator.generateCGImagesAsynchronously(forTimes: times) { requestedTime, cgImage, actualTime, result, error in

            guard error == nil else {
                debugLog("Error occurred: \(String(describing: error))")
                return
            }
            
            guard let cgImage = cgImage else {
                debugLog("Image generation failed")
                return
            }

            let image = UIImage(cgImage: cgImage)
            localFrameImages.append(image)
            
            if totalSeconds > 16 {
                debugLog("Generated image for time: \(CMTimeGetSeconds(requestedTime) / 4 * totalSeconds) seconds")
            } else {
                debugLog("Generated image for time: \(CMTimeGetSeconds(requestedTime)) seconds")
            }
            
            if localFrameImages.count == totalFrames {
                debugLog("All frames have been generated.")
                
                debugLog("-->> 最终有 \(localFrameImages.count) 帧。")
                
                // 在主线程上更新 frameImages 并调用回调
                Task { @MainActor in
                    self.frameImages = localFrameImages
                    handler?(self.frameImages)
                }
            }
        }

    }
}
