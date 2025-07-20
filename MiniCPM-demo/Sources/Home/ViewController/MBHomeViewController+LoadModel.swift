//
//  MBHomeViewController+LoadModel.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/7/12.
//

import Foundation

extension MBHomeViewController {
    
    /// 尝试重新加载多模态模型
    func checkMultiModelLoadStatusAndLoadIt() {
    
        if self.mtmdWrapperExample?.multiModelLoadingSuccess == true {
            return
        }
        
        // 显示加载 HUD
        let hud = MBHUD.showAdded(to: self.view, animated: true)
        hud.mode = .indeterminate
        hud.label.text = "正在加载多模态模型..."
        
        Task.detached(priority: .userInitiated) {
            let modelURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(MiniCPMModelConst.model4v3b_Q4_K_M_FileName)
            
            // 立即更新状态值和 UI
            DispatchQueue.main.async {
                // 当前加载的模型为多模态（图像）模型
                if modelURL.absoluteString.contains("v263") {
                    self.currentUsingModelType = .V26MultiModel
                    self.mtmdWrapperExample?.currentUsingModelType = .V26MultiModel
                    UserDefaults.standard.setValue("V26MultiModel", forKey: "current_selected_model")
                } else if modelURL.absoluteString.contains("4v3b") {
                    self.currentUsingModelType = .V4V3BMultiModel
                    self.mtmdWrapperExample?.currentUsingModelType = .V4V3BMultiModel
                    UserDefaults.standard.setValue("V4V3BMultiModel", forKey: "current_selected_model")
                }
                
                self.updateNavTitle()
            }
            
            // 只加载一次模型
            if await self.mtmdWrapperExample?.multiModelLoadingSuccess == false {
                // part.1 加载模型
                await self.mtmdWrapperExample?.initialize()
                // 更新模型加载状态为：加载成功，maybe 不需要，因为直接选择一张图提问时，也可能要重新 load model。
                await self.updateImageLoadedStatus(true)
            }
            
            // 检查模型加载状态
            DispatchQueue.main.async {
                if let mtmdWrapper = self.mtmdWrapperExample, mtmdWrapper.multiModelLoadingSuccess == false {
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
