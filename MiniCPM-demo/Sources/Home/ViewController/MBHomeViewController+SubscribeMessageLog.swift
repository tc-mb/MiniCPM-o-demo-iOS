//
//  MBHomeViewController+SubscribeMessageLog.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/6/21.
//

import Foundation
import llama

extension MBHomeViewController {
    
    /// 订阅大模型的输出-original
    public func subscriberLlamaMessageLog() {
        
        // 订阅 @Published 属性的变化
        dataSubscriber = mtmdWrapperExample?.$outputText
            .receive(on: DispatchQueue.main)   // 更新 UI 的相关操作切换到主线程
            .sink { [weak self] receivedData in
                
                // find last text cell and append text
                if self?.liveStreamVCShow == false,
                   let c = self?.dataArray.count, c > 0 {
                    if let latestCell = self?.tableView.cellForRow(at: IndexPath(row: (self?.dataArray.count ?? 0) - 1, section: 0)) as? MBTextTableViewCell {
                        
                        if latestCell.model?.role == "llm", !receivedData.isEmpty {
                            
                            // 更新输出的文本内容
                            latestCell.model?.contentText = receivedData
                            
                            // 重新计算 cell 高度
                            let cellHeight = MBTextTableViewCell.calcCellHeight(data: latestCell.model, viewWidth: self?.view.frame.width ?? 0)
                            latestCell.model?.cellHeight = cellHeight
                            
                            self?.tableViewScrollToBottom()
                        }
                        
                    }
                    
                } else {
                    
                    // cell 为空时
                    // 如果存在 live stream vc，则输出到 live stream 的 output 上
                    if let liveVC = self?.liveStreamVC {
                        
                        var formatedStr = receivedData
                        
                        // 替换 “\\n” 为 " "
                        formatedStr = formatedStr.replacingOccurrences(of: "\\n", with: "\n")
                        if !formatedStr.isEmpty {
                            liveVC.updateWithOutputLabel(str: formatedStr)
                        }
                        
                    }
                    
                }
                
            }
        
        // 订阅 performanceLog @Published 属性的变化
        perfLogSubscriber = mtmdWrapperExample?.$performanceLog
            .receive(on: DispatchQueue.main)   // 更新 UI 的相关操作切换到主线程
            .sink { [weak self] log in
                
                // 输出完成
                if !log.isEmpty {
                    self?.thinking = false
                }
                
                // 同步给 live stream vc
                if let liveVC = self?.liveStreamVC {
                    if log.contains("Done") {
                        
                        self?.thinking = true
                        
                        Task {
                            // question gap
                            let retrievedDictionary = UserDefaults.standard.dictionary(forKey: "mb_ls_presets") as? [String: [String: String]]
                            
                            // 默认等 2 秒，再抽帧
                            var gap = 2000
                            
                            if let qgap = retrievedDictionary?["qgap"] {
                                let tmp = Int(qgap["qgap"] ?? "0") ?? 0
                                gap = tmp
                            }
                            
                            debugLog("-->> 提问间隔 = \(gap) 毫秒")
                            usleep(useconds_t(gap * 1000))
                            
                            // live stream 正式完成
                            liveVC.processing = false
                            
                            // 再同步把 thinking 关了
                            self?.thinking = false
                            
                            // live stream 正式重置 embed count 为 0
                            debugLog("-->> embed, 提问完成，embedCount 重置为 0.")
                            liveVC.embeddingCount = 0
                            
                            self?.mtmdWrapperExample?.outputText = ""
                            self?.mtmdWrapperExample?.performanceLog = ""
                            
                            let time = NSDate().timeIntervalSince1970
                            debugLog("-->> $performanceLog \(time) 输出完成。")
                        }
                        
                        // eat \n
                        if let outputString = liveVC.getOutputLabelString() {
                            var formatedStr = outputString
                            while formatedStr.hasSuffix("\n") == true {
                                formatedStr = String(formatedStr.dropLast())
                            }
                            if !formatedStr.isEmpty {
                                liveVC.updateWithOutputLabel(str: formatedStr)
                            }
                        }
                    }
                }
                
                
                if let c = self?.dataArray.count, c > 0 {
                    if let latestCell = self?.tableView.cellForRow(at: IndexPath(row: (self?.dataArray.count ?? 0) - 1, section: 0)) as? MBTextTableViewCell {
                        if latestCell.model?.role == "llm" {
                            // 多模态模型 更新性能日志
                            if !log.hasPrefix("Loaded model") {
                                latestCell.model?.performLog = log
                                
                                self?.tableViewScrollToBottom()
                                
                                // 显示暂停和继续的悬浮的按钮
                                if !log.isEmpty {
                                    
                                    // 输出完才显示 toolbar
                                    latestCell.model?.hasBottomToolbar = true
                                    latestCell.model?.cellHeight = MBTextTableViewCell.calcCellHeight(data: latestCell.model, viewWidth: self?.view.frame.width ?? 0)
                                    latestCell.bindTextWith(data: latestCell.model)
                                    
                                    // 多 layout 一次
                                    latestCell.layoutIfNeeded()
                                    
                                    // 隐藏悬浮的停止生成的按钮
                                    self?.showFloatingActionViewWith(show: false)
                                    
                                    self?.mtmdWrapperExample?.outputText = ""
                                    self?.mtmdWrapperExample?.performanceLog = ""
                                }
                                
                            }
                            
                        }
                    }
                }
            }
        
    }
}
