//
//  MBHomeWelcomeTipsView.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/6/8.
//

import Foundation
import UIKit
import SnapKit

/// 首页 header 用到的 tips view
class MBHomeWelcomeTipsView: UIView {
    
    /// 点击事件
    public var onTap: ((String?) -> Void)?

    /// icon
    lazy var iconImageView: UIImageView = {
        let img = UIImageView()
        img.contentMode = .scaleAspectFill
        img.clipsToBounds = true
        
        return img
    }()

    /// text
    lazy var titleLabel: UILabel = {
        let lb = UILabel()
        if MBUtils.isDeviceIPad() {
            lb.font = UIFont.systemFont(ofSize: 16, weight: .medium)
        } else {
            lb.font = UIFont.systemFont(ofSize: 10, weight: .medium)
        }
        lb.textColor = UIColor.mb_color(with: "#1C1C23")
        return lb
    }()
    
    /// 初始化方法，通过代码创建视图实例时会调用此方法
    override init(frame: CGRect) {
        super.init(frame: frame)
        setupView()
    }
    
    /// 初始化方法，通过 Interface Builder 创建视图实例时会调用此方法
    required init?(coder aDecoder: NSCoder) {
        super.init(coder: aDecoder)
        setupView()
    }
    
    /// 设置视图和其子视图的布局和样式
    private func setupView() {
        
        self.backgroundColor = .white
        self.layer.cornerRadius = 16
        
        addSubview(iconImageView)
        iconImageView.snp.makeConstraints { make in
            make.centerY.equalTo(self)
            make.left.equalTo(7)
            make.width.height.equalTo(40)
        }

        addSubview(titleLabel)
        titleLabel.snp.makeConstraints { make in
            make.centerY.equalTo(self)
            make.height.equalTo(18)
            make.left.equalTo(self.iconImageView.snp.right).offset(8)
        }
        
        // 增加点击事件
        self.isUserInteractionEnabled = true
        let tapGestureRecognizer = UITapGestureRecognizer(target: self, action: #selector(viewTapped))
        addGestureRecognizer(tapGestureRecognizer)
    }
    
    /// 点击事件
    @objc private func viewTapped() {
        
        self.isUserInteractionEnabled = false
        DispatchQueue.main.asyncAfter(wallDeadline: .now() + 5) {
            self.isUserInteractionEnabled = true
        }
        
        onTap?(titleLabel.text)
    }
    
    /// 数据绑定
    public func bindWith(icon: UIImage?, title: String?) {
        self.iconImageView.image = icon
        self.titleLabel.text = title
    }
    
}
