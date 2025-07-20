//
//  MBModelDownloadHelperV2.swift
//  MiniCPM-demo
//
//  Created by Alex on 2027/07/18.
//

import Foundation

import llama

/// 大模型下载管理器，可以同时下载 主模型 和 图像识别模型
class MBModelDownloadHelperV2: NSObject {
    
    /// 外部（调用方）传入的引用
    private var mtmdWrapperExample: MTMDWrapperExample

    /// 模型文件名
    private var modelName: String
    
    /// 模型对应服务器下载地址
    public var modelUrl: String
    
    /// 文件名（有扩展名）
    private var filename: String
    
    /// 当前模型的下载状态【没有下载前需要下载】
    public var status: String
    
    private var downloadTask: URLSessionDownloadTask?
    
    /// 下载进度
    private var progress = 0.0
    
    public var observation: NSKeyValueObservation?
    
    // 定义一个闭包类型的属性
    public var completionHandler: ((CGFloat) -> Void)?
    
    /// 当前选中的模型
    private var loadedStatus: Bool
    
    /// 获取模型对应的本地路径
    private static func getFileURL(filename: String) -> URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(filename)
    }
    
    /// 模型下载管理器初始化方法
    /// - Parameters:
    ///   - wrapper: 外部传入的 MTMDWrapperExample 结构体的引用
    ///   - modelName: 模型名字
    ///   - modelUrl: 模型下载 url 地址
    ///   - filename: 本地文件名
    init(wrapper: MTMDWrapperExample, modelName: String, modelUrl: String, filename: String) {
        self.mtmdWrapperExample = wrapper
        self.modelName = modelName
        self.modelUrl = modelUrl
        self.filename = filename
        
        // 获取模型本地 url
        let fileURL = MBModelDownloadHelperV2.getFileURL(filename: filename)
        
        // 模型是否存在
        status = FileManager.default.fileExists(atPath: fileURL.path) ? "downloaded" : "download"
        
        // 模型选中的模型
        loadedStatus = false
    }
}

extension MBModelDownloadHelperV2 {
    
    /// 断点续传下载器
    public func downloadV2(completionBlock: @escaping (String, CGFloat) -> Void) {
        
        if status == "downloaded" {
            return
        }
        
        FDownLoaderManager.shareInstance().downLoader(URL(string: modelUrl)) { totalSize in
            debugLog("-->> totalsize = \(totalSize)")
        } progress: { [weak self] progress in
            self?.progress = Double(progress)
            completionBlock(self?.status ?? "", CGFloat(progress))
        } success: { [weak self] cachePath in
            guard let cachePath = cachePath else {
                return
            }
            
            debugLog("-->> cachePath = \(cachePath)")
            
            do {
                // 生成正式的文件地址（下载完成后，需要从 cache folder copy 到 documents folder 中）
                let fileURL = MBModelDownloadHelperV2.getFileURL(filename: self?.filename ?? "")
                
                let temporaryURLString = String(format: "file://%@", cachePath)
                
                if let cacheURL = URL(string: temporaryURLString) {
                    
                    try FileManager.default.moveItem(at: cacheURL, to: fileURL)
                    
                    debugLog("Writing to \(self?.filename ?? "") completed")
                    
                    DispatchQueue.main.async {
                        
                        let model = ModelV2(name: self?.modelName ?? "", url: self?.modelUrl ?? "", filename: self?.filename ?? "", status: "downloaded")
                        
                        self?.mtmdWrapperExample.downloadedModels.append(model)
                        
                        self?.status = "downloaded"
                        
                        // 更新进度
                        if let s = self?.status {
                            completionBlock(s, 1.0)
                        }
                    }
                    
                }
                
            } catch let err {
                debugLog("Error: \(err.localizedDescription)")
            }
            
        } failed: {

            FDownLoaderManager.shareInstance().downLoaderInfo.removeAllObjects()
            
            completionBlock("failed", -1)

            debugLog("-->> 下载失败.")
        }
    }
}
