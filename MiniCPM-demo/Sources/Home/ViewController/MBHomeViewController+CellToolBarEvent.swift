//
//  MBHomeViewController+CellToolBarEvent.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/6/27.
//

import Foundation
import UIKit

extension MBHomeViewController {
    
    /// cell 上 toolbar & floating bar 点击事件
    public func cellToolbarClickEvent(_ model: MBChatModel?, action: String?) {
        
        if thinking {
            // 输出时不允许按钮事件
            return
        }
        
        if action == "copy" {
            if let text = model?.contentText {
                UIPasteboard.general.string = text
                self.showErrorTips("已复制", delay: 1)
            }
        } else if action == "regenerate" {
            // 重新生成
            // 建议不要删除用户的输入和之前的输出，这样可以看到对比的效果；
            self.regenerateLastOutput()
        } else if action == "voteup" {
            if model?.voteStatus == .voteup {
                self.showErrorTips("已取消", delay: 1)
            } else {
                self.showErrorTips("已赞同", delay: 1)
            }
        } else if action == "votedown" {
            if model?.voteStatus == .votedown {
                self.showErrorTips("已取消", delay: 1)
            } else {
                self.showErrorTips("已反对", delay: 1)
            }
        }
    }
    
    /// 显示输出中 底部飘出来的 赞停、继续 按钮
    public func showFloatingActionViewWith(show: Bool) {
        floatingActionView.isHidden = !show
    }
    
    // MARK: - 重新生成最后的一次输出内容
    func regenerateLastOutput() {
        textInputView.text = latestUserInputText
        handleSendText(sendButton)
    }
}
