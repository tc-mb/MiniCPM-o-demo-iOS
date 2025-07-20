//
//  MBImageTableViewCell.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/6/6.
//

import Foundation
import UIKit
import SnapKit

/// 图片 cell
class MBImageTableViewCell: UITableViewCell {
    
    /// 对应的 model
    var model: MBChatModel?
    
    var cellMargin = 24
    
    /// 点击事件
    public var onTapImageCover: ((UIImage?, String?) -> Void)?

    /// 包裹着内容的带圆角的背景 view
    lazy var containerBGView: UIView = {
        let v = UIView()
        v.layer.cornerRadius = 8
        return v
    }()
    
    /// 显示用户选中的图片
    lazy var customImageView: UIImageView = {
        let img = UIImageView()
        img.contentMode = .scaleAspectFit
        img.layer.cornerRadius = 8
        img.clipsToBounds = true
        img.layer.masksToBounds = true
        return img
    }()
    
    /// 视频类型播放指示器
    lazy var playIconImageView: UIImageView = {
        let img = UIImageView()
        img.contentMode = .scaleAspectFit
        img.clipsToBounds = true
        img.layer.masksToBounds = true
        img.image = UIImage(systemName: "play.circle.fill", withConfiguration: UIImage.SymbolConfiguration(pointSize: 48))?.withTintColor(UIColor.mb_color(with: "#FEFEFE") ?? .white, renderingMode: .alwaysOriginal)
        return img
    }()

    /// 输出日志信息，左边
    lazy var logLabel: UILabel = {
        let lb = UILabel()
        lb.textColor = UIColor.mb_color(with: "#10B601")
        lb.backgroundColor = .clear
        lb.font = .systemFont(ofSize: 12, weight: .regular)
        return lb
    }()
    
    /// 输出日志信息，右边
    lazy var logTimeConsumeLabel: UILabel = {
        let lb = UILabel()
        lb.textColor = UIColor.mb_color(with: "#10B601")
        lb.backgroundColor = .clear
        lb.font = .systemFont(ofSize: 12, weight: .regular)
        lb.textAlignment = .right
        return lb
    }()
    
    /// 进度条背景灰色
    lazy var progressBarBGView: UIView = {
       let v = UIView()
        v.backgroundColor = UIColor.mb_color(with: "#F9FAFC")
        v.layer.cornerRadius = 10/2
        v.clipsToBounds = true
        return v
    }()

    /// 进度条的进度绿色
    lazy var progressBarView: UIView = {
        let v = UIView()
         v.backgroundColor = UIColor.mb_color(with: "#51D346")
         v.layer.cornerRadius = 10/2
         return v
    }()

    
    // MARK: - init
    
    /// 初始化方法
    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        
        self.backgroundColor = .clear
        
        if MBUtils.isDeviceIPad() {
            cellMargin = 120
        }
        
        containerBGView.backgroundColor = UIColor.mb_color(with: "#FFFFFF")
        contentView.addSubview(containerBGView)
        containerBGView.snp.makeConstraints { make in
            make.right.equalTo(-cellMargin)
            make.top.equalTo(5)
            make.bottom.equalTo(-5 - 50).priority(.low)
            make.width.lessThanOrEqualTo(320 + 15)
            make.width.greaterThanOrEqualTo(240).priority(.high)
            make.height.lessThanOrEqualTo(240/* + 10 + 16 PM 要求 log 显示在 背景的外边*/)
        }
        
        // 添加自定义视图到 cell 的内容视图（contentView）中
        customImageView.isUserInteractionEnabled = true
        let tapGestureRecognizer = UITapGestureRecognizer(target: self, action: #selector(imageTapped))
        customImageView.addGestureRecognizer(tapGestureRecognizer)
        containerBGView.addSubview(customImageView)
        customImageView.snp.makeConstraints { make in
            make.edges.equalTo(containerBGView).inset(10)
        }

        // 播放按钮
        playIconImageView.isHidden = true
        containerBGView.addSubview(playIconImageView)
        playIconImageView.snp.makeConstraints { make in
            make.width.equalTo(44)
            make.height.equalTo(44)
            make.center.equalTo(customImageView)
        }

        // 显示日志用的
        contentView.addSubview(logLabel)
        logLabel.snp.makeConstraints { make in
            make.left.equalTo(containerBGView.snp.left)
            make.height.equalTo(16)
            make.top.equalTo(self.containerBGView.snp.bottom).offset(4)
        }

        // 显示耗时日志
        contentView.addSubview(logTimeConsumeLabel)
        logTimeConsumeLabel.snp.makeConstraints { make in
            make.right.equalTo(containerBGView.snp.right)
            make.height.equalTo(16)
            make.top.equalTo(self.containerBGView.snp.bottom).offset(4)
        }

        // 显示图片处理进度-背景
        contentView.addSubview(progressBarBGView)
        progressBarBGView.snp.makeConstraints { make in
            make.left.equalTo(containerBGView.snp.left)
            make.right.equalTo(-cellMargin)
            make.height.equalTo(10)
            make.top.equalTo(self.logLabel.snp.bottom).offset(4)
        }

        // 显示图片处理进度-进度条（绿色部分）
        progressBarBGView.addSubview(progressBarView)
        progressBarView.snp.makeConstraints { make in
            make.top.equalTo(1)
            make.left.equalTo(2)
            make.bottom.equalTo(-1)
            make.width.equalTo(0)
        }
    }
    
    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
    
