//
//  MBSettingsModel.swift
//  MiniCPM-demo
//
//  Created by Assistant on 2024/12/19.
//

import Foundation
import UIKit

/// 新设置页面的数据模型
class MBSettingsModel: NSObject {
    
    /// cell 用的图标
    var icon: UIImage?
    
    /// cell 标题
    var title: String?
    
    /// 右边附属 icon 样式
    var accessoryIcon: UIImage?
    
    /// 是否有选中状态：'none', 'downloaded', 'selected'
    var status: String?
    
    /// download, downloading, downloaded
    var statusString: String?
    
    /// 选中 arrow icon
    var selectedIcon: UIImage?
    
    /// 是否启用开关（用于实时理解设置）
    var isSwitchEnabled: Bool = false
    
    /// 开关状态改变的回调
    var switchValueChanged: ((Bool) -> Void)?
    
    /// 是否优先显示状态文字而不是箭头图标（用于下载状态显示）
    var shouldShowStatusText: Bool = false
} 
