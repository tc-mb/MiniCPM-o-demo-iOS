//
//  MBHomeViewController+CheckSupport.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/6/19.
//

import Foundation
import UIKit

extension MBHomeViewController {

    /// 是否是低端设备
    public func isLowerDevice() -> Bool {

        if MBUtils.isDeviceIPhone() {
            return true
        }
        
        return false
    }
    
    /// 检查 Metal 的支持情况
    func checkMetalSupportAndAlert() {
        
    }
 
    /// 保存 UIImage 到 沙箱 cache folder 里
    public func saveImageToCache(image: UIImage,
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
            // 选中的图片大小（KB，MB）
            self.outputImageFileSize = UInt64(imageData.count)
            try imageData.write(to: url)
        } catch {
            print("Error saving image to cache: \(error)")
            return nil
        }

        return url
    }
}
