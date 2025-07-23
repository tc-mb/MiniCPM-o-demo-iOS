/*Copyright (c) 2016, Andrew Walz.
 
 Redistribution and use in source and binary forms, with or without modification,are permitted provided that the following conditions are met:
 
 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 
 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the
 documentation and/or other materials provided with the distribution.
 
 THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS
 BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import UIKit
import AVFoundation
import SnapKit
import llama

/// 拍摄界面 ViewController
class SwiftyCameraMainViewController: SwiftyCamViewController, SwiftyCamViewControllerDelegate {
    
    // MARK: - properties
    
    /// 底部中间-拍摄按钮
    lazy var captureButton: SwiftyCameraRecordButton = {
        let btn = SwiftyCameraRecordButton(frame: CGRectMake(0, 0, 75, 75))
        return btn
    }()
    
    /// 底部左边-翻转相机按钮
    lazy var flipCameraButton: UIButton = {
        let btn = UIButton(frame: CGRectMake(0, 0, 30, 23))
        return btn
    }()
    
    /// 底部右边-开启闪光灯按钮
    lazy var flashButton: UIButton = {
        let btn = UIButton(frame: CGRectMake(0, 0, 36, 36))
        return btn
    }()
    
    /// 左上角关闭按钮
    lazy var closeButton: UIButton = {
        let btn = UIButton()
        return btn
    }()
    
    /// 逻辑是这样的：用户长按拍摄按钮时，会进入录像流程，这时，开启这个定时器，然后每秒钟拍一张照片（达到抽帧的效果）
    fileprivate var timer: Timer?
    var triggeredTime: Int = 0
    
    /// 记录录像时每秒钟抽帧
    var capturedImages: [UIImage] = [UIImage]()
    
    /// 开始拍照（按下录像按钮）的回调
    typealias StartCapturedImageCompletionHandler = (Int) -> Void
    var startCapturedImageCompletionHandler: StartCapturedImageCompletionHandler?
    
    /// 拍照（抽帧）完成的回调
    typealias CapturedImageCompletionHandler = (Int, UIImage) -> Void
    var capturedImageCompletionHandler: CapturedImageCompletionHandler?
    
    /// 录像完成的回调，注意，录完像是保存为 file url 了
    typealias CapturedVideoRecordCompletionHandler = (UIImage, URL) -> Void
    var capturedVideoRecordCompletionHandler: CapturedVideoRecordCompletionHandler?
    
    /// 关闭按钮的回调
    typealias DismissVCHandler = (String) -> Void
    var dismissVCHandler: DismissVCHandler?
    
    /// 切换实时
    lazy var switchStreamControl: UISegmentedControl = {
        
        let control = UISegmentedControl()
        control.alpha = 0.01
        control.selectedSegmentTintColor = UIColor.mb_color(with: "#333333")?.withAlphaComponent(0.8)
        control.tintColor = UIColor.mb_color(with: "#333333")?.withAlphaComponent(0.4)
        control.isMomentary = false
        
        if MBUtils.isDeviceIPhone() {
            let normalAttributes: [NSAttributedString.Key: Any] = [.foregroundColor: UIColor.white]
            control.setTitleTextAttributes(normalAttributes, for: .normal)
            let selectedAttributes: [NSAttributedString.Key: Any] = [.foregroundColor: UIColor.mb_color(with: "#F8CB58") ?? .gray]
            control.setTitleTextAttributes(selectedAttributes, for: .selected)
            
        } else {
            let normalAttributes: [NSAttributedString.Key: Any] = [.foregroundColor: UIColor.white, .font: UIFont.systemFont(ofSize: 20)]
            control.setTitleTextAttributes(normalAttributes, for: .normal)
            let selectedAttributes: [NSAttributedString.Key: Any] = [.foregroundColor: UIColor.mb_color(with: "#F8CB58") ?? .gray, .font: UIFont.systemFont(ofSize: 20)]
            control.setTitleTextAttributes(selectedAttributes, for: .selected)
        }
        
        control.insertSegment(withTitle: "单视频拍摄", at: 0, animated: false)
        control.insertSegment(withTitle: "实时理解", at: 1, animated: false)
        control.addTarget(self, action: #selector(switchSegmentValueChanged), for: .valueChanged)
        
        return control
    }()
    
    /// live stream 时预置的问题 view
    lazy var presetQuestionView: MBPresetQuestionView = {
        let presetView = MBPresetQuestionView()
        return presetView
    }()
    
    /// live stream timer
    var liveStreamTimer: Timer?
    
    /// 外部传入的 llama mtmd state machine 的引用
    var llamaState: MTMDWrapperExample?
    
    /// 上一张视频帧是否在处理中，不能提问，也不能抽帧
    var processing = false
    
    /// 如果 embed 不足 3 张，则继续 embed
    var embeddingCount = 0
    
    /// 上一次点击的实时问题是
    var lastLiveQuestionStr: String?

    public lazy var liveOutputContainer: UIView = {
        let c = UIView()
        c.backgroundColor = UIColor.mb_color(with: "#333333")?.withAlphaComponent(0.4)
        
        if MBUtils.isDeviceIPhone() {
            c.layer.cornerRadius = 16
        } else {
            c.layer.cornerRadius = 32
        }
        
        c.clipsToBounds = true
        return c
    }()
    
    /// live stream 显示的结果
    public lazy var liveOutputTextLabel: UILabel = {
        let lbl = UILabel()
        // 不限制，可以直接输出
        lbl.numberOfLines = 6
        lbl.textColor = .white
        lbl.lineBreakMode = .byWordWrapping
        
        if MBUtils.isDeviceIPhone() {
            lbl.font = UIFont.systemFont(ofSize: 12, weight: .regular)
        } else {
            lbl.font = UIFont.systemFont(ofSize: 20, weight: .regular)
        }
        
        return lbl
    }()
    
    // MARK: - View Lifecycle
    
    override func viewDidLoad() {
        super.viewDidLoad()
        
        setupSubViews()
        
        // 视频质量为 .low
        videoQuality = .high
        
        // 提示用户开启摄像头、麦克风权限
        shouldPrompToAppSettings = true
        
        cameraDelegate = self
        
        // 最大录像时长 10 秒
        maximumVideoDuration = 10.0 + 1.0
        
        // 允许旋转屏幕
        shouldUseDeviceOrientation = true
        
        // 允许旋转屏幕
        allowAutoRotate = true
        
        audioEnabled = false
        
        pinchToZoom = false
        
        doubleTapCameraSwitch = false
        
        swipeToZoom = false
        
        swipeToZoomInverted = false
        
        // 就是这个控制着预览视口
        videoGravity = .resizeAspect
        
        // 关闭闪光灯，要不然抽帧的时候一闪一闪的
        flashMode = .off
        
        captureButton.buttonEnabled = false
    }
    
    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        
        invalidateTimer()
    }
    
    /// 创建 子view
    func setupSubViews() {
        
        // 拍照按钮
        self.view.addSubview(captureButton)
        captureButton.snp.makeConstraints { make in
            make.centerX.equalTo(self.view)
            make.width.height.equalTo(75)
            make.bottom.equalToSuperview().offset(-MBConstants.shared.kBottomSafeHeight)
        }
        
        // 切换相机按钮
        self.view.addSubview(flipCameraButton)
        flipCameraButton.snp.makeConstraints { make in
            make.centerY.equalTo(captureButton)
            make.right.equalTo(captureButton.snp.left).offset(-44)
            make.width.height.equalTo(42)
        }
        flipCameraButton.setImage(UIImage(named: "camera_flip_icon"), for: UIControl.State())
        flipCameraButton.addTarget(self, action: #selector(cameraSwitchTapped), for: .touchUpInside)
        
        // 左上角关闭按钮
        let closeImageConfig = UIImage.SymbolConfiguration(pointSize: 22)
        let closeImage = UIImage(systemName: "xmark",
                                 withConfiguration: closeImageConfig)?.withTintColor(UIColor.mb_color(with: "#FFFFFF") ?? .white,
                                                                                     renderingMode: .alwaysOriginal)
        closeButton.setImage(closeImage, for: .normal)
        
        self.view.addSubview(closeButton)
        closeButton.snp.makeConstraints { make in
            make.top.equalTo(MBConstants.shared.kStatusBarHeight)
            make.left.equalTo(28)
            if MBUtils.isDeviceIPhone() {
                make.width.height.equalTo(32)
            } else {
                make.width.height.equalTo(58)
            }
        }
        
        closeButton.addTarget(self, action: #selector(handleNavCloseButton), for: .touchUpInside)
        
        // 切换 实时 按钮
        switchStreamControl.selectedSegmentIndex = 0
        self.view.addSubview(switchStreamControl)
        switchStreamControl.snp.makeConstraints { make in
            make.centerX.equalToSuperview()
            
            if MBUtils.isDeviceIPhone() {
                make.width.equalTo(300)
                make.height.equalTo(25)
            } else {
                make.width.equalTo(400)
                make.height.equalTo(50)
            }
            
            make.bottom.equalTo(-132)
        }
        
        self.view.addSubview(presetQuestionView)
        presetQuestionView.isHidden = true
        presetQuestionView.snp.makeConstraints { make in
            
            make.top.equalTo(MBConstants.shared.kNavBarHeight).offset(120)
            
            // height + 28 是顶部切换 gap 按钮的位置
            if MBUtils.isDeviceIPhone() {
                make.width.equalTo(168)
                make.height.equalTo(188 + 24)
            } else {
                make.width.equalTo(296)
                make.height.equalTo(320 + 24)
            }
            
            make.right.equalTo(-30)
        }
        
        // 点击事件
        presetQuestionView.onTap = { [weak self] str in
            
            guard let self else {
                return
            }
            
            self.lastLiveQuestionStr = str
            
            // 抽一帧
            self.takePhoto()
        }
        
        // 输出结果
        self.view.addSubview(liveOutputContainer)
        liveOutputContainer.isHidden = true
        liveOutputContainer.snp.makeConstraints { make in
            make.centerX.equalToSuperview()
            if MBUtils.isDeviceIPhone() {
                make.height.equalTo(32)
            } else {
                make.height.equalTo(64)
            }
            make.width.equalTo(102)
            make.bottom.equalTo(-180)
        }
        
        liveOutputContainer.addSubview(liveOutputTextLabel)
        liveOutputTextLabel.isHidden = true
        
        liveOutputTextLabel.snp.makeConstraints { make in
            make.top.equalToSuperview().offset(8)
            make.bottom.equalToSuperview().offset(-8)
            make.left.equalTo(12)
            make.right.equalTo(-12)
        }
    }
    
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        captureButton.delegate = self
        
        // 延迟 0.5 秒自动切换到实时理解模式
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            self.switchStreamControl.selectedSegmentIndex = 1
            self.switchStreamControl.isUserInteractionEnabled = false
            
            // 触发切换事件
            self.switchSegmentValueChanged(self.switchStreamControl)
        }
    }
    
    /// 隐藏状态栏
    override var prefersStatusBarHidden: Bool {
        return true
    }
    
    /// 重写支持的屏幕方向
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        return .portrait
    }
    
    /// 开始 session
    func swiftyCamSessionDidStartRunning(_ swiftyCam: SwiftyCamViewController) {
        debugLog("Session did start running")
        captureButton.buttonEnabled = true
    }
    
    /// 结束 session
    func swiftyCamSessionDidStopRunning(_ swiftyCam: SwiftyCamViewController) {
        debugLog("Session did stop running")
        captureButton.buttonEnabled = false
    }
    
    /// 这是拍照功能的回调，可以改造为每秒截一张图片的模式
    func swiftyCam(_ swiftyCam: SwiftyCamViewController, didTake photo: UIImage) {
        
        if switchStreamControl.selectedSegmentIndex == 0 {
            // 录像功能 抽帧 的回调
            // 现在改为存入数组中
            self.capturedImages.append(photo)
            
            // 回调给外部业务
            capturedImageCompletionHandler?(triggeredTime, photo)
            
        } else if switchStreamControl.selectedSegmentIndex == 1 {
            // 现在逻辑改为了每秒都会拍一帧，然后不停地放入到模型里去
            // 需要那个 queue;
            // 还要一个 type 判断所有图片都处理完了没？
            if self.liveStreamTimer != nil {
                self.processLiveStreamPhotoLogic(photo: photo)
            }
        }
        
    }
    
    /// 开始拍照、录像
    func swiftyCam(_ swiftyCam: SwiftyCamViewController, didBeginRecordingVideo camera: SwiftyCamViewController.CameraSelection) {
        
        if switchStreamControl.selectedSegmentIndex == 0 {
            
            debugLog("Did Begin Recording")
            
            // ui 更新 capture button ui
            captureButton.growButton()
            
            // ui 隐藏界面其它按钮
            hideButtons()
            
            // 同时「录像」启动抽帧定时器
            startTimer()
            
            // 停止「实时理解」timer
            invalidateLiveStreamTimer()
            
            // 开始录像的回调
            self.startCapturedImageCompletionHandler?(0)
        } else if switchStreamControl.selectedSegmentIndex == 1 {
            
            // live stream 功能
            debugLog("Did Begin live streaming.")
            
            captureButton.growButton()
            
            hideButtons()
            
            // 现在的逻辑是点击开始后，自动进入「准备抽帧」的状态，用户点一下预置的问题，就会向模型问一下问题。
            self.presetQuestionView.isHidden = false
            
            invalidateLiveStreamTimer()
            
            // 启动 live stream timer
            startLiveStreamTimer()
        }
    }
    
    /// 完成拍照、录像时的回调
    func swiftyCam(_ swiftyCam: SwiftyCamViewController, didFinishRecordingVideo camera: SwiftyCamViewController.CameraSelection) {
        debugLog("Did finish Recording")
        captureButton.shrinkButton()
        showButtons()
        
        // 停止抽帧定时器
        invalidateTimer()
        
        invalidateLiveStreamTimer()
        
        if switchStreamControl.selectedSegmentIndex == 1 {
            // 隐藏 live stream 的内容
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                self.liveOutputContainer.isHidden = true
                self.liveOutputTextLabel.isHidden = true
                self.llamaState?.stopGeneration()
                self.llamaState?.outputText = ""
                self.liveOutputTextLabel.attributedText = nil
            }
        }
    }
    
    /// 完成录像，返回保存到 documents 的 file url
    func swiftyCam(_ swiftyCam: SwiftyCamViewController, didFinishProcessVideoAt url: URL) {
        
        if self.switchStreamControl.selectedSegmentIndex == 0 {
            // 收起录像 ViewController，并且返回给业务侧保存好的录像文件 url
            self.dismiss(animated: true) { [weak self] in
                // 注意：使用前置摄像头时，帧会水平翻转
                var snap = self?.capturedImages.first
                if swiftyCam.currentCamera == .front {
                    // 前置拍摄的，需要 180 度旋转缩略图才行
                    snap = snap?.rotated(byDegrees: 180)
                }
                
                // 外部设备
                if SwiftyCamViewController.externalDevice {
                    // 使用外接摄像头，修正缩略图
                    snap = snap?.rotated(byDegrees: 180)
                }
                
                if let snap = snap {
                    self?.capturedVideoRecordCompletionHandler?(snap, url)
                }
            }
        } else {
            // 这是 live stream or asr，啥也不用干
        }
        
    }
    
    /// 点击进行对焦功能
    func swiftyCam(_ swiftyCam: SwiftyCamViewController, didFocusAtPoint point: CGPoint) {
        debugLog("Did focus at point: \(point)")
        focusAnimationAt(point)
    }
    
    /// 无法录像失败的回调
    func swiftyCamDidFailToConfigure(_ swiftyCam: SwiftyCamViewController) {
        let message = NSLocalizedString("暂时无法拍摄录像",
                                        comment: "Alert message when something goes wrong during capture session configuration")
        let alertController = UIAlertController(title: "提示",
                                                message: message,
                                                preferredStyle: .alert)
        alertController.addAction(UIAlertAction(title: NSLocalizedString("好", comment: "Alert OK button"),
                                                style: .cancel,
                                                handler: nil))
        present(alertController, animated: true, completion: nil)
    }
    
    /// 使用手指捏合进行缩放
    func swiftyCam(_ swiftyCam: SwiftyCamViewController, didChangeZoomLevel zoom: CGFloat) {
        debugLog("Zoom level did change. Level: \(zoom)")
        print(zoom)
    }
    
    /// 切换前、后摄像头的回调
    func swiftyCam(_ swiftyCam: SwiftyCamViewController, didSwitchCameras camera: SwiftyCamViewController.CameraSelection) {
        debugLog("Camera did change to \(camera.rawValue)")
        print(camera)
    }
    
    /// 失败
    func swiftyCam(_ swiftyCam: SwiftyCamViewController, didFailToRecordVideo error: Error) {
        print(error)
        invalidateTimer()
        
        // 停止抽帧定时器
        invalidateLiveStreamTimer()
    }
    
    /// 切换前、后摄像头
    @objc func cameraSwitchTapped(_ sender: Any) {
        switchCamera()
    }
    
    /// 闪光灯「自动、开启、关闭」
    @objc func toggleFlashTapped(_ sender: Any) {
        toggleFlashAnimation()
    }
    
    /// 关闭当前 VC
    @objc func handleNavCloseButton(_ sender: UIButton) {
        
        invalidateTimer()
        
        // 停止抽帧定时器
        invalidateLiveStreamTimer()
        
        self.dismiss(animated: true) { [weak self] in
            self?.llamaState?.stopGeneration()
            self?.flashMode = .off
            self?.dismissVCHandler?("close")
        }
    }
    
    /// 录像 or 实时 switch control 事件
    @objc func switchSegmentValueChanged(_ sender: UISegmentedControl) {
        // 如果选中了 1（live stream），则启动每秒 1 次的抽帧，并把结果输出在当前屏幕上
        // 这个时候，注意，还要把 llama state 传过来才行
        if sender.selectedSegmentIndex == 1 {
            presetQuestionView.isHidden = false
        } else if sender.selectedSegmentIndex == 0 {
            // 如果切换到录像界面，则停止 live stream
            presetQuestionView.isHidden = true
            invalidateLiveStreamTimer()
        }
    }
}


// UI Animations
extension SwiftyCameraMainViewController {
    
    fileprivate func hideButtons() {
        UIView.animate(withDuration: 0.25) {
            self.flashButton.alpha = 0.0
            self.flipCameraButton.alpha = 0.0
            self.switchStreamControl.alpha = 0.0
        }
    }
    
    fileprivate func showButtons() {
        UIView.animate(withDuration: 0.25) {
            self.flashButton.alpha = 1.0
            self.flipCameraButton.alpha = 1.0
            self.switchStreamControl.alpha = 0.01
        }
    }
    
    /// 对焦提示 icon
    fileprivate func focusAnimationAt(_ point: CGPoint) {
        let focusView = UIImageView(frame: CGRectMake(0, 0, 36, 36))
        let focusImageConfig = UIImage.SymbolConfiguration(pointSize: 36)
        focusView.image = UIImage(systemName: "circle.circle",
                                  withConfiguration: focusImageConfig)?.withTintColor(UIColor.mb_color(with: "#E0E0E0") ?? .white,
                                                                                      renderingMode: .alwaysOriginal)
        
        focusView.clipsToBounds = true
        focusView.layer.cornerRadius = 18
        
        focusView.center = point
        focusView.alpha = 0.0
        view.addSubview(focusView)
        
        UIView.animate(withDuration: 0.25, delay: 0.0, options: .curveEaseInOut, animations: {
            focusView.alpha = 1.0
            focusView.transform = CGAffineTransform(scaleX: 1.25, y: 1.25)
        }) { (success) in
            UIView.animate(withDuration: 0.15, delay: 0.5, options: .curveEaseInOut, animations: {
                focusView.alpha = 0.0
                focusView.transform = CGAffineTransform(translationX: 0.6, y: 0.6)
            }) { (success) in
                focusView.removeFromSuperview()
            }
        }
    }
    
    fileprivate func toggleFlashAnimation() {
        if flashMode == .auto {
            flashMode = .on
            flashButton.setImage(UIImage(named: "flash_on_icon"), for: UIControl.State())
        } else if flashMode == .on {
            flashMode = .off
            flashButton.setImage(UIImage(named: "flash_off_icon"), for: UIControl.State())
        } else if flashMode == .off {
            flashMode = .auto
            flashButton.setImage(UIImage(named: "flash_on_icon"), for: UIControl.State())
        }
    }
}

extension SwiftyCameraMainViewController {
    
    /// 长按录像后，启动定时器
    fileprivate func startTimer() {
        timer = Timer.scheduledTimer(timeInterval: 1,
                                     target: self,
                                     selector: #selector(timerFired),
                                     userInfo: nil,
                                     repeats: true)
    }
    
    /// 结束定时器
    fileprivate func invalidateTimer() {
        timer?.invalidate()
        timer = nil
        triggeredTime = 0
    }
    
    /// 进行拍照（抽帧）
    @objc fileprivate func timerFired() {
        
        // 时长增加
        triggeredTime += 1
        
        // 抽帧
        takePhoto()
        
        // 超时了
        if triggeredTime == 11 {
            invalidateTimer()
            stopVideoRecording()
        }
    }
    
}

extension SwiftyCameraMainViewController {
    
    /// 显示错误提示
    func showErrorTips(_ str: String?, detail: String? = nil, delay: TimeInterval = 2) {
        DispatchQueue.main.async {
            let hud = MBHUD.showAdded(to: self.view, animated: true)
            hud.mode = .text
            hud.label.text = str
            hud.hide(animated: true, afterDelay: delay)
        }
    }
    
    /// 保存 UIImage 到 沙箱 cache folder 里
    private func saveImageToCache(image: UIImage,
                                  fileName: String,
                                  asJPEGFormat: Bool = true,
                                  compressionQuality: CGFloat = 1) -> URL? {
        let cacheDirectory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
        let fileUrl = cacheDirectory?.appendingPathComponent(fileName)
        
        var data: Data?
        
        if asJPEGFormat {
            data = image.jpegData(compressionQuality: compressionQuality)
        } else {
            data = image.pngData()
        }
        
        guard let imageData = data, let url = fileUrl else { return nil }
        
        do {
            try imageData.write(to: url)
        } catch {
            debugLog("saveImageToCache(:) error.")
            return nil
        }
        
        return url
    }
    
}

extension SwiftyCameraMainViewController {
    
    public func getOutputLabelString() -> String? {
        return self.liveOutputTextLabel.attributedText?.string
    }
    
    /// 更新输出文字
    public func updateWithOutputLabel(str: String?) {
        guard let str else {
            return
        }
        
        // MARK: - 以下是「实时理解」处理模型输出的逻辑
        
        let viewWidth: CGFloat? = self.view.frame.width
        
        var exceedMaxLine = false
        
        var containerHeight = self.calcOutputContainerHeightWith(str: str, viewWidth: viewWidth, exceedMaxLine: &exceedMaxLine)
        
        if exceedMaxLine {
            // 通知 llamaState 停止
            self.llamaState?.stopGeneration()
        }
        
        if containerHeight < 64 {
            containerHeight = 64
        }
        
        // 来这儿再让显示出来
        liveOutputContainer.isHidden = false
        liveOutputTextLabel.isHidden = false
        
        self.liveOutputContainer.snp.remakeConstraints { make in
            
            make.centerX.equalToSuperview()
            
            make.height.equalTo(containerHeight)
            
            make.width.greaterThanOrEqualTo(64)
            
            if MBUtils.isDeviceIPhone() {
                make.left.greaterThanOrEqualTo(64)
                make.right.lessThanOrEqualTo(-64)
            } else {
                make.left.greaterThanOrEqualTo(100)
                make.right.lessThanOrEqualTo(-100)
            }
            
            make.bottom.equalTo(-180)
        }
        
        let para = NSMutableParagraphStyle()
        para.maximumLineHeight = 22
        para.minimumLineHeight = 22
        para.lineSpacing = 2
        para.lineBreakMode = .byWordWrapping
        
        var font: UIFont = UIFont.systemFont(ofSize: 12, weight: .regular)
        
        if MBUtils.isDeviceIPad() {
            font = UIFont.systemFont(ofSize: 20, weight: .regular)
        }
        
        let attributes: [NSAttributedString.Key: Any] = [
            .foregroundColor: UIColor.white,
            .font: font,
            .paragraphStyle: para
        ]
        
        liveOutputTextLabel.attributedText = NSAttributedString(string: str, attributes: attributes)
    }
    
    /// 计算输出文本的高度
    private func calcOutputContainerHeightWith(str: String?, viewWidth: CGFloat?, exceedMaxLine: inout Bool) -> CGFloat {
        if let text = str,
           let viewWidth = viewWidth {
            
            let para = NSMutableParagraphStyle()
            para.maximumLineHeight = 22
            para.minimumLineHeight = 22
            para.lineSpacing = 2
            para.lineBreakMode = .byWordWrapping
            
            var font: UIFont = UIFont.systemFont(ofSize: 12, weight: .regular)
            
            if MBUtils.isDeviceIPad() {
                font = UIFont.systemFont(ofSize: 20, weight: .regular)
            }
            
            let attributes: [NSAttributedString.Key: Any] = [
                .foregroundColor: UIColor.white,
                .font: font,
                .paragraphStyle: para
            ]
            
            let customLabel = UILabel()
            customLabel.attributedText = NSAttributedString(string: text, attributes: attributes)
            
            // iPad
            var cellMargin = 200 + 12 + 12
            
            // for MacOS App
            if viewWidth >= 1920 {
                cellMargin = 200 + 12 + 12 + 185/*magic*/ + 172
            }
            
            if MBUtils.isDeviceIPhone() {
                // iPhone
                cellMargin = 128 + 12 + 12
            }
            
            let frameWidth: CGFloat = viewWidth - CGFloat(cellMargin)
            
            let pureContentTextframe = customLabel.attributedText?.boundingRect(with: CGSize(width: frameWidth,
                                                                                             height: .greatestFiniteMagnitude),
                                                                                options: .usesLineFragmentOrigin,
                                                                                context: nil).size ?? .zero
            
            // return cell height
            var textHeight = pureContentTextframe.height
            
            if textHeight / 26 > 5.5 {
                textHeight = 142
                exceedMaxLine = true
            }
            
            return textHeight + 42/* label 上下 gap */ - 16
        }
        
        return 0
    }
    
}
