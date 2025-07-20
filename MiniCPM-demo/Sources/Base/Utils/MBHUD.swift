//
//  MBHUD.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/12/19.
//

import UIKit

/// 简单的进度提示组件，替代 MBProgressHUD
class MBHUD: UIView {
    
    // MARK: - Properties
    
    /// 提示模式
    enum Mode {
        case text
        case indeterminate
    }
    
    /// 当前模式
    var mode: Mode = .text {
        didSet {
            updateUI()
        }
    }
    
    /// 提示文本
    var label: UILabel!
    
    /// 活动指示器
    private var activityIndicator: UIActivityIndicatorView!
    
    /// 背景容器
    private var containerView: UIView!
    
    /// 自动隐藏定时器
    private var hideTimer: Timer?
    
    /// 约束引用
    private var textModeConstraints: [NSLayoutConstraint] = []
    private var indeterminateModeConstraints: [NSLayoutConstraint] = []
    
    // MARK: - Initialization
    
    override init(frame: CGRect) {
        super.init(frame: frame)
        setupUI()
    }
    
    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupUI()
    }
    
    // MARK: - UI Setup
    
    private func setupUI() {
        backgroundColor = UIColor.black.withAlphaComponent(0.7)
        layer.cornerRadius = 10
        clipsToBounds = true
        
        // 创建容器视图
        containerView = UIView()
        containerView.translatesAutoresizingMaskIntoConstraints = false
        addSubview(containerView)
        
        // 创建标签
        label = UILabel()
        label.textColor = .white
        label.font = UIFont.systemFont(ofSize: 16)
        label.textAlignment = .center
        label.numberOfLines = 0
        label.translatesAutoresizingMaskIntoConstraints = false
        containerView.addSubview(label)
        
        // 创建活动指示器
        activityIndicator = UIActivityIndicatorView(style: .large)
        activityIndicator.color = .white
        activityIndicator.translatesAutoresizingMaskIntoConstraints = false
        containerView.addSubview(activityIndicator)
        
        setupConstraints()
    }
    
    private func setupConstraints() {
        NSLayoutConstraint.activate([
            // 容器视图约束
            containerView.centerXAnchor.constraint(equalTo: centerXAnchor),
            containerView.centerYAnchor.constraint(equalTo: centerYAnchor),
            containerView.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor, constant: 20),
            containerView.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -20),
            containerView.topAnchor.constraint(greaterThanOrEqualTo: topAnchor, constant: 20),
            containerView.bottomAnchor.constraint(lessThanOrEqualTo: bottomAnchor, constant: -20),
            
            // 活动指示器约束（默认隐藏）
            activityIndicator.topAnchor.constraint(equalTo: containerView.topAnchor, constant: 15),
            activityIndicator.centerXAnchor.constraint(equalTo: containerView.centerXAnchor)
        ])
        
        // 创建并存储约束引用
        textModeConstraints = [
            label.topAnchor.constraint(equalTo: containerView.topAnchor, constant: 15),
            label.leadingAnchor.constraint(equalTo: containerView.leadingAnchor, constant: 15),
            label.trailingAnchor.constraint(equalTo: containerView.trailingAnchor, constant: -15),
            label.bottomAnchor.constraint(equalTo: containerView.bottomAnchor, constant: -15)
        ]
        
        indeterminateModeConstraints = [
            label.topAnchor.constraint(equalTo: activityIndicator.bottomAnchor, constant: 10),
            label.leadingAnchor.constraint(equalTo: containerView.leadingAnchor, constant: 15),
            label.trailingAnchor.constraint(equalTo: containerView.trailingAnchor, constant: -15),
            label.bottomAnchor.constraint(equalTo: containerView.bottomAnchor, constant: -15)
        ]
        
        // 默认激活 text 模式约束
        NSLayoutConstraint.activate(textModeConstraints)
    }
    
    private func updateUI() {
        switch mode {
        case .text:
            activityIndicator.isHidden = true
            activityIndicator.stopAnimating()
            
            // 切换到 text 模式约束
            NSLayoutConstraint.deactivate(indeterminateModeConstraints)
            NSLayoutConstraint.activate(textModeConstraints)
            
        case .indeterminate:
            activityIndicator.isHidden = false
            activityIndicator.startAnimating()
            
            // 切换到 indeterminate 模式约束
            NSLayoutConstraint.deactivate(textModeConstraints)
            NSLayoutConstraint.activate(indeterminateModeConstraints)
        }
    }
    
    // MARK: - Public Methods
    
    /// 显示进度提示
    /// - Parameters:
    ///   - view: 要显示在哪个视图上
    ///   - animated: 是否动画显示
    /// - Returns: 创建的进度提示实例
    @discardableResult
    static func showAdded(to view: UIView, animated: Bool) -> MBHUD {
        let hud = MBHUD()
        hud.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(hud)
        
        // 只设置居中和最大宽度约束，让HUD自适应内容大小
        NSLayoutConstraint.activate([
            hud.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            hud.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            hud.widthAnchor.constraint(lessThanOrEqualToConstant: 280)
        ])
        
        if animated {
            hud.alpha = 0
            UIView.animate(withDuration: 0.3) {
                hud.alpha = 1
            }
        }
        
        return hud
    }
    
    /// 隐藏进度提示
    /// - Parameters:
    ///   - animated: 是否动画隐藏
    ///   - completion: 完成回调
    func hide(animated: Bool, completion: (() -> Void)? = nil) {
        hideTimer?.invalidate()
        hideTimer = nil
        
        if animated {
            UIView.animate(withDuration: 0.3, animations: {
                self.alpha = 0
            }) { _ in
                self.removeFromSuperview()
                completion?()
            }
        } else {
            removeFromSuperview()
            completion?()
        }
    }
    
    /// 延迟隐藏
    /// - Parameters:
    ///   - animated: 是否动画隐藏
    ///   - afterDelay: 延迟时间
    func hide(animated: Bool, afterDelay delay: TimeInterval) {
        hideTimer?.invalidate()
        hideTimer = Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { [weak self] _ in
            self?.hide(animated: animated)
        }
    }
}

// MARK: - Convenience Extensions

extension MBHUD {
    
    /// 显示文本提示并自动隐藏
    /// - Parameters:
    ///   - view: 要显示在哪个视图上
    ///   - text: 提示文本
    ///   - delay: 自动隐藏延迟时间
    /// - Returns: 创建的进度提示实例
    @discardableResult
    static func showText(_ text: String, to view: UIView, hideAfter delay: TimeInterval = 2.0) -> MBHUD {
        let hud = showAdded(to: view, animated: true)
        hud.mode = .text
        hud.label.text = text
        hud.hide(animated: true, afterDelay: delay)
        return hud
    }
} 