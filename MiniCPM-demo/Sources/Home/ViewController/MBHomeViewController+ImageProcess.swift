//
//  MBHomeViewController+ImageProcess.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/6/27.
//

import Foundation
import UIKit
import HXPhotoPicker

extension MBHomeViewController {

    // MARK: - 图片 embed & clip
    
    /// 异步更新当前选中的（应用）模型的类型（例如语言模型 还是 多模态模型）
    func updateCurrentUsingModelType(_ type: CurrentUsingModelTypeV2) async {
        self.currentUsingModelType = type
    }
    
    /// 使用 用户选择的模型 和 image 初始化模型
    public func prepareLoadModelAddImageToCell() {
        Task.detached(priority: .userInitiated) {
            // 一次只加载一张图片并让模型 embedding 完成后，才能再加载另一张图片；
            if await !self.uploadSingleImageToModel {
                
                // 开始记录 image embedding 的时间
                await self.startLogTimer()
                
                // part.2 调用 4v3b 新方法，也是图片处理的方法进行图片 embed
                if let imgPath = await self.outputImageURL?.path {
                    // 在后台线程中执行 addImage 操作，避免阻塞主线程
                    let ret = await self.mtmdWrapperExample?.addImageInBackground(imgPath) ?? false
                    print("[UI]addImage: \(imgPath) ret = \(ret)")
                }
                
                // part.7 更新 UI, embedding 图片的耗时
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
                            
                            // 格式化耗时显示，logTimeSecond 是以 0.1 秒为单位，需要转换为秒
                            let timeInSeconds = String(format: "%.1f", lastLogTime)
                            var perfLog: String = ""
                            perfLog = "\t\t预处理耗时：\(timeInSeconds)s"
                            
                            // 处理完成后，这个值总是 -1
                            latestCell.model?.processProgress = -1
                            
                            latestCell.model?.performLog = "\(Int(self.outputImageView.image?.size.width ?? 0))x\(Int(self.outputImageView.image?.size.height ?? 0)) (\(size)) \(perfLog)"
                            latestCell.bindImageWith(data: latestCell.model)
                        }
                    }
                    
                    // 使用过一次就清除
                    self.outputImageView.image = nil
                }
                
                
            }
            
        }
        
    }
    
    /// 更新多图模型加载状态
    func updateImageLoadedStatus(_ status: Bool) async {
        self.imageLoaded = status
    }
    
    /// 用户在 toolbar 选择单张图上传时 上传状态
    func updateSingleImageUploadAndProcessStatus(_ status: Bool) async {
        self.uploadSingleImageToModel = status
    }
}

extension MBHomeViewController {
    
    public func selectedImagePreprocess(result: PickerResult?, urls: [URL], iv: UIImage) {
        
        guard let result else {
            return
        }
        
        // 选中的图片大小（KB，MB）
        self.outputImageFileSize = UInt64(result.photoAssets.first?.fileSize ?? 0)
        
        // 重置这个变量
        self.hasImageAndTextConversation = false
        
        // 最近一次选中的图片
        self.outputImageView.image = iv
        
        // 加一个处理 heic 格式的逻辑
        let tmpURL = urls.first
        
        if tmpURL?.absoluteString.lowercased().contains(".heic") == true {
            if let url = self.saveImageToCache(image: iv,
                                               fileName: "myImage_heic_\(self.outputImageFileSize)_\(arc4random()).png") {
                self.outputImageURL = url
                debugLog("选择了 .heic 格式图片。")
            }
        } else if let tmpURL = tmpURL {
            if let imageData = try? Data(contentsOf: tmpURL) {
                if imageData.isWebP {
                    if let url = self.saveImageToCache(image: iv,
                                                       fileName: "myImage_webp_\(self.outputImageFileSize)_\(arc4random()).png") {
                        self.outputImageURL = url
                        debugLog("选择了 .webp 格式图片。")
                    }
                } else {
                    self.outputImageURL = tmpURL
                }
            } else {
                self.outputImageURL = tmpURL
            }
        } else {
            self.outputImageURL = urls.first
        }
    }
    
