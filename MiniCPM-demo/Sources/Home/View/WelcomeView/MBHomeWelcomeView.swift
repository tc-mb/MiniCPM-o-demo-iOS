//
//  MBHomeWelcomeView.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/6/8.
//

import Foundation
import UIKit
import SnapKit

/// 首页欢迎 view
class MBHomeWelcomeView: UIView {
    
    /// 点击事件
    public var onTap: ((String?) -> Void)?

    lazy var titleLabel: UILabel = {
        let lb = UILabel()
        lb.text = "Welcome to MiniCPM"
        lb.font = UIFont.systemFont(ofSize: 26, weight: .semibold)
        lb.textColor = UIColor.mb_color(with: "#333333")
        lb.textAlignment = .center
        return lb
    }()
    
    lazy var descLabel: UILabel = {
        let lb = UILabel()
        lb.font = UIFont.systemFont(ofSize: 10, weight: .regular)
        lb.textColor = UIColor.mb_color(with: "#8A8A8E")
        lb.textAlignment = .center
        lb.lineBreakMode = .byWordWrapping
        lb.numberOfLines = 2
        return lb
    }()
    
    /// tip1
    lazy var tips01ContainerView: MBHomeWelcomeTipsView = {
        let v = MBHomeWelcomeTipsView()
        v.bindWith(icon: UIImage(named: "header_tips1_icon"), title: "请描述图片中的内容。")
        return v
    }()

    /// tip2
    lazy var tips02ContainerView: MBHomeWelcomeTipsView = {
        let v = MBHomeWelcomeTipsView()
        v.bindWith(icon: UIImage(named: "header_tips2_icon"), title: "视频中有什么？")
        return v
    }()


    
    // 初始化方法，通过代码创建视图实例时会调用此方法
    override init(frame: CGRect) {
        super.init(frame: frame)
        setupView()
    }
    
    // 初始化方法，通过 Interface Builder 创建视图实例时会调用此方法
    required init?(coder aDecoder: NSCoder) {
        super.init(coder: aDecoder)
        setupView()
    }
    
    // 设置视图和其子视图的布局和样式
    private func setupView() {

        var bubbleWidth = 280
        
        if MBUtils.isDeviceIPhone() {
            bubbleWidth = 180
        }
        
        let viewMargin: CGFloat = 14

        addSubview(titleLabel)
        titleLabel.snp.makeConstraints { make in
            make.centerX.equalTo(self)
            make.height.equalTo(28)
        }
        
        let para = NSMutableParagraphStyle()
        para.maximumLineHeight = 20
        para.minimumLineHeight = 20
        para.lineSpacing = 1
        para.alignment = .center
        para.lineBreakMode = .byWordWrapping
        
        let attributes: [NSAttributedString.Key: Any] = [
            .foregroundColor: UIColor.mb_color(with: "#8A8A8E") ?? .gray,
            .font: UIFont.systemFont(ofSize: 14),
            .paragraphStyle: para
        ]
        let text = "让我协助你了解知识、获得灵感、提升效率，我可以进行多轮对话与互动、根据图片给出信息并进一步解读。"
        descLabel.attributedText = NSAttributedString(string: text, attributes: attributes)
        addSubview(descLabel)
        self.descLabel.snp.makeConstraints { make in
            make.centerX.equalTo(self)
            make.top.equalTo(titleLabel.snp.bottom).offset(6)
            if MBUtils.isDeviceIPad() {
                make.left.equalTo(160)
                make.right.equalTo(-160)
            } else {
                make.left.equalTo(24)
                make.right.equalTo(-24)
            }
            make.height.equalTo(44)
        }
        
        addSubview(tips01ContainerView)
        addSubview(tips02ContainerView)
        
        // 设置 tips01 和 tips02 的约束
        tips01ContainerView.snp.makeConstraints { make in
            if MBUtils.isDeviceIPad() {
                make.right.equalTo(self.snp_centerXWithinMargins).offset(-viewMargin)
            } else {
                make.left.equalTo(12)
            }
            make.top.equalTo(self.descLabel.snp.bottom).offset(25)
            make.height.equalTo(56)
            make.width.equalTo(bubbleWidth)
        }

        tips02ContainerView.snp.makeConstraints { make in
            if MBUtils.isDeviceIPad() {
                make.left.equalTo(tips01ContainerView.snp.right).offset(viewMargin*2)
            } else {
                make.right.equalTo(-12)
            }
            make.top.equalTo(self.descLabel.snp.bottom).offset(25)
            make.height.equalTo(56)
            make.width.equalTo(bubbleWidth)
        }
    }
    
    public func setupTapEvent(_ tapImp : ((String?) -> Void)? ) {
        self.onTap = tapImp
        
        tips01ContainerView.onTap = self.onTap
        tips02ContainerView.onTap = self.onTap

        // 隐藏质量不高的 2 个引导 view
        /*
        #if DEBUG
            tips01ContainerView.isHidden = false
            tips02ContainerView.isHidden = false
        #else
            tips01ContainerView.isHidden = true
            tips02ContainerView.isHidden = true
        #endif
        */
    }
    
    

    
}
