//
//  MBRealtimeUnderstandingViewController.swift
//  MiniCPM-demo
//
//  Created by Assistant on 2024/12/19.
//

import Foundation
import UIKit
import SnapKit

/// 配置实时理解设置 VC
class MBRealtimeUnderstandingViewController: UIViewController {
    
    // MARK: - 属性
    let userDefaultsKey = "mb_ls_presets"
    
    // MARK: - 子 view
    
    /// 可滚动的容器，把 contentSize 调大
    lazy var containerScrollView: UIScrollView = {
        let v = UIScrollView()
        v.backgroundColor = UIColor.mb_color(with: "#ffffff")
        v.showsVerticalScrollIndicator = true
        v.showsHorizontalScrollIndicator = false
        v.bounces = true
        v.alwaysBounceVertical = true
        return v
    }()
    
    // question 1
    
    lazy var question1Label: UILabel = {
        let lb = UILabel()
        lb.text = "问题选项"
        lb.font = UIFont.systemFont(ofSize: 16, weight: .medium)
        lb.textColor = UIColor.mb_color(with: "#000000")
        lb.textAlignment = .left
        return lb
    }()
    
    lazy var question1LimitLabel: UILabel = {
        let lb = UILabel()
        lb.text = "实际 Prompt"
        lb.font = UIFont.systemFont(ofSize: 16, weight: .medium)
        lb.textColor = UIColor.mb_color(with: "#000000")
        lb.textAlignment = .left
        return lb
    }()
    
    lazy var question1TextView: UITextView = {
        let tv = UITextView()
        tv.font = UIFont.systemFont(ofSize: 16)
        tv.textContainerInset = UIEdgeInsets(top: 12, left: 12, bottom: 12, right: 12)
        tv.textColor = .black
        tv.backgroundColor = .white
        tv.layer.cornerRadius = 8
        tv.layer.masksToBounds = true
        return tv
    }()
    
    lazy var question1LimitTextView: UITextView = {
        let tv = UITextView()
        tv.font = UIFont.systemFont(ofSize: 16)
        tv.textContainerInset = UIEdgeInsets(top: 12, left: 12, bottom: 12, right: 12)
        tv.textColor = .black
        tv.backgroundColor = .white
        tv.layer.cornerRadius = 8
        tv.layer.masksToBounds = true
        return tv
    }()
    
    /// 提问间隔 提示 label
    lazy var questionGapLabel: UILabel = {
        let lb = UILabel()
        lb.numberOfLines = 2
        lb.text = "提问间隔(0-10000ms)"
        lb.font = UIFont.systemFont(ofSize: 16, weight: .regular)
        lb.textColor = UIColor.mb_color(with: "#000000")
        lb.textAlignment = .left
        return lb
    }()
    
    /// 提问间隔 输入框
    lazy var questionGapTextField: UITextField = {
        let tv = UITextField()
        tv.font = UIFont.systemFont(ofSize: 16)
        tv.textColor = .black
        tv.backgroundColor = UIColor.mb_color(with: "#F0F0F0")
        tv.layer.cornerRadius = 8
        tv.layer.masksToBounds = true
        tv.leftView = UIView(frame: CGRect(x: 0, y: 0, width: 12, height: 0))
        tv.leftViewMode = .always
        tv.delegate = self
        return tv
    }()
    
    /// 提问抽帧数 label
    lazy var drawFramePerSecondLabel: UILabel = {
        let lb = UILabel()
        lb.text = "提问抽帧数"
        lb.font = UIFont.systemFont(ofSize: 16, weight: .medium)
        lb.textColor = UIColor.mb_color(with: "#000000")
        lb.textAlignment = .left
        return lb
    }()
    