    /// 输入栏「选择图片」按钮点击事件
    @objc public func handleChooseImage(_ sender: UIButton) {
        
        if thinking {
            self.showErrorTips("处理中，请稍等")
            return
        }
        
        // 单选 + 只能单选图片 or 视频（v2.6 的功能）
        var config = PickerConfiguration.default
        config.selectMode = .single
        
        // 不允许选择 photo 和 video
        if self.fullscreenEditor {
            // 全屏只允许选图片
            config.selectOptions = [.photo]
        } else {
            // 收起态时，可以选图片或视频
            config.selectOptions = [.photo, .video]
        }
        
        config.maximumSelectedCount = 1
        config.maximumSelectedPhotoCount = 1
        config.isSelectedOriginal = true
        
        // 方法三：
        Photo.picker(
            config
        ) { result, pickerController in
            
            // 选择完图片后的回调
            Task {
                let images: [UIImage] = try await result.objects()
                let urls: [URL] = try await result.objects()
                DispatchQueue.main.async {
                    let image = images.first
                    if let iv = image {
                        // 预先处理一下从相册中选中的图片（附带转换不支持的 .webp 格式）
                        self.selectedImagePreprocess(result: result, urls: urls, iv: iv)
                        
                        // step.1 UI 更新：先把图片显示到 UITableview 列表里
                        if (self.outputImageURL != nil) && self.outputImageView.image != nil {
                            
                            var videoURL: String? = nil
                            
                            if let photoAsset = result.photoAssets.first,
                               photoAsset.mediaType == .video {
                                // 只有 video 才加这个 url
                                videoURL = self.outputImageURL?.absoluteString
                            }
                            
                            self.appendImageDataToCellWith(image: self.outputImageView.image, imageURL: videoURL)
                            
                            // 滚动到底部
                            self.tableViewScrollToBottom()
                            
                            self.mtmdWrapperExample?.performanceLog = ""
                            
                            // 为了将来能预览视频，需要在这儿把 photoAsset 先 cache 起来
                            if let keyStr = self.outputImageURL?.absoluteString,
                               !keyStr.isEmpty,
                               let photoAsset = result.photoAssets.first,
                               photoAsset.mediaType == .video {
                                
                                self.cachedPhotoAssets[keyStr] = photoAsset
                                
                                debugLog("-->> selected.video = \(photoAsset)")
                                
                                Task {
                                    
                                    // 如果没有加载模型，要先加载多模态模型
                                    
                                    // 因为在 outputImageURL 放着是 video 的 path
                                    // 进行 video 抽帧处理
                                    if let path = self.outputImageURL?.path() {
                                        let videoURL = URL(fileURLWithPath: path)
                                        // 每 1 秒 1 帧抽取（目前只支持最多 16 帧，所以不管多长的时候，要按这个最大 16 帧来抽取）
                                        let extractor = MBVideoFrameExtractor(videoURL: videoURL, fps: 1)
                                        
                                        // 异步视频抽帧算法
                                        await extractor.extractFrames { [weak self] images in
                                            if let images = images {
                                                Task {
                                                    await self?.processVideoFrame(images: images)
                                                }
                                                // end if let images
                                            }
                                            // end extractFrames
                                        }
                                    }
                                }
                            } else {
                                // 从图片选择器选中的是普通图片，不是视频，直接到 else 里
                                if self.outputImageURL?.absoluteString.contains(".mp4") == false ||
                                    self.outputImageURL?.absoluteString.contains(".mov") == false {
                                    self.prepareLoadModelAddImageToCell()
                                }
                                
                            }
                        }
                    }
                }
            }
            
        } cancel: { pickerController in
            // image picker 取消的回调
            debugLog("-->> \(pickerController)")
        }
    }
}