    /// Image Cell 数据绑定
    /// - Parameters:
    ///   - data: 数据 model
    ///   - logStr: （可选）性能日志
    public func bindImageWith(data: MBChatModel?) {
        
        model = data
        
        if let img = model?.contentImage {
            customImageView.image = img
        }

        // 如果是视频格式（目前特指 mp4），显示播放按钮
        if let imgURL = model?.imageURLString,
            ( imgURL.hasSuffix(".mp4") || imgURL.hasSuffix(".mov") ) {
            self.playIconImageView.isHidden = false
        } else {
            self.playIconImageView.isHidden = true
        }

        // 显示日志
        if let log = data?.performLog, !log.isEmpty {
            let attachment = NSTextAttachment()
            attachment.image = UIImage(named: "log_icon")?.withTintColor(UIColor.mb_color(with: "#10B601") ?? .gray)
            attachment.bounds = CGRect(x: 0, y: -1, width: 10, height: 10)
            let attachmentString = NSAttributedString(attachment: attachment)
            
            // 4032x3024 (565 KB)         预处理耗时：24.4s
            // 前半部分送给 logLabel，后半部分送给 logTimeConsumeLabel
            let sepString = log.components(separatedBy: ")")
            let firstString = NSAttributedString(string: " \(sepString.first ?? ""))", attributes: [NSAttributedString.Key.foregroundColor: UIColor.mb_color(with: "#10B601") ?? .gray, NSAttributedString.Key.font: UIFont.systemFont(ofSize: 12, weight: .regular)])
            let finalString = NSMutableAttributedString()
            finalString.append(attachmentString)
            finalString.append(firstString)
            logLabel.attributedText = finalString

            // 后半部分
            let timeConsumeString = NSAttributedString(string: " \(sepString.last ?? "")", attributes: [NSAttributedString.Key.foregroundColor: UIColor.mb_color(with: "#10B601") ?? .gray, NSAttributedString.Key.font: UIFont.systemFont(ofSize: 12, weight: .regular)])
            logTimeConsumeLabel.attributedText = timeConsumeString

            if (model?.processProgress ?? 0) < 0 {
                
                self.progressBarBGView.isHidden = false
                self.progressBarView.isHidden = false

                let barWidth = self.containerBGView.frame.size.width
                self.progressBarView.snp.remakeConstraints { make in
                    make.top.equalTo(1)
                    make.left.equalTo(2)
                    make.bottom.equalTo(-1)
                    make.width.equalTo(barWidth)
                }
            } else {
                let barWidth = self.containerBGView.frame.size.width * (model?.processProgress ?? 0)
                
                if (model?.processProgress ?? 0) < 0.1, self.progressBarView.frame.width > 50 {
                    self.progressBarView.snp.remakeConstraints { make in
                        make.top.equalTo(1)
                        make.left.equalTo(2)
                        make.bottom.equalTo(-1)
                        make.width.equalTo(0)
                    }
                }
                
                self.progressBarBGView.isHidden = false
                self.progressBarView.isHidden = false

                // 加一个平滑的动画
                self.layoutIfNeeded()
                UIView.animate(withDuration: 0.2 + CGFloat(arc4random_uniform(10))/20.0) {
                    self.progressBarView.snp.remakeConstraints { make in
                        make.top.equalTo(1)
                        make.left.equalTo(2)
                        make.bottom.equalTo(-1)
                        make.width.equalTo(barWidth)
                    }
                    self.layoutIfNeeded()
                }
            }

        } else {
            logLabel.attributedText = nil
            logTimeConsumeLabel.attributedText = nil
            self.progressBarBGView.isHidden = true
            self.progressBarView.isHidden = true
            self.progressBarView.snp.remakeConstraints { make in
                make.top.equalTo(1)
                make.left.equalTo(2)
                make.bottom.equalTo(-1)
                make.width.equalTo(0)
            }
        }
    }
    
    /// 点击事件
    @objc private func imageTapped() {
        
        self.isUserInteractionEnabled = false
        DispatchQueue.main.asyncAfter(wallDeadline: .now() + 1) {
            self.isUserInteractionEnabled = true
        }
        
        onTapImageCover?(customImageView.image, model?.imageURLString)
    }

    /// 计算 cell 高度
    public static func calcCellHeight(data: MBChatModel?, viewWidth: CGFloat) -> CGFloat {
        return 240 + 36 /* more gap to bottom */ + 18/*日志 label 的高度*/ + 20/* progress bar height */
    }
}
