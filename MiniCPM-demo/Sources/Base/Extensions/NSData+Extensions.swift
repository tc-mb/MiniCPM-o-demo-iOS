//
//  NSData+Extensions.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/7/1.
//

import Foundation
import CommonCrypto

extension Data {
    
    /// 返回对应文件 md5 校验结果
    var md5: String {
        let length = Int(CC_MD5_DIGEST_LENGTH)
        var digest = [UInt8](repeating: 0, count: length)
        
        self.withUnsafeBytes {
            _ = CC_MD5($0.baseAddress, CC_LONG(self.count), &digest)
        }
        
        return digest.map { String(format: "%02hhx", $0) }.joined()
    }
    
    /// 文件是否是 webp 格式的图片
    var isWebP: Bool {
        guard self.count >= 12 else { return false }
        
        let riff = self[0...3]
        let webp = self[8...11]
        
        let riffString = String(data: riff, encoding: .ascii)
        let webpString = String(data: webp, encoding: .ascii)
        
        return riffString == "RIFF" && webpString == "WEBP"
    }

}
