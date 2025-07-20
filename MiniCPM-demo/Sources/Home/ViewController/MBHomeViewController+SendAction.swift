//
//  MBHomeViewController+SendAction.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/7/12.
//

import Foundation
import UIKit

/// 点击「发送」按钮，逻辑处理
extension MBHomeViewController {

    // MARK: - 多模态模型的处理逻辑
    
    /// 多模态模型的处理逻辑
    func processImageAndTextMixModeSendLogic() async {

        // 如果有录像的在处理中的视频帧，也要等
        if MBLiveCaptureVideoFrameManager.shared.capturedImageArray.count != 0 {
            self.showErrorTips("图片预处理中，请稍等再点击发送。")
            return
        }

        let inputText = textInputView.text.trimmingCharacters(in: .whitespacesAndNewlines)
        
        // 把文字也转换为 cell 放到 UITableView 上
        appendTextDataToCellWith(text: inputText, role: "user")

        // 记录用户输入（总是记录用户最后的输入）
        latestUserInputText = inputText

        // 所有这一切处理完成后，才清空用户输入的内容
        textInputView.text = ""
        sendButton.isEnabled = false
        placeholderLabel.isHidden = false
        
        // 把之前显示在 llm cell 上的 toolbar 及 popup 都隐藏掉，注意，不能把下边即将要输出显示的 llm cell 隐藏了。
        hideAllCellToolbarAndPopup()

        // append robte output text cell, prepare llm output
        appendTextDataToCellWith(text: "", role: "llm")
        
        // 滚动到底部
        tableViewScrollToBottom()
        
        // 清空输出
        mtmdWrapperExample?.outputText = ""
        mtmdWrapperExample?.performanceLog = ""
        
        // 显示暂停和继续的悬浮的按钮
        showFloatingActionViewWith(show: true)
        
        // 清空 perflog
        self.cachedImageEmbeddingPerfLog.removeAll()

        let ret = await mtmdWrapperExample?.addTextInBackground(inputText)
        
        if ret == true {
            Task {
                await mtmdWrapperExample?.startGeneration()
            }
        }
    }
}
