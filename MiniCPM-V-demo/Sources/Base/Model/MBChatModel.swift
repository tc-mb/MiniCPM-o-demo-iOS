//
//  MBChatModel.swift
//  MiniCPM-V-demo
//
//  Created by Alex on 2024/6/6.
//

import Foundation
import UIKit

enum MBChatVoteStatusCode {
    case neutral
    case voteup
    case votedown
}

/// chat model
public class MBChatModel: NSObject {
    
    /// 类型：支持 TEXT 和 IMAGE
    var type: String?
    
    /// 角色：人 or 大模型, user or llm
    var role: String?
    
    /// 文字内容
    var contentText: String?
    
    /// 性能日志
    var performLog: String?
    
    /// 多模态加载图片、clip 的总进度【0 - 1】
    var processProgress: CGFloat = 0.0
    
    /// 图片内容
    var contentImage: UIImage?
    
    /// 对应图片资源的 url.string
    var imageURLString: String?

    /// cell 的高度
    var cellHeight: CGFloat = 0.0
    
    /// 是否在每一条内容输出的底部显示调试信息
    var enableDebugLog: Bool = true
    
    /// 最后一条 LLM 输出的内容会有 toolbar【复制、重新生成、👍、🦶】，非最后一条不显示 toolbar
    var hasBottomToolbar: Bool = false
    
    /// 非最后一条 LLM 输出的内容，点击后会有悬浮的 action button【复制、👍、🦶】，滚动时消失
    var hasFloatingActionButton: Bool = false
    
    /// 赞同状态
    var voteStatus: MBChatVoteStatusCode = .neutral
    
    /// 本次输出是否被强制终止了？
    var isForceHalted = false
    
    /// 思考过程是否被折叠（默认 nil = 未设置，生成完成后自动折叠）
    var isThinkingCollapsed: Bool?
    
    /// 日志用：会话 ID
    var msgId: String?
    
    /// 日志用：消息创建时间：时间戳 【非空】（单位毫秒）
    var createTime: Double = 0.0
}
