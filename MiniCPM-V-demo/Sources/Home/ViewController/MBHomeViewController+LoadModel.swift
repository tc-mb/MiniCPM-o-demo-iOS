//
//  MBHomeViewController+LoadModel.swift
//  MiniCPM-V-demo
//
//  Created by Alex on 2024/7/12.
//

import Foundation
import UIKit

extension MBHomeViewController {
    
    /// 尝试重新加载多模态模型
    func checkMultiModelLoadStatusAndLoadIt() {
    
        if self.mtmdWrapperExample?.multiModelLoadingSuccess == true {
            return
        }
        
        // 显示加载 HUD。
        // 第二行是最朴素的多行 UILabel 文案：避免 attributedText / Timer 心跳那种花哨方案。
        // 在多次切换模型反复 reset + init 的场景里，跨 main actor 写 attributedText / 长生命
        // 周期 Timer 持有 hud 会跟 vc dealloc 路径竞争，触发
        // `Cannot form weak reference to MBHomeViewController` 崩溃 + 白屏。
        // 静态多行文案在 vc 完全 attach 之后再被 alpha 动画显示出来，没有任何额外引用链，最稳。
        let hud = MBHUD.showAdded(to: self.view, animated: true)
        hud.mode = .indeterminate
        hud.label.text = "正在加载模型...\n首次启动需要解析权重，请稍候"
        
        Task.detached(priority: .userInitiated) {

            var modelURL: URL?
            var mmprojURL: URL?
            var selectedModelType: CurrentUsingModelTypeV2 = .Unknown
            
            // 判断用户在设置页选中的模型
            let lastSelectedModelString = UserDefaults.standard.value(forKey: "current_selected_model") as? String ?? ""
            if lastSelectedModelString == "V26MultiModel" {
                // V-2.6 8B 多模态模型
                modelURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(MiniCPMModelConst.modelQ4_K_MFileName)
                mmprojURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(MiniCPMModelConst.mmprojFileName)
                selectedModelType = .V26MultiModel
            } else if lastSelectedModelString == "V4MultiModel" {
                // V-4.0 4B 多模态模型
                modelURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(MiniCPMModelConst.modelv4_Q4_K_M_FileName)
                mmprojURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(MiniCPMModelConst.mmprojv4_FileName)
                selectedModelType = .V4MultiModel
            } else if lastSelectedModelString == "V46MultiModel" {
                // V-4.6 多模态模型
                modelURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(MiniCPMModelConst.modelv46_FileName)
                mmprojURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(MiniCPMModelConst.mmprojv46_FileName)
                selectedModelType = .V46MultiModel
            } else if lastSelectedModelString == "V5TextModel" {
                // MiniCPM 5 纯文本模型（无 mmproj）
                modelURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(MiniCPMModelConst.modelv5_FileName)
                selectedModelType = .V5TextModel
            }
            
            // 纯文本模型不需要 mmproj
            guard let modelURL = modelURL,
                  (selectedModelType.isTextOnly || mmprojURL != nil) else {
                DispatchQueue.main.async {
                    hud.mode = .text
                    hud.label.text = "初始化失败，请先下载模型"
                    hud.hide(animated: true, afterDelay: 3)
                }
                return
            }

            DispatchQueue.main.async {
                self.currentUsingModelType = selectedModelType
                self.mtmdWrapperExample?.currentUsingModelType = selectedModelType
            }
            
            // 加载模型
            if await self.mtmdWrapperExample?.multiModelLoadingSuccess == false {
                if selectedModelType == .V5TextModel {
                    // MiniCPM 5 纯文本模型：无 mmproj，走 text-only 初始化路径
                    await self.mtmdWrapperExample?.initializeTextOnly(modelPath: modelURL.path)
                } else if selectedModelType == .V26MultiModel {
                    // V2.6 不走 CoreML / ANE，没有冷启动文案。
                    await self.mtmdWrapperExample?.initialize(modelPath: modelURL.path, mmprojPath: mmprojURL!.path)
                } else if selectedModelType == .V4MultiModel {

                    await self.mtmdWrapperExample?.initialize()

                    // warm-up：注入一张全白图触发 mmproj 首次 forward。
                    let whiteImage = UIImage(named: "white")
                    let whiteImageData = whiteImage?.pngData()
                    let documentsPath = NSSearchPathForDirectoriesInDomains(.documentDirectory, .userDomainMask, true)[0]
                    let whiteImagePath = documentsPath.appending("/white.png")
                    try? whiteImageData?.write(to: URL(fileURLWithPath: whiteImagePath))
                    _ = await self.mtmdWrapperExample?.addImageInBackground(whiteImagePath)
                } else if selectedModelType == .V46MultiModel {
                    let coremlPath = MiniCPMV46CoreMLBootstrap.resolvedCoreMLPathInDocuments()
                    let nCtx = MBDeviceMemoryProbe.recommendedNCtx
                    await self.mtmdWrapperExample?.initialize(
                        modelPath: modelURL.path,
                        mmprojPath: mmprojURL!.path,
                        coremlPath: coremlPath,
                        nCtx: nCtx
                    )
                }

                // 更新模型加载状态为：加载成功
                await self.updateImageLoadedStatus(true)

                // 通知 C bridge 当前模型版本，用于选择正确的 assistant prompt prefix
                await self.mtmdWrapperExample?.applyModelVersion()
            }
            
            // 检查模型加载状态
            DispatchQueue.main.async {
                if let mtmdWrapper = self.mtmdWrapperExample,
                   mtmdWrapper.multiModelLoadingSuccess == false {
                    // 模型加载失败，显示错误提示
                    hud.mode = .text
                    hud.label.text = "初始化失败，请先下载模型"
                    hud.hide(animated: true, afterDelay: 3)
                } else {
                    // 模型加载成功，隐藏 HUD
                    hud.mode = .text
                    hud.label.text = "初始化完成"
                    hud.hide(animated: true, afterDelay: 2)
                }
            }
        }
    }

}
