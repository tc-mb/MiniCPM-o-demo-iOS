//
//  MBImageVideoViewController.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/12/19.
//

import UIKit
import AVKit
import AVFoundation
import Photos
import SnapKit

/// 原生图片视频查看VC
@objc public class MBImageVideoViewController: UIViewController {
    
    // MARK: - Properties
    
    /// 图片源（UIImage 或 URL）
    private var imageSource: Any?
    
    /// 视频源（URL 或 AVAsset）
    private var videoSource: Any?
    
    /// 滚动视图，用于图片的缩放和平移
    private lazy var scrollView: UIScrollView = {
        let scrollView = UIScrollView()
        scrollView.delegate = self
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.showsVerticalScrollIndicator = false
        scrollView.decelerationRate = UIScrollView.DecelerationRate.fast
        scrollView.contentInsetAdjustmentBehavior = .never
        scrollView.minimumZoomScale = 1.0
        scrollView.maximumZoomScale = 3.0
        scrollView.zoomScale = 1.0
        return scrollView
    }()
    
    /// 图片视图
    private lazy var imageView: UIImageView = {
        let imageView = UIImageView()
        imageView.contentMode = .scaleAspectFit
        imageView.clipsToBounds = true
        imageView.backgroundColor = .clear
        return imageView
    }()
    
    /// 视频播放器视图
    private lazy var playerView: UIView = {
        let view = UIView()
        view.backgroundColor = .black
        return view
    }()
    
    /// 视频播放器
    private var player: AVPlayer?
    private var playerLayer: AVPlayerLayer?
    
    /// 关闭按钮
    private lazy var closeButton: UIButton = {
        let button = UIButton(type: .system)
        button.setTitle("✕", for: .normal)
        button.setTitleColor(.white, for: .normal)
        button.titleLabel?.font = UIFont.systemFont(ofSize: 24, weight: .medium)
        button.backgroundColor = UIColor.black.withAlphaComponent(0.5)
        button.layer.cornerRadius = 20
        button.addTarget(self, action: #selector(closeButtonTapped), for: .touchUpInside)
        return button
    }()
    
    /// 播放/暂停按钮（仅视频）
    private lazy var playPauseButton: UIButton = {
        let button = UIButton(type: .system)
        button.setImage(UIImage(systemName: "play.fill"), for: .normal)
        button.setImage(UIImage(systemName: "pause.fill"), for: .selected)
        button.tintColor = .white
        button.backgroundColor = UIColor.black.withAlphaComponent(0.5)
        button.layer.cornerRadius = 25
        button.addTarget(self, action: #selector(playPauseButtonTapped), for: .touchUpInside)
        button.isHidden = true
        return button
    }()
    
    /// 是否正在播放视频
    private var isPlaying = false
    
    // MARK: - Initialization
    
    /// 使用图片初始化
    @objc public convenience init(image: UIImage) {
        self.init()
        self.imageSource = image
        self.modalPresentationStyle = .fullScreen
        self.modalTransitionStyle = .crossDissolve
    }
    
    /// 使用图片URL初始化
    @objc public convenience init(imageURL: URL) {
        self.init()
        self.imageSource = imageURL
        self.modalPresentationStyle = .fullScreen
        self.modalTransitionStyle = .crossDissolve
    }
    
    /// 使用视频URL初始化
    @objc public convenience init(videoURL: URL) {
        self.init()
        self.videoSource = videoURL
        self.modalPresentationStyle = .fullScreen
        self.modalTransitionStyle = .crossDissolve
    }
    
    /// 使用视频Asset初始化
    @objc public convenience init(videoAsset: AVAsset) {
        self.init()
        self.videoSource = videoAsset
        self.modalPresentationStyle = .fullScreen
        self.modalTransitionStyle = .crossDissolve
    }
    
    // MARK: - Lifecycle
    
    public override func viewDidLoad() {
        super.viewDidLoad()
        setupUI()
        setupContent()
        setupGestures()
    }
    
    public override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        if videoSource != nil {
            // 配置音频会话
            setupAudioSession()
            player?.play()
            isPlaying = true
            playPauseButton.isSelected = true
        }
    }
    
    public override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        player?.pause()
    }
    