    // 抽帧 menu 替换为 UITextField + UIPickerView
    lazy var drawFPSPickerTextField: UITextField = {
        let tf = UITextField()
        tf.font = UIFont.systemFont(ofSize: 16)
        tf.textColor = .black
        tf.backgroundColor = UIColor.mb_color(with: "#F0F0F0")
        tf.layer.cornerRadius = 8
        tf.layer.masksToBounds = true
        tf.leftView = UIView(frame: CGRect(x: 0, y: 0, width: 12, height: 0))
        tf.leftViewMode = .always
        tf.tintColor = .clear // 不显示光标
        tf.textAlignment = .left
        // 添加右侧三角形指示器
        let indicator = UIImageView(image: drawDownArrowImage())
        indicator.contentMode = .scaleAspectFit
        indicator.frame = CGRect(x: 0, y: 0, width: 24, height: 24)
        tf.rightView = indicator
        tf.rightViewMode = .always
        return tf
    }()
    
    let drawFPSOptions = ["1", "2", "3", "4", "5"]
    lazy var drawFPSPickerView: UIPickerView = {
        let picker = UIPickerView()
        picker.delegate = self
        picker.dataSource = self
        return picker
    }()
    
    /// 保存按钮
    lazy var confirmButton: UIButton = {
        let btn = UIButton()
        btn.setTitle("保存", for: .normal)
        btn.setTitleColor(.white, for: .normal)
        btn.titleLabel?.font = UIFont.systemFont(ofSize: 18, weight: .semibold)
        btn.addTarget(self, action: #selector(handleTapConfirmButton), for: .touchUpInside)
        btn.layer.cornerRadius = 12
        btn.clipsToBounds = true
        return btn
    }()

    // MARK: - view life cycle
    
    
    override func viewDidLoad() {
        super.viewDidLoad()
        
        self.view.backgroundColor = .white
        
        setupNav()
        
        setupSubViews()
        
        addTapGesture()
        
        setupKeyboardHandling()
    }
    
    deinit {
        NotificationCenter.default.removeObserver(self)
    }
    
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
    }
    
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        
        // 确保 scrollView 有足够的内容高度
        updateScrollViewContentSize()
    }
    
    private func updateScrollViewContentSize() {
        // 计算所有元素的总高度
        let topMargin: CGFloat = 20
        let sectionGap: CGFloat = 30
        let elementGap: CGFloat = 15
        
        let question1LabelHeight: CGFloat = 30
        let question1TextViewHeight: CGFloat = 80
        let question1LimitLabelHeight: CGFloat = 30
        let question1LimitTextViewHeight: CGFloat = 120
        let questionGapLabelHeight: CGFloat = 50
        let questionGapTextFieldHeight: CGFloat = 50
        let drawFramePerSecondLabelHeight: CGFloat = 30
        let drawFPSPickerTextFieldHeight: CGFloat = 50
        let confirmButtonHeight: CGFloat = 50
        let bottomMargin: CGFloat = 50
        
        let totalHeight = topMargin + 
                         question1LabelHeight + elementGap + question1TextViewHeight + sectionGap +
                         question1LimitLabelHeight + elementGap + question1LimitTextViewHeight + sectionGap +
                         questionGapLabelHeight + elementGap + questionGapTextFieldHeight + sectionGap +
                         drawFramePerSecondLabelHeight + elementGap + drawFPSPickerTextFieldHeight + sectionGap + 20 +
                         confirmButtonHeight + bottomMargin
        
        // 为键盘预留额外空间
        let keyboardExtraSpace: CGFloat = 400
        let finalHeight = max(totalHeight + keyboardExtraSpace, self.view.frame.height + 300)
        
        containerScrollView.contentSize = CGSize(width: self.view.frame.width, height: finalHeight)
        
        // 调试输出
        print("ScrollView contentSize: \(containerScrollView.contentSize)")
        print("View frame: \(self.view.frame)")
        print("Calculated height: \(finalHeight)")
    }
    
    // MARK: - 创建 子 view
    
    func setupNav() {
        
        self.navigationController?.setNavigationBackgroundColor(UIColor.mb_color(with: "#FFFFFF") ?? .white)
        
        self.title = "实时理解设置"
        let titleDict: [NSAttributedString.Key : Any] = [NSAttributedString.Key.foregroundColor: UIColor.black]
        self.navigationController?.navigationBar.titleTextAttributes = titleDict
        
        // 返回按钮
        let img = UIImage(systemName: "chevron.left")
        let leftNavIcon = UIBarButtonItem(image: img,
                                          style: .plain,
                                          target: self,
                                          action: #selector(handleLeftNavIcon))
        leftNavIcon.tintColor = .black
        self.navigationItem.leftBarButtonItem = leftNavIcon
        
        // 删除所有
        var resetImg = UIImage(systemName: "eraser.fill")?.withTintColor(.red, renderingMode: .alwaysTemplate)
        resetImg = resetImg?.mb_imageCompressForWidth(resetImg ?? UIImage(), targetWidth: 22)
        let rightNavIcon = UIBarButtonItem(image: resetImg,
                                           style: .plain,
                                           target: self,
                                           action: #selector(handleResetNavIcon))
        self.navigationItem.rightBarButtonItem = rightNavIcon
        self.navigationItem.rightBarButtonItem?.tintColor = .red
    }
    
    func setupSubViews() {
        
        let topMargin = 20
        let leftMargin = 20
        let rightMargin = 20
        let sectionGap = 20
        let elementGap = 10
        
        // 可滚动容器，把其它 子 view 放在这个容器里
        view.addSubview(containerScrollView)
        containerScrollView.snp.makeConstraints { make in
            make.left.right.equalToSuperview()
            make.top.bottom.equalToSuperview()
        }
        
        // 设置背景色
        question1TextView.backgroundColor = UIColor.mb_color(with: "#F0F0F0")
        question1LimitTextView.backgroundColor = UIColor.mb_color(with: "#F0F0F0")
        questionGapTextField.backgroundColor = UIColor.mb_color(with: "#F0F0F0")
        
        // 1. 问题选项
        containerScrollView.addSubview(question1Label)
        question1Label.snp.makeConstraints { make in
            make.left.equalTo(containerScrollView).offset(leftMargin)
            make.top.equalTo(containerScrollView).offset(topMargin)
            make.height.equalTo(30)
            make.width.equalTo(120)
        }
        
        containerScrollView.addSubview(question1TextView)
        question1TextView.snp.makeConstraints { make in
            make.top.equalTo(question1Label.snp.bottom).offset(elementGap)
            make.left.equalTo(containerScrollView.snp.left).offset(leftMargin)
            make.width.equalTo(containerScrollView.snp.width).offset(-leftMargin - rightMargin)
            make.height.equalTo(48)
        }

        
        // 2. 实际 Prompt
        containerScrollView.addSubview(question1LimitLabel)
        question1LimitLabel.snp.makeConstraints { make in
            make.left.equalTo(containerScrollView).offset(leftMargin)
            make.top.equalTo(question1TextView.snp.bottom).offset(sectionGap)
            make.height.equalTo(30)
            make.width.equalTo(120)
        }
        
        containerScrollView.addSubview(question1LimitTextView)
        question1LimitTextView.snp.makeConstraints { make in
            make.top.equalTo(question1LimitLabel.snp.bottom).offset(elementGap)
            make.left.equalTo(containerScrollView.snp.left).offset(leftMargin)
            make.width.equalTo(containerScrollView.snp.width).offset(-leftMargin - rightMargin)
            make.height.equalTo(240)
        }

        
        // 3. 提问间隔
        containerScrollView.addSubview(questionGapLabel)
        questionGapLabel.snp.makeConstraints { make in
            make.left.equalTo(containerScrollView).offset(leftMargin)
            make.top.equalTo(question1LimitTextView.snp.bottom).offset(sectionGap)
            make.height.equalTo(50)
            make.width.equalTo(200)
        }
        
        containerScrollView.addSubview(questionGapTextField)
        questionGapTextField.backgroundColor = UIColor.mb_color(with: "#F0F0F0")
        questionGapTextField.snp.makeConstraints { make in
            make.top.equalTo(questionGapLabel.snp.bottom).offset(elementGap)
            make.left.equalTo(containerScrollView.snp.left).offset(leftMargin)
            make.width.equalTo(containerScrollView.snp.width).offset(-leftMargin - rightMargin)
            make.height.equalTo(50)
        }

        
        // 4. 提问抽帧数
        containerScrollView.addSubview(drawFramePerSecondLabel)
        drawFramePerSecondLabel.snp.makeConstraints { make in
            make.left.equalTo(containerScrollView).offset(leftMargin)
            make.top.equalTo(questionGapTextField.snp.bottom).offset(sectionGap)
            make.height.equalTo(30)
            make.width.equalTo(120)
        }
        // 替换 DropDown 为 PickerTextField
        containerScrollView.addSubview(drawFPSPickerTextField)
        drawFPSPickerTextField.snp.makeConstraints { make in
            make.top.equalTo(drawFramePerSecondLabel.snp.bottom).offset(elementGap)
            make.left.equalTo(containerScrollView.snp.left).offset(leftMargin)
            make.width.equalTo(containerScrollView.snp.width).offset(-leftMargin - rightMargin)
            make.height.equalTo(50)
        }
        drawFPSPickerTextField.inputView = drawFPSPickerView
        drawFPSPickerTextField.text = drawFPSOptions[0] // 默认显示第一个
        
        // 5. 保存按钮
        confirmButton.backgroundColor = UIColor.mb_color(with: "#007AFF")
        containerScrollView.addSubview(confirmButton)
        confirmButton.snp.makeConstraints { make in
            make.centerX.equalTo(containerScrollView)
            make.top.equalTo(drawFPSPickerTextField.snp.bottom).offset(sectionGap + 20)
            make.width.equalTo(200)
            make.height.equalTo(50)
        }
        
        // 加载数据
        loadData()
        
        // 确保 scrollView 有正确的内容大小
        DispatchQueue.main.async {
            self.updateScrollViewContentSize()
        }
    }
    
    func addTapGesture() {
        // 增加点击事件
        containerScrollView.isUserInteractionEnabled = true
        let tapGestureRecognizer = UITapGestureRecognizer(target: self, action: #selector(viewTapped))
        containerScrollView.addGestureRecognizer(tapGestureRecognizer)
    }
    
    func setupKeyboardHandling() {
        // 监听键盘显示和隐藏事件
        NotificationCenter.default.addObserver(self, selector: #selector(keyboardWillShow), name: UIResponder.keyboardWillShowNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(keyboardWillHide), name: UIResponder.keyboardWillHideNotification, object: nil)
    }
    
    // MARK: - 事件响应
    
    @objc func handleLeftNavIcon() {
        self.navigationController?.popViewController(animated: true)
    }
    
    /// 点击事件
    @objc private func viewTapped() {
        question1TextView.resignFirstResponder()
        question1LimitTextView.resignFirstResponder()
        questionGapTextField.resignFirstResponder()
    }
    
    @objc private func keyboardWillShow(notification: NSNotification) {
        guard let keyboardFrame = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect,
              let duration = notification.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double else { return }
        
        let keyboardHeight = keyboardFrame.height
        print("Keyboard will show, height: \(keyboardHeight)")
        
        // 设置 contentInset 为键盘高度
        let contentInsets = UIEdgeInsets(top: 0, left: 0, bottom: keyboardHeight, right: 0)
        
        UIView.animate(withDuration: duration) {
            self.containerScrollView.contentInset = contentInsets
            self.containerScrollView.scrollIndicatorInsets = contentInsets
            print("ContentInset set to: \(self.containerScrollView.contentInset)")
        }
        
        // 延迟滚动，确保 contentInset 已经设置
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
            self.scrollToActiveTextField()
        }
    }
    
    @objc private func keyboardWillHide(notification: NSNotification) {
        guard let duration = notification.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double else { return }
        
        UIView.animate(withDuration: duration) {
            self.containerScrollView.contentInset = UIEdgeInsets.zero
            self.containerScrollView.scrollIndicatorInsets = UIEdgeInsets.zero
        }
    }
    
    private func scrollToActiveTextField() {
        var activeView: UIView?
        
        if question1TextView.isFirstResponder {
            activeView = question1TextView
            print("Active view: question1TextView")
        } else if question1LimitTextView.isFirstResponder {
            activeView = question1LimitTextView
            print("Active view: question1LimitTextView")
        } else if questionGapTextField.isFirstResponder {
            activeView = questionGapTextField
            print("Active view: questionGapTextField")
        }
        
        guard let activeView = activeView else { 
            print("No active view found")
            return 
        }
        
        // 计算活跃视图在 scrollView 中的位置
        let activeViewFrame = activeView.convert(activeView.bounds, to: containerScrollView)
        let scrollViewFrame = containerScrollView.frame
        
        print("Active view frame in scrollView: \(activeViewFrame)")
        print("ScrollView frame: \(scrollViewFrame)")
        print("Current contentInset: \(containerScrollView.contentInset)")
        
        // 计算需要滚动的偏移量，确保输入框在键盘上方有足够空间
        let keyboardOffset: CGFloat = 20 // 键盘上方留20px空间
        let targetY = activeViewFrame.maxY + keyboardOffset
        
        print("Target Y: \(targetY)")
        print("Available height: \(scrollViewFrame.height - containerScrollView.contentInset.bottom)")
        
        if targetY > scrollViewFrame.height - containerScrollView.contentInset.bottom {
            let offsetY = targetY - scrollViewFrame.height + containerScrollView.contentInset.bottom
            let newContentOffset = CGPoint(x: 0, y: offsetY)
            print("Scrolling to offset: \(newContentOffset)")
            containerScrollView.setContentOffset(newContentOffset, animated: true)
        } else {
            print("No need to scroll")
        }
    }
    
    @objc public func handleTapConfirmButton(_ sender: UIButton) {
        
        // save to userdefaults
        var myDictionary = [String: [String: String]]()
        
        myDictionary["q1"] = [
            "q": question1TextView.text ?? "",
            "e": question1LimitTextView.text ?? ""
        ]

        // 提问间隔
        if let qgap = questionGapTextField.text,
           !qgap.isEmpty {
            let gapInt = Int(qgap) ?? 0
            if gapInt < 0 || gapInt > 10000 {
                let hud = MBHUD.showAdded(to: self.view, animated: true)
                hud.mode = .text
                hud.label.text = "错误"
                hud.hide(animated: true, afterDelay: 2)
                return
            }
            
            myDictionary["qgap"] = ["qgap": questionGapTextField.text ?? ""]
        }
        
        // 抽帧数量
        if let selectedText = drawFPSPickerTextField.text, let idx = drawFPSOptions.firstIndex(of: selectedText) {
            myDictionary["dfps"] = ["dfps": "\(idx)"]
        }
        
        UserDefaults.standard.set(myDictionary, forKey: userDefaultsKey)
        
        let hud = MBHUD.showAdded(to: self.view, animated: true)
        hud.mode = .text
        hud.label.text = "已保存"
        hud.hide(animated: true, afterDelay: 2)
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            self.navigationController?.popViewController(animated: true)
        }
    }
    
    @objc func handleResetNavIcon() {
        let message = NSLocalizedString("删除所有已经输入的内容？",
                                        comment: "Delete all inputed texts.")
        
        let alertController = UIAlertController(title: "提示",
                                                message: message,
                                                preferredStyle: .alert)
        
        alertController.addAction(UIAlertAction(title: NSLocalizedString("取消", comment: "Cancel"),
                                                style: .cancel,
                                                handler: nil))
        
        alertController.addAction(UIAlertAction(title: NSLocalizedString("删除",
                                                                         comment: "delete"),
                                                style: .destructive,
                                                handler: { [weak self] action in
            
            guard let self else {
                return
            }
            
            UserDefaults.standard.removeObject(forKey: self.userDefaultsKey)
            
            // 清空数据
            question1TextView.text = ""
            question1LimitTextView.text = ""
            questionGapTextField.text = "2000"
            drawFPSPickerTextField.text = drawFPSOptions[0]
            
            // 弹 hud
            let hud = MBHUD.showAdded(to: self.view, animated: true)
            hud.mode = .text
            hud.label.text = "已删除"
            hud.hide(animated: true, afterDelay: 2)
            
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                self.navigationController?.popViewController(animated: true)
            }
        }))
        
        self.present(alertController, animated: true, completion: nil)
        
    }
    
    // 加载数据
    func loadData() {
        if let retrievedDictionary = UserDefaults.standard.dictionary(forKey: userDefaultsKey) as? [String: [String: String]] {
            
            if let q1 = retrievedDictionary["q1"] {
                question1TextView.text = q1["q"]
                question1LimitTextView.text = q1["e"]
            }

            if let qgap = retrievedDictionary["qgap"] {
                questionGapTextField.text = qgap["qgap"]
            } else {
                questionGapTextField.text = "2000"
            }
            
            // 每毫秒抽多少帧
            if let drawFPSDict = retrievedDictionary["dfps"],
               let dfps = drawFPSDict["dfps"],
               let idx = Int(dfps), idx >= 0, idx < drawFPSOptions.count {
                drawFPSPickerTextField.text = drawFPSOptions[idx]
            } else {
                drawFPSPickerTextField.text = drawFPSOptions[0]
            }
            
        } else {
            // 每毫秒抽多少帧
            questionGapTextField.text = "1000"
            
            // 默认选中 1 帧那个选项
            drawFPSPickerTextField.text = drawFPSOptions[0]
        }
        
    }
    
    // MARK: - 箭头位置调整
    
    private func adjustArrowPosition() {
        // 延迟执行，确保视图已经布局完成
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
            // 获取 rightView 中的箭头容器
            if let rightView = self.drawFPSPickerTextField.rightView,
               let arrowContainerView = rightView.subviews.first {
                // 遍历箭头容器中的子视图，找到箭头
                for subview in arrowContainerView.subviews {
                    // 检查是否是箭头视图（通过检查是否有 shapeLayer 属性）
                    if subview.layer.sublayers?.contains(where: { $0 is CAShapeLayer }) == true {
                        // 将箭头往左移动20pt
                        subview.frame.origin.x -= 20
                        break
                    }
                }
            }
        }
    }
    
    // 在类内添加生成三角形图片的方法
    private func drawDownArrowImage() -> UIImage? {
        let size = CGSize(width: 16, height: 16)
        UIGraphicsBeginImageContextWithOptions(size, false, 0)
        guard let context = UIGraphicsGetCurrentContext() else { return nil }
        context.setFillColor(UIColor.lightGray.cgColor)
        context.move(to: CGPoint(x: 2, y: 5))
        context.addLine(to: CGPoint(x: size.width/2, y: size.height-3))
        context.addLine(to: CGPoint(x: size.width-2, y: 5))
        context.closePath()
        context.fillPath()
        let image = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        return image
    }
    
}

extension MBRealtimeUnderstandingViewController: UITextFieldDelegate {
    
    func textField(_ textField: UITextField, shouldChangeCharactersIn range: NSRange, replacementString string: String) -> Bool {
        let allowedCharacters = CharacterSet.decimalDigits
        let characterSet = CharacterSet(charactersIn: string)
        return allowedCharacters.isSuperset(of: characterSet)
    }
    
}

// MARK: - UIPickerViewDelegate, UIPickerViewDataSource
extension MBRealtimeUnderstandingViewController: UIPickerViewDelegate, UIPickerViewDataSource {
    func numberOfComponents(in pickerView: UIPickerView) -> Int { 1 }
    func pickerView(_ pickerView: UIPickerView, numberOfRowsInComponent component: Int) -> Int {
        return drawFPSOptions.count
    }
    func pickerView(_ pickerView: UIPickerView, titleForRow row: Int, forComponent component: Int) -> String? {
        return drawFPSOptions[row]
    }
    func pickerView(_ pickerView: UIPickerView, didSelectRow row: Int, inComponent component: Int) {
        drawFPSPickerTextField.text = drawFPSOptions[row]
        drawFPSPickerTextField.resignFirstResponder()
    }
}

