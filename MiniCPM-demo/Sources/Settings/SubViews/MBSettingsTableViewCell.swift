//
//  MBSettingsTableViewCell.swift
//  MiniCPM-demo
//
//  Created by Assistant on 2024/12/19.
//

import Foundation
import UIKit
import SnapKit

/// 新设置页面的 Cell
class MBSettingsTableViewCell: UITableViewCell {
    
    let gap = 16
    
    private let iconImageView = UIImageView()
    private let titleLabel = UILabel()
    
    /// 当前生效的模型
    private let selectedImageView = UIImageView()
    
    /// 更多 > icon
    private let accessoryImageView = UIImageView()
    
    /// 模型状态 label
    private let statusLabel = UILabel()
    
    /// 开关控件（用于实时理解设置）
    private let switchControl = UISwitch()
    
    public var model: MBSettingsModel?
    
    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        setupViews()
    }
    
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
    
    private func setupViews() {
        // 配置 iconImageView
        contentView.backgroundColor = .white
        
        iconImageView.clipsToBounds = true
        contentView.addSubview(iconImageView)
        iconImageView.snp.makeConstraints { make in
            make.centerY.equalTo(contentView)
            make.left.equalTo(12 + gap)
            make.width.height.equalTo(22)
        }
        
        // 配置 titleLabel
        titleLabel.textColor = .black
        titleLabel.font = .systemFont(ofSize: 14, weight: .medium)
        contentView.addSubview(titleLabel)
        titleLabel.snp.makeConstraints { make in
            make.centerY.equalTo(contentView)
            make.height.equalTo(28)
            make.left.equalTo(self.iconImageView.snp.right).offset(12)
            make.right.equalTo(-120) // 为statusLabel和accessoryImageView留出更多空间
        }
        
        // 配置 accessoryImageView
        accessoryImageView.clipsToBounds = true
        contentView.addSubview(accessoryImageView)
        accessoryImageView.snp.makeConstraints { make in
            make.centerY.equalTo(contentView)
            make.width.height.equalTo(22)
            make.right.equalTo(-12 - gap)
        }
        
        // 配置 statusLabel
        statusLabel.textColor = UIColor.mb_color(with: "#007AFF") // 使用蓝色突出显示
        statusLabel.font = .systemFont(ofSize: 12, weight: .medium) // 稍微增大字体
        statusLabel.textAlignment = .right
        contentView.addSubview(statusLabel)
        statusLabel.snp.makeConstraints { make in
            make.centerY.equalTo(contentView)
            make.width.equalTo(80)
            make.right.equalTo(-44 - gap) // 为accessoryImageView留出空间
            make.height.equalTo(16)
        }
        
        // 配置 switchControl
        switchControl.onTintColor = UIColor.mb_color(with: "#007AFF")
        switchControl.addTarget(self, action: #selector(switchValueChanged), for: .valueChanged)
        contentView.addSubview(switchControl)
        switchControl.snp.makeConstraints { make in
            make.centerY.equalTo(contentView)
            make.right.equalTo(-12 - gap)
        }
    }
    
    @objc private func switchValueChanged() {
        model?.switchValueChanged?(switchControl.isOn)
    }
    
    // 配置视图的方法
    func configure(with model: MBSettingsModel?) {
        guard let model = model else {
            return
        }
        
        self.model = model
        
        // 重置所有状态
        statusLabel.text = ""
        statusLabel.isHidden = true
        accessoryImageView.image = nil
        accessoryImageView.isHidden = true
        switchControl.isHidden = true
        
        iconImageView.image = model.icon
        iconImageView.tintColor = .black
        titleLabel.text = model.title
        
        // 根据模型类型配置不同的显示
        if model.isSwitchEnabled {
            // 开关类型的 cell（如实时理解设置）
            accessoryImageView.isHidden = true
            statusLabel.isHidden = true
            switchControl.isHidden = false
            
            // 从 UserDefaults 读取开关状态
            let isEnabled = UserDefaults.standard.bool(forKey: "realtime_understanding_enabled")
            switchControl.isOn = isEnabled
        } else {
            // 普通类型的 cell（如模型选择）
            switchControl.isHidden = true
            
            // 检查是否应该优先显示状态文字（用于下载页面）
            if model.shouldShowStatusText, let statusStr = model.statusString {
                // 优先显示状态文字
                statusLabel.text = statusStr
                statusLabel.isHidden = false
                accessoryImageView.isHidden = true
                
                // 重新设置 statusLabel 的约束，让它更贴近右边距
                statusLabel.snp.remakeConstraints { make in
                    make.centerY.equalTo(contentView)
                    make.width.equalTo(80)
                    make.right.equalTo(-12 - gap) // 直接贴近右边距
                    make.height.equalTo(16)
                }
            } else if model.status == "selected" {
                // 选中状态：显示选中图标和状态文字
                debugLog("-->> Cell: 设置选中状态，statusString: \(model.statusString ?? "nil")")
                accessoryImageView.isHidden = false
                accessoryImageView.image = model.selectedIcon
                accessoryImageView.tintColor = .black
                
                // 恢复 statusLabel 的原始约束
                statusLabel.snp.remakeConstraints { make in
                    make.centerY.equalTo(contentView)
                    make.width.equalTo(80)
                    make.right.equalTo(-44 - gap) // 为accessoryImageView留出空间
                    make.height.equalTo(16)
                }
                
                if let statusStr = model.statusString, !statusStr.isEmpty {
                    statusLabel.text = statusStr
                    statusLabel.isHidden = false
                    debugLog("-->> Cell: 显示自定义状态文字: \(statusStr)")
                } else {
                    statusLabel.text = "正在使用"
                    statusLabel.isHidden = false
                    debugLog("-->> Cell: 显示默认状态文字: 正在使用")
                }
            } else if let acc = model.accessoryIcon {
                // 显示箭头图标
                accessoryImageView.image = acc
                accessoryImageView.tintColor = .black
                accessoryImageView.isHidden = false
                statusLabel.isHidden = true
                
                // 恢复 statusLabel 的原始约束
                statusLabel.snp.remakeConstraints { make in
                    make.centerY.equalTo(contentView)
                    make.width.equalTo(80)
                    make.right.equalTo(-44 - gap) // 为accessoryImageView留出空间
                    make.height.equalTo(16)
                }
            } else {
                // 默认状态：只显示箭头图标，不显示状态文字
                accessoryImageView.image = model.accessoryIcon
                accessoryImageView.tintColor = .black
                accessoryImageView.isHidden = false
                statusLabel.isHidden = true
                
                // 恢复 statusLabel 的原始约束
                statusLabel.snp.remakeConstraints { make in
                    make.centerY.equalTo(contentView)
                    make.width.equalTo(80)
                    make.right.equalTo(-44 - gap) // 为accessoryImageView留出空间
                    make.height.equalTo(16)
                }
            }
        }
    }
} 