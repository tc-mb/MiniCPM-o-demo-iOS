//
//  MBUtils+checkMD5.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/7/1.
//

import Foundation
import CommonCrypto

extension MBUtils {
    
    /// 文件 MD5 值生成（用以校验）
    public static func md5(for fileURL: URL) -> String? {
        // Define the read buffer size
        let bufferSize = 1024 * 1024 // 1MB
        
        // Open the file for reading
        guard let fileHandle = try? FileHandle(forReadingFrom: fileURL) else {
            return nil
        }
        
        defer {
            fileHandle.closeFile()
        }
        
        // Initialize MD5 context
        var context = CC_MD5_CTX()
        CC_MD5_Init(&context)
        
        // Read the file in chunks and update the hash context
        while autoreleasepool(invoking: {
            let fileData = fileHandle.readData(ofLength: bufferSize)
            if fileData.count > 0 {
                _ = fileData.withUnsafeBytes { (bytes: UnsafeRawBufferPointer) in
                    CC_MD5_Update(&context, bytes.baseAddress, CC_LONG(fileData.count))
                }
                return true
            } else {
                return false
            }
        }) {}
        
        // Finalize the hash computation
        var digest = Data(count: Int(CC_MD5_DIGEST_LENGTH))
        digest.withUnsafeMutableBytes { (bytes: UnsafeMutableRawBufferPointer) in
            CC_MD5_Final(bytes.bindMemory(to: UInt8.self).baseAddress!, &context)
        }
        
        // Convert the digest to a string
        return digest.map { String(format: "%02hhx", $0) }.joined()
    }
    
}
