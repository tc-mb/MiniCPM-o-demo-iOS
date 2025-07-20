//
//  MBVideoSerialProcessQueueManager.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/7/19.
//

import Foundation
import UIKit
import llama

/// 串行处理 vidoe frame embed + input 的 queue
class MBVideoSerialProcessQueueManager: NSObject {
    
    // Singleton
    static let shared = MBVideoSerialProcessQueueManager()

    private var taskCount: Int = 0
    
    // 创建一个全局的串行队列
    private let serialQueue = DispatchQueue(label: "com.modelbest.minicpmv.demo.video.serial.process.queue")
    
    func serialProcessVideoFrame(image: UIImage?, index: Int, mtmdWrapperExample: MTMDWrapperExample?, embedType: ImageEmbeddingTypeV2) async -> Bool {
        
        incrementTaskCount()

        defer {
            decrementTaskCount()
        }
        
        guard let img = image, let mtmdWrapperExample = mtmdWrapperExample else {
            return false
        }

        // 抽帧之后，依次把截图给到模型
        // step.1 save 到本地磁盘的 cache folder 里，然后去 embed 和 input 到模型里
        debugLog("-->> selected.video.image = \(img)")

        // 把视频帧以 jpeg 格式，90% 质量，保存到 cache 中
        let timestamp = Int(Date().timeIntervalSince1970)
        let randomNumber = Int.random(in: 1000...9999)
        let filename = "myvfs_\(timestamp)_\(randomNumber).png"
        
        if let imgURL = self.saveImageToCache(image: img, fileName: filename) {
            debugLog("-->> index = \(index), begin.embed.video.frame = \(imgURL.pathComponents.last ?? "")")
            let ret = await mtmdWrapperExample.addImageInBackground(imgURL.path)
            debugLog("-->> index = \(index), end.input.video.frame = \(imgURL.pathComponents.last ?? "")，并且 input 给模型, ret = \(ret)")
            return ret
        }
        
        return false
    }
    
    private func incrementTaskCount() {
        taskCount += 1
    }
    
    private func decrementTaskCount() {
        taskCount -= 1
    }
    
    public var isQueueEmpty: Bool {
        return taskCount == 0
    }

    public var runningTaskCount: Int {
        return taskCount
    }
    
    /// 保存 UIImage 到 沙箱 cache folder 里
    private func saveImageToCache(image: UIImage,
                                  fileName: String,
                                  asJPEGFormat: Bool = true,
                                  compressionQuality: CGFloat = 1) -> URL? {
        let cacheDirectory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
        let fileUrl = cacheDirectory?.appendingPathComponent(fileName)
        
        var data: Data?
        
        if asJPEGFormat {
            data = image.jpegData(compressionQuality: compressionQuality)
        } else {
            data = image.pngData()
        }
        
        guard let imageData = data, let url = fileUrl else { return nil }

        do {
            try imageData.write(to: url)
        } catch {
            debugLog("saveImageToCache(:) error.")
            return nil
        }

        return url
    }
}
