//
//  MBPresetQuestionView.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/7/24.
//

import Foundation
import UIKit

/// Live Stream 时预置的问题列表
class MBPresetQuestionView: UIView {
    
    /// 点击事件
    public var onTap: ((String?) -> Void)?
    
    /// 当前选中的按钮的标题
    public var currentSelectedButtonTitle: String?
    
    /// 当前选中的额外的提示词
    public var currentExtraPrompt: String?
    
    lazy var customBackgroundView: UIView = {
        let v = UIView()
        v.backgroundColor = UIColor.mb_color(with: "#333333")?.withAlphaComponent(0.2)
        v.layer.cornerRadius = 4
        return v
    }()

    /// 当前间隔时间
    public var currentGapTitle: String?

    /// 预先设置好的问题
    lazy var presetQuestion01Button: UIButton = {
       let btn = UIButton()
        btn.setTitle("此刻发生了什么？", for: UIControl.State())
        btn.addTarget(self, action: #selector(handleTapButton), for: .touchUpInside)
        btn.backgroundColor = UIColor.mb_color(with: "#333333")?.withAlphaComponent(0.4)
        btn.layer.cornerRadius = 8
        
        if MBUtils.isDeviceIPhone() {
            btn.titleLabel?.font = UIFont.systemFont(ofSize: 12, weight: .medium)
        } else {
            btn.titleLabel?.font = UIFont.systemFont(ofSize: 20, weight: .medium)
        }
        
        btn.setTitleColor(.white, for: UIControl.State())
        
        btn.tag = 1001
        
        return btn
    }()

    /// 预先设置好的问题
    lazy var presetQuestion02Button: UIButton = {
       let btn = UIButton()
        btn.setTitle("视频的背景环境在哪里？", for: UIControl.State())
        btn.addTarget(self, action: #selector(handleTapButton), for: .touchUpInside)
        btn.backgroundColor = UIColor.mb_color(with: "#333333")?.withAlphaComponent(0.4)
        btn.layer.cornerRadius = 8
        
        if MBUtils.isDeviceIPhone() {
            btn.titleLabel?.font = UIFont.systemFont(ofSize: 12, weight: .medium)
        } else {
            btn.titleLabel?.font = UIFont.systemFont(ofSize: 20, weight: .medium)
        }
        
        btn.setTitleColor(.white, for: UIControl.State())
        
        btn.tag = 1002
        
        return btn
    }()

    /// 预先设置好的问题
    lazy var presetQuestion03Button: UIButton = {
       let btn = UIButton()
        btn.setTitle("视频中有哪些物体？", for: UIControl.State())
        btn.addTarget(self, action: #selector(handleTapButton), for: .touchUpInside)
        btn.backgroundColor = UIColor.mb_color(with: "#333333")?.withAlphaComponent(0.4)
        btn.layer.cornerRadius = 8
        
        if MBUtils.isDeviceIPhone() {
            btn.titleLabel?.font = UIFont.systemFont(ofSize: 12, weight: .medium)
        } else {
            btn.titleLabel?.font = UIFont.systemFont(ofSize: 20, weight: .medium)
        }
        
        btn.setTitleColor(.white, for: UIControl.State())
        
        btn.tag = 1003
        
        return btn
    }()

    /// 预先设置好的问题
    lazy var presetQuestion04Button: UIButton = {
       let btn = UIButton()
        btn.setTitle("视频是在室内还是室外？", for: UIControl.State())
        btn.addTarget(self, action: #selector(handleTapButton), for: .touchUpInside)
        btn.backgroundColor = UIColor.mb_color(with: "#333333")?.withAlphaComponent(0.4)
        btn.layer.cornerRadius = 8
        
        if MBUtils.isDeviceIPhone() {
            btn.titleLabel?.font = UIFont.systemFont(ofSize: 12, weight: .medium)
        } else {
            btn.titleLabel?.font = UIFont.systemFont(ofSize: 20, weight: .medium)
        }
        
        btn.setTitleColor(.white, for: UIControl.State())
        
        btn.tag = 1004
        
        return btn
    }()

    /// q1-4 扩展的 prompt
    var presetQuestion01Ext: String?
    var presetQuestion02Ext: String?
    var presetQuestion03Ext: String?
    var presetQuestion04Ext: String?
    
    // MARK: - view lifecycle

    /// 初始化方法，通过代码创建视图实例时会调用此方法
    override init(frame: CGRect) {
        super.init(frame: frame)
        setupView()
        
        loadData()
    }
    
    /// 初始化方法，通过 Interface Builder 创建视图实例时会调用此方法
    required init?(coder aDecoder: NSCoder) {
        super.init(coder: aDecoder)
        setupView()
        
        loadData()
    }
    
    /// 设置视图和其子视图的布局和样式
    private func setupView() {
        
        self.addSubview(customBackgroundView)
        customBackgroundView.snp.makeConstraints { make in
            make.top.equalTo(44)
            make.left.right.equalToSuperview()
            make.bottom.equalToSuperview()
        }
        
        var buttonHeight = 30
        
        if MBUtils.isDeviceIPad() {
            buttonHeight = 60
        }
        
        // 预置的问题列表
        customBackgroundView.addSubview(presetQuestion01Button)
        presetQuestion01Button.snp.makeConstraints { make in
            make.left.equalTo(12)
            make.top.equalTo(12)
            make.height.equalTo(buttonHeight)
            make.right.equalTo(-12)
        }
        
        customBackgroundView.addSubview(presetQuestion02Button)
        presetQuestion02Button.snp.makeConstraints { make in
            make.left.equalTo(12)
            make.top.equalTo(presetQuestion01Button.snp.bottom).offset(12)
            make.height.equalTo(buttonHeight)
            make.right.equalTo(-12)
        }

        customBackgroundView.addSubview(presetQuestion03Button)
        presetQuestion03Button.snp.makeConstraints { make in
            make.left.equalTo(12)
            make.top.equalTo(presetQuestion02Button.snp.bottom).offset(12)
            make.height.equalTo(buttonHeight)
            make.right.equalTo(-12)
        }

        customBackgroundView.addSubview(presetQuestion04Button)
        presetQuestion04Button.snp.makeConstraints { make in
            make.left.equalTo(12)
            make.top.equalTo(presetQuestion03Button.snp.bottom).offset(12)
            make.height.equalTo(buttonHeight)
            make.right.equalTo(-12)
        }
        
        // 默认选中第一个 preset question
        currentSelectedButtonTitle = presetQuestion01Button.titleLabel?.text
        presetQuestion01Button.setTitleColor(UIColor.mb_color(with: "#F8CB58"), for: UIControl.State())
        
        // 默认使用抽 2 帧
        currentGapTitle = "1"
    }
    
    /// 切换不同的问题
    @objc func handleTapButton(_ btn: UIButton) {

        presetQuestion01Button.setTitleColor(UIColor.mb_color(with: "#FFFFFF"), for: UIControl.State())
        presetQuestion02Button.setTitleColor(UIColor.mb_color(with: "#FFFFFF"), for: UIControl.State())
        presetQuestion03Button.setTitleColor(UIColor.mb_color(with: "#FFFFFF"), for: UIControl.State())
        presetQuestion04Button.setTitleColor(UIColor.mb_color(with: "#FFFFFF"), for: UIControl.State())

        // 当前选中
        currentSelectedButtonTitle = btn.titleLabel?.text
        btn.setTitleColor(UIColor.mb_color(with: "#F8CB58"), for: UIControl.State())
        
        // 更新当前的 extra prompt
        switch btn.tag {
        case 1001:
            currentExtraPrompt = presetQuestion01Ext
        case 1002:
            currentExtraPrompt = presetQuestion02Ext
        case 1003:
            currentExtraPrompt = presetQuestion03Ext
        case 1004:
            currentExtraPrompt = presetQuestion04Ext
        default:
            currentExtraPrompt = presetQuestion01Ext
        }
    }
}

extension MBPresetQuestionView {
    
    // 加载数据
    func loadData() {
        
        let userDefaultsKey = "mb_ls_presets"

        if let retrievedDictionary = UserDefaults.standard.dictionary(forKey: userDefaultsKey) as? [String: [String: String]] {
            
            presetQuestion01Button.setTitle("此刻发生了什么？", for: UIControl.State())
            if let q = retrievedDictionary["q1"],
               let t = q["q"],
               !t.isEmpty {
                presetQuestion01Button.setTitle(t, for: UIControl.State())
                
                // 更新第一次
                currentSelectedButtonTitle = t
            }
            
            presetQuestion02Button.setTitle("视频的背景环境在哪里？", for: UIControl.State())
            if let q = retrievedDictionary["q2"],
               let t = q["q"],
               !t.isEmpty {
                presetQuestion02Button.setTitle(t, for: UIControl.State())
            }

            presetQuestion03Button.setTitle("视频中有哪些物体？", for: UIControl.State())
            if let q = retrievedDictionary["q3"],
               let t = q["q"],
               !t.isEmpty {
                presetQuestion03Button.setTitle(t, for: UIControl.State())
            }

            presetQuestion04Button.setTitle("视频是在室内还是室外？", for: UIControl.State())
            if let q = retrievedDictionary["q4"],
               let t = q["q"],
               !t.isEmpty {
                presetQuestion04Button.setTitle(t, for: UIControl.State())
            }

            
            let defaultExtStr = ""
            presetQuestion01Ext = defaultExtStr
            presetQuestion02Ext = defaultExtStr
            presetQuestion03Ext = defaultExtStr
            presetQuestion04Ext = defaultExtStr
            
            if let q = retrievedDictionary["q1"],
               let e = q["e"],
               !e.isEmpty {
                presetQuestion01Ext = e
                
                // 更新第一次
                currentExtraPrompt = e
            }
            
            if let q = retrievedDictionary["q2"],
               let e = q["e"],
               !e.isEmpty {
                presetQuestion02Ext = e
            }

            if let q = retrievedDictionary["q3"],
               let e = q["e"],
               !e.isEmpty {
                presetQuestion03Ext = e
            }

            if let q = retrievedDictionary["q4"],
               let e = q["e"],
               !e.isEmpty {
                presetQuestion04Ext = e
            }
            
            // 每秒抽多少帧
            if let drawFPSDict = retrievedDictionary["dfps"],
               let dfps = drawFPSDict["dfps"] {
                currentGapTitle = "\((Int(dfps) ?? 0) + 1)"
            } else {
                // 默认选中 1 帧那个选项
                currentGapTitle = "1"
            }
            
        }
    }
    
}