    public override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        playerLayer?.frame = playerView.bounds
        updateImageLayout()
    }
    
    // MARK: - UI Setup
    
    private func setupUI() {
        view.backgroundColor = .black
        
        // 添加滚动视图
        view.addSubview(scrollView)
        scrollView.snp.makeConstraints { make in
            make.edges.equalToSuperview()
        }
        
        // 添加图片视图
        scrollView.addSubview(imageView)
        
        // 添加视频播放器视图
        view.addSubview(playerView)
        playerView.snp.makeConstraints { make in
            make.edges.equalToSuperview()
        }
        playerView.isHidden = true
        
        // 添加关闭按钮（隐藏，因为点击图片区域即可关闭）
        view.addSubview(closeButton)
        closeButton.snp.makeConstraints { make in
            make.top.equalTo(view.safeAreaLayoutGuide.snp.top).offset(10)
            make.left.equalToSuperview().offset(20)
            make.width.height.equalTo(40)
        }
        closeButton.isHidden = true
        
        // 添加播放/暂停按钮
        view.addSubview(playPauseButton)
        playPauseButton.snp.makeConstraints { make in
            make.center.equalToSuperview()
            make.width.height.equalTo(50)
        }
    }
    
    private func setupContent() {
        if let image = imageSource as? UIImage {
            // 显示图片
            imageView.image = image
            scrollView.isHidden = false
            playerView.isHidden = true
            playPauseButton.isHidden = true
            closeButton.isHidden = true
            updateImageLayout()
            
        } else if let imageURL = imageSource as? URL {
            // 加载网络图片
            scrollView.isHidden = false
            playerView.isHidden = true
            playPauseButton.isHidden = true
            closeButton.isHidden = true
            
            // 这里可以添加图片加载逻辑，比如使用SDWebImage或Kingfisher
            // 暂时使用系统方法
            if let data = try? Data(contentsOf: imageURL),
               let image = UIImage(data: data) {
                imageView.image = image
                updateImageLayout()
            }
            
        } else if let videoURL = videoSource as? URL {
            // 播放视频
            setupVideoPlayer(with: videoURL)
            
        } else if let videoAsset = videoSource as? AVAsset {
            // 播放视频Asset
            setupVideoPlayer(with: videoAsset)
        }
    }
    
    private func setupVideoPlayer(with url: URL) {
        player = AVPlayer(url: url)
        playerLayer = AVPlayerLayer(player: player)
        playerLayer?.videoGravity = .resizeAspect
        
        if let playerLayer = playerLayer {
            playerView.layer.addSublayer(playerLayer)
            playerLayer.frame = playerView.bounds
        }
        
        scrollView.isHidden = true
        playerView.isHidden = false
        playPauseButton.isHidden = false
        closeButton.isHidden = false
        
        // 监听播放状态
        player?.addObserver(self, forKeyPath: "rate", options: [.new, .old], context: nil)
    }
    
    private func setupVideoPlayer(with asset: AVAsset) {
        let playerItem = AVPlayerItem(asset: asset)
        player = AVPlayer(playerItem: playerItem)
        playerLayer = AVPlayerLayer(player: player)
        playerLayer?.videoGravity = .resizeAspect
        
        if let playerLayer = playerLayer {
            playerView.layer.addSublayer(playerLayer)
            playerLayer.frame = playerView.bounds
        }
        
        scrollView.isHidden = true
        playerView.isHidden = false
        playPauseButton.isHidden = false
        closeButton.isHidden = false
        
        // 监听播放状态
        player?.addObserver(self, forKeyPath: "rate", options: [.new, .old], context: nil)
    }
    
    /// 配置音频会话
    private func setupAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            print("音频会话配置失败: \(error)")
        }
    }
    
    private func setupGestures() {
        // 单击关闭
        let singleTap = UITapGestureRecognizer(target: self, action: #selector(handleSingleTap))
        view.addGestureRecognizer(singleTap)
        
        // 双击缩放（仅图片）
        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap))
        doubleTap.numberOfTapsRequired = 2
        view.addGestureRecognizer(doubleTap)
        
        // 确保双击不会触发单击
        singleTap.require(toFail: doubleTap)
    }
    
    // MARK: - Layout
    
    private func updateImageLayout() {
        guard let image = imageView.image else { return }
        
        // 移除之前的约束
        imageView.snp.removeConstraints()
        
        let imageSize = image.size
        let viewSize = scrollView.bounds.size
        
        // 计算缩放比例，确保图片完全显示在视图中
        let widthRatio = viewSize.width / imageSize.width
        let heightRatio = viewSize.height / imageSize.height
        let scale = min(widthRatio, heightRatio)
        
        let scaledWidth = imageSize.width * scale
        let scaledHeight = imageSize.height * scale
        
        // 使用SnapKit约束确保图片居中
        imageView.snp.makeConstraints { make in
            make.center.equalToSuperview()
            make.width.equalTo(scaledWidth)
            make.height.equalTo(scaledHeight)
        }
        
        // 设置滚动视图的contentSize
        scrollView.contentSize = CGSize(width: scaledWidth, height: scaledHeight)
        
        // 重置缩放
        scrollView.zoomScale = 1.0
        scrollView.minimumZoomScale = 1.0
        scrollView.maximumZoomScale = max(3.0, 1.0 / scale)
    }
    
    // MARK: - Actions
    
    @objc private func closeButtonTapped() {
        dismiss(animated: true)
    }
    
    @objc private func playPauseButtonTapped() {
        if isPlaying {
            player?.pause()
            playPauseButton.isSelected = false
        } else {
            player?.play()
            playPauseButton.isSelected = true
        }
        isPlaying = !isPlaying
    }
    
    @objc private func handleSingleTap() {
        if videoSource != nil {
            // 视频模式：切换UI显示状态
            let isHidden = closeButton.isHidden
            UIView.animate(withDuration: 0.3) {
                self.closeButton.alpha = isHidden ? 1.0 : 0.0
                self.playPauseButton.alpha = isHidden ? 1.0 : 0.0
            } completion: { _ in
                self.closeButton.isHidden = !isHidden
                self.playPauseButton.isHidden = !isHidden || self.videoSource == nil
            }
        } else {
            // 图片模式：直接关闭查看器
            dismiss(animated: true)
        }
    }
    
    @objc private func handleDoubleTap() {
        guard imageSource != nil else { return }
        
        if scrollView.zoomScale > scrollView.minimumZoomScale {
            // 缩小到原始大小
            scrollView.setZoomScale(scrollView.minimumZoomScale, animated: true)
        } else {
            // 放大到最大
            scrollView.setZoomScale(scrollView.maximumZoomScale, animated: true)
        }
    }
    
    /// 视频播放完成处理
    @objc private func playerDidFinishPlaying() {
        DispatchQueue.main.async {
            self.isPlaying = false
            self.playPauseButton.isSelected = false
            // 可以在这里添加播放完成后的逻辑，比如循环播放或显示提示
        }
    }
    
    // MARK: - KVO
    
    public override func observeValue(forKeyPath keyPath: String?, of object: Any?, change: [NSKeyValueChangeKey : Any]?, context: UnsafeMutableRawPointer?) {
        if keyPath == "rate" {
            DispatchQueue.main.async {
                if self.player?.rate == 0 {
                    self.isPlaying = false
                    self.playPauseButton.isSelected = false
                } else {
                    self.isPlaying = true
                    self.playPauseButton.isSelected = true
                }
            }
        }
    }
    
    // MARK: - Deinit
    
    deinit {
        player?.removeObserver(self, forKeyPath: "rate")
        NotificationCenter.default.removeObserver(self)
    }
}

// MARK: - UIScrollViewDelegate

extension MBImageVideoViewController: UIScrollViewDelegate {
    
    public func viewForZooming(in scrollView: UIScrollView) -> UIView? {
        return imageSource != nil ? imageView : nil
    }
    
    public func scrollViewDidZoom(_ scrollView: UIScrollView) {
        // 缩放时重新设置约束以保持居中
        guard let image = imageView.image else { return }
        
        let imageSize = image.size
        let viewSize = scrollView.bounds.size
        
        let widthRatio = viewSize.width / imageSize.width
        let heightRatio = viewSize.height / imageSize.height
        let scale = min(widthRatio, heightRatio)
        
        let scaledWidth = imageSize.width * scale * scrollView.zoomScale
        let scaledHeight = imageSize.height * scale * scrollView.zoomScale
        
        // 移除之前的约束并重新设置
        imageView.snp.removeConstraints()
        imageView.snp.makeConstraints { make in
            make.center.equalToSuperview()
            make.width.equalTo(scaledWidth)
            make.height.equalTo(scaledHeight)
        }
        
        // 更新contentSize
        scrollView.contentSize = CGSize(width: scaledWidth, height: scaledHeight)
    }
} 