//
//  TtsViewController.swift
//  MiniCPM-V-demo
//
//  VoxCPM2 TTS interface: text input, voice cloning, parameter control,
//  generation, and playback. UIKit-based, mirroring Android's TtsActivity.
//

import UIKit
import Combine

class TtsViewController: UIViewController {

    // MARK: - Engine and audio utilities

    private let engine = TtsEngine.shared
    private let recorder = MBAudioRecorder()
    private let audioPlayer = MBAudioPlayer()
    private var cancellables = Set<AnyCancellable>()

    // MARK: - State

    private var referenceWavURL: URL?
    private var isRecording = false
    private var isGenerating = false
    private var generatedWavURL: URL?
    private var generatedDurationMs: Int = 0

    // MARK: - UI components

    private let scrollView = UIScrollView()
    private let contentView = UIView()

    private let textView: UITextView = {
        let tv = UITextView()
        tv.font = UIFont.systemFont(ofSize: 16)
        tv.layer.borderColor = UIColor.systemGray4.cgColor
        tv.layer.borderWidth = 1
        tv.layer.cornerRadius = 8
        tv.textContainerInset = UIEdgeInsets(top: 12, left: 12, bottom: 12, right: 12)
        return tv
    }()

    // Reference audio section
    private let refAudioLabel: UILabel = {
        let label = UILabel()
        label.font = UIFont.systemFont(ofSize: 14, weight: .medium)
        label.textColor = .secondaryLabel
        return label
    }()

    private let presetFemaleBtn: UIButton = {
        let btn = UIButton(type: .system)
        btn.setTitleColor(.systemBlue, for: .normal)
        btn.layer.borderColor = UIColor.systemBlue.cgColor
        btn.layer.borderWidth = 1
        btn.layer.cornerRadius = 6
        btn.titleLabel?.font = UIFont.systemFont(ofSize: 13)
        return btn
    }()

    private let presetMaleBtn: UIButton = {
        let btn = UIButton(type: .system)
        btn.setTitleColor(.systemBlue, for: .normal)
        btn.layer.borderColor = UIColor.systemBlue.cgColor
        btn.layer.borderWidth = 1
        btn.layer.cornerRadius = 6
        btn.titleLabel?.font = UIFont.systemFont(ofSize: 13)
        return btn
    }()

    private let recordBtn: UIButton = {
        let btn = UIButton(type: .system)
        let img = UIImage(systemName: "mic.fill")
        btn.setImage(img, for: .normal)
        btn.tintColor = .systemRed
        btn.layer.borderColor = UIColor.systemRed.cgColor
        btn.layer.borderWidth = 1
        btn.layer.cornerRadius = 6
        return btn
    }()

    private let playRefBtn: UIButton = {
        let btn = UIButton(type: .system)
        let img = UIImage(systemName: "play.circle")
        btn.setImage(img, for: .normal)
        btn.tintColor = .systemBlue
        btn.isHidden = true
        return btn
    }()

    private let clearRefBtn: UIButton = {
        let btn = UIButton(type: .system)
        btn.setTitle("X", for: .normal)
        btn.tintColor = .systemRed
        btn.isHidden = true
        return btn
    }()

    private let refInfoLabel: UILabel = {
        let label = UILabel()
        label.font = UIFont.systemFont(ofSize: 12)
        label.textColor = .secondaryLabel
        label.isHidden = true
        label.numberOfLines = 0
        return label
    }()

    // Parameter section
    private let cfgSlider: UISlider = {
        let slider = UISlider()
        slider.minimumValue = 0.5
        slider.maximumValue = 5.0
        slider.value = 2.0
        return slider
    }()

    private let cfgValueLabel: UILabel = {
        let label = UILabel()
        label.font = UIFont.systemFont(ofSize: 14, weight: .medium)
        label.text = "2.0"
        return label
    }()

    private let timestepsSlider: UISlider = {
        let slider = UISlider()
        slider.minimumValue = 0
        slider.maximumValue = 20
        slider.value = 5
        return slider
    }()

    private let timestepsValueLabel: UILabel = {
        let label = UILabel()
        label.font = UIFont.systemFont(ofSize: 14, weight: .medium)
        label.text = "5"
        return label
    }()

    private let timestepsHintLabel: UILabel = {
        let label = UILabel()
        label.font = UIFont.systemFont(ofSize: 11)
        label.textColor = .systemOrange
        label.isHidden = true
        return label
    }()

    // Generate section
    private let generateBtn: UIButton = {
        let btn = UIButton(type: .system)
        btn.setTitleColor(.white, for: .normal)
        btn.backgroundColor = UIColor.systemBlue
        btn.layer.cornerRadius = 8
        btn.titleLabel?.font = UIFont.systemFont(ofSize: 16, weight: .medium)
        return btn
    }()

    private let progressIndicator: UIActivityIndicatorView = {
        let indicator = UIActivityIndicatorView(style: .medium)
        indicator.hidesWhenStopped = true
        indicator.isHidden = true
        return indicator
    }()

    private let statusLabel: UILabel = {
        let label = UILabel()
        label.font = UIFont.systemFont(ofSize: 13)
        label.textColor = .secondaryLabel
        label.isHidden = true
        return label
    }()

    // Playback section
    private let playbackCard: UIView = {
        let view = UIView()
        view.backgroundColor = .systemGray6
        view.layer.cornerRadius = 12
        view.isHidden = true
        return view
    }()

    private let playPauseBtn: UIButton = {
        let btn = UIButton(type: .system)
        let img = UIImage(systemName: "play.fill")
        btn.setImage(img, for: .normal)
        btn.tintColor = .systemBlue
        return btn
    }()

    private let playbackSlider: UISlider = {
        let slider = UISlider()
        slider.minimumValue = 0
        slider.maximumValue = 100
        slider.value = 0
        return slider
    }()

    private let playbackTimeLabel: UILabel = {
        let label = UILabel()
        label.font = UIFont.systemFont(ofSize: 12)
        label.textColor = .secondaryLabel
        label.text = "0:00 / 0:00"
        return label
    }()

    // MARK: - Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground

        setupNavigationBar()
        setupLayout()
        setupListeners()
        observeEngine()
        checkModelAndLoad()

        // Set default prompt if textView is empty
        if textView.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            textView.text = L.Tts.defaultPrompt.loc
        }

        NotificationCenter.default.addObserver(self,
                                               selector: #selector(applyLanguage),
                                               name: .languageDidChange,
                                               object: nil)
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    @objc private func applyLanguage() {
        // Rebuild layout to refresh all text (full rebuild since labels are
        // created inline in setupLayout without retained references).
        contentView.subviews.forEach { $0.removeFromSuperview() }
        setupLayout()
        setupListeners()
        
        // Restore state-driven labels
        if isGenerating {
            generateBtn.setTitle(L.Tts.btnCancel.loc, for: .normal)
            generateBtn.backgroundColor = .systemRed
            statusLabel.text = L.Tts.statusGenerating.loc
        } else {
            generateBtn.setTitle(L.Tts.btnGenerate.loc, for: .normal)
            generateBtn.backgroundColor = .systemBlue
        }
        if !isRecording {
            recordBtn.setImage(UIImage(systemName: "mic.fill"), for: .normal)
        } else {
            recordBtn.setImage(UIImage(systemName: "stop.fill"), for: .normal)
        }
        // Refresh ref audio info if active
        if referenceWavURL != nil {
            refInfoLabel.isHidden = false
            // brief label preserved
        } else {
            refInfoLabel.isHidden = true
        }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        // Warm up keyboard immediately so first textView tap is instant
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
            self?.warmUpKeyboard()
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        audioPlayer.stop()
        if isRecording { stopRecording() }
    }

    // MARK: - Reset

    /// Warm up the keyboard extension to avoid lag on first textView tap
    private func warmUpKeyboard() {
        guard !textView.isFirstResponder else { return }

        let probe = UITextField(frame: CGRect(x: -1000, y: -1000, width: 1, height: 1))
        probe.alpha = 0
        probe.autocorrectionType = .no
        probe.spellCheckingType = .no
        view.addSubview(probe)
        _ = probe.becomeFirstResponder()

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
            probe.resignFirstResponder()
            probe.removeFromSuperview()
        }
    }

    /// Called by home VC via the delete button to clear all content
    func resetAllContent() {
        textView.text = ""
        referenceWavURL = nil
        generatedWavURL = nil
        audioPlayer.stop()
        if isRecording { stopRecording() }
        refInfoLabel.isHidden = true
        clearRefBtn.isHidden = true
        playRefBtn.isHidden = true
        playbackCard.isHidden = true
        playbackSlider.value = 0
        playbackTimeLabel.text = "0:00 / 0:00"
        playPauseBtn.setImage(UIImage(systemName: "play.fill"), for: .normal)
        isGenerating = false
        generateBtn.setTitle(L.Tts.btnGenerate.loc, for: .normal)
        generateBtn.backgroundColor = .systemBlue
        progressIndicator.stopAnimating()
        statusLabel.isHidden = true
    }

    // MARK: - Navigation bar

    private func setupNavigationBar() {
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            image: UIImage(systemName: "chevron.left"),
            style: .plain,
            target: self,
            action: #selector(openModelManager)
        )
    }

    @objc private func openModelManager() {
        guard let homeVC = parent as? MBHomeViewController,
              let wrapper = homeVC.mtmdWrapperExample else {
            return
        }
        let settingsVC = MBSettingsViewController(with: wrapper)
        homeVC.navigationController?.pushViewController(settingsVC, animated: true)
    }

    // MARK: - Layout

    private func setupLayout() {
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        contentView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scrollView)
        scrollView.addSubview(contentView)

        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            contentView.topAnchor.constraint(equalTo: scrollView.topAnchor),
            contentView.leadingAnchor.constraint(equalTo: scrollView.leadingAnchor),
            contentView.trailingAnchor.constraint(equalTo: scrollView.trailingAnchor),
            contentView.bottomAnchor.constraint(equalTo: scrollView.bottomAnchor),
            contentView.widthAnchor.constraint(equalTo: scrollView.widthAnchor),
        ])

        let padding: CGFloat = 16
        var lastView: UIView?

        // 语音合成 title — centered blue header
        let headerTitle = UILabel()
        headerTitle.font = UIFont.systemFont(ofSize: 28, weight: .bold)
        headerTitle.textColor = UIColor.systemBlue
        headerTitle.textAlignment = .center
        headerTitle.text = "VoxCPM2"
        headerTitle.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(headerTitle)
        NSLayoutConstraint.activate([
            headerTitle.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 32),
            headerTitle.centerXAnchor.constraint(equalTo: contentView.centerXAnchor),
            headerTitle.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 32),
            headerTitle.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -32),
        ])
        lastView = headerTitle

        // Subtitle
        let subtitleLabel = UILabel()
        subtitleLabel.font = UIFont.systemFont(ofSize: 14)
        subtitleLabel.textColor = .secondaryLabel
        subtitleLabel.textAlignment = .center
        subtitleLabel.text = L.Tts.subtitle.loc
        subtitleLabel.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(subtitleLabel)
        NSLayoutConstraint.activate([
            subtitleLabel.topAnchor.constraint(equalTo: lastView!.bottomAnchor, constant: 6),
            subtitleLabel.centerXAnchor.constraint(equalTo: contentView.centerXAnchor),
            subtitleLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 32),
            subtitleLabel.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -32),
        ])
        lastView = subtitleLabel

        // 使用指引 — centered
        let guideLabel = UILabel()
        guideLabel.font = UIFont.systemFont(ofSize: 13)
        guideLabel.textColor = .tertiaryLabel
        guideLabel.numberOfLines = 0
        guideLabel.textAlignment = .center
        guideLabel.text = L.Tts.guide.loc
        guideLabel.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(guideLabel)
        NSLayoutConstraint.activate([
            guideLabel.topAnchor.constraint(equalTo: lastView!.bottomAnchor, constant: 20),
            guideLabel.centerXAnchor.constraint(equalTo: contentView.centerXAnchor),
            guideLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 28),
            guideLabel.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -28),
        ])
        lastView = guideLabel

        // Text input
        addToContent(textView, below: lastView!, topPadding: 20, height: 120)
        lastView = textView

        // Reference audio label
        addToContent(refAudioLabel, below: lastView!, topPadding: 16)
        refAudioLabel.text = L.Tts.labelRefAudio.loc
        lastView = refAudioLabel

        // Preset + record buttons row
        let btnStack = UIStackView(arrangedSubviews: [presetFemaleBtn, presetMaleBtn, recordBtn, playRefBtn, clearRefBtn])
        btnStack.axis = .horizontal
        btnStack.spacing = 8
        btnStack.distribution = .fillEqually
        presetFemaleBtn.setTitle(L.Tts.btnPresetFemale.loc, for: .normal)
        presetMaleBtn.setTitle(L.Tts.btnPresetMale.loc, for: .normal)
        btnStack.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(btnStack)
        NSLayoutConstraint.activate([
            btnStack.topAnchor.constraint(equalTo: lastView!.bottomAnchor, constant: 8),
            btnStack.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: padding),
            btnStack.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -padding),
            btnStack.heightAnchor.constraint(equalToConstant: 36)
        ])
        lastView = btnStack

        // Ref info label
        addToContent(refInfoLabel, below: lastView!, topPadding: 6)
        lastView = refInfoLabel

        // CFG slider
        let cfgTitle = UILabel()
        cfgTitle.font = UIFont.systemFont(ofSize: 14, weight: .medium)
        cfgTitle.text = L.Tts.labelCfg.loc

        let cfgRow = UIStackView(arrangedSubviews: [cfgTitle, cfgValueLabel])
        cfgRow.axis = .horizontal
        cfgRow.distribution = .equalSpacing
        cfgRow.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(cfgRow)
        NSLayoutConstraint.activate([
            cfgRow.topAnchor.constraint(equalTo: lastView!.bottomAnchor, constant: 20),
            cfgRow.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: padding),
            cfgRow.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -padding),
        ])
        lastView = cfgRow

        addToContent(cfgSlider, below: lastView!, topPadding: 4)
        lastView = cfgSlider

        // Timesteps slider
        let tsTitle = UILabel()
        tsTitle.font = UIFont.systemFont(ofSize: 14, weight: .medium)
        tsTitle.text = L.Tts.labelTimesteps.loc

        let tsRow = UIStackView(arrangedSubviews: [tsTitle, timestepsValueLabel])
        tsRow.axis = .horizontal
        tsRow.distribution = .equalSpacing
        tsRow.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(tsRow)
        NSLayoutConstraint.activate([
            tsRow.topAnchor.constraint(equalTo: lastView!.bottomAnchor, constant: 16),
            tsRow.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: padding),
            tsRow.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -padding),
        ])
        lastView = tsRow

        addToContent(timestepsSlider, below: lastView!, topPadding: 4)
        lastView = timestepsSlider

        timestepsHintLabel.text = L.Tts.hintTimestepsHigh.loc
        addToContent(timestepsHintLabel, below: lastView!, topPadding: 4)
        lastView = timestepsHintLabel

        // Generate button
        let genRow = UIStackView(arrangedSubviews: [generateBtn, progressIndicator])
        genRow.axis = .horizontal
        genRow.spacing = 12
        genRow.alignment = .center
        genRow.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(genRow)
        generateBtn.setTitle(L.Tts.btnGenerate.loc, for: .normal)
        generateBtn.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            genRow.topAnchor.constraint(equalTo: lastView!.bottomAnchor, constant: 24),
            genRow.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: padding),
            genRow.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -padding),
            generateBtn.heightAnchor.constraint(equalToConstant: 44)
        ])
        lastView = genRow

        // Status label
        addToContent(statusLabel, below: lastView!, topPadding: 8)
        lastView = statusLabel

        // Playback card
        addToContent(playbackCard, below: lastView!, topPadding: 16, height: 100)
        lastView = playbackCard
        setupPlaybackCard()

        lastView!.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -32).isActive = true
    }

    private func addToContent(_ view: UIView, below previous: UIView?, topPadding: CGFloat, height: CGFloat? = nil) {
        view.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(view)
        let topAnchor: NSLayoutYAxisAnchor = previous?.bottomAnchor ?? contentView.topAnchor
        var constraints: [NSLayoutConstraint] = [
            view.topAnchor.constraint(equalTo: topAnchor, constant: topPadding),
            view.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 16),
            view.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -16),
        ]
        if let h = height {
            constraints.append(view.heightAnchor.constraint(equalToConstant: h))
        }
        NSLayoutConstraint.activate(constraints)
    }

    private func setupPlaybackCard() {
        let pTitle = UILabel()
        pTitle.font = UIFont.systemFont(ofSize: 13, weight: .medium)
        pTitle.text = L.Tts.playbackTitle.loc

        let controls = UIStackView(arrangedSubviews: [playPauseBtn, playbackSlider])
        controls.axis = .horizontal
        controls.spacing = 12
        controls.alignment = .center

        let stack = UIStackView(arrangedSubviews: [pTitle, controls, playbackTimeLabel])
        stack.axis = .vertical
        stack.spacing = 8
        stack.translatesAutoresizingMaskIntoConstraints = false
        playbackCard.addSubview(stack)

        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: playbackCard.topAnchor, constant: 12),
            stack.leadingAnchor.constraint(equalTo: playbackCard.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: playbackCard.trailingAnchor, constant: -16),
            stack.bottomAnchor.constraint(equalTo: playbackCard.bottomAnchor, constant: -12),
        ])
    }

    // MARK: - Listeners

    private func setupListeners() {
        cfgSlider.addTarget(self, action: #selector(cfgChanged), for: .valueChanged)
        timestepsSlider.addTarget(self, action: #selector(timestepsChanged), for: .valueChanged)
        generateBtn.addTarget(self, action: #selector(generateTapped), for: .touchUpInside)
        recordBtn.addTarget(self, action: #selector(recordTapped), for: .touchUpInside)
        playRefBtn.addTarget(self, action: #selector(playRefTapped), for: .touchUpInside)
        clearRefBtn.addTarget(self, action: #selector(clearRefTapped), for: .touchUpInside)
        presetFemaleBtn.addTarget(self, action: #selector(presetFemaleTapped), for: .touchUpInside)
        presetMaleBtn.addTarget(self, action: #selector(presetMaleTapped), for: .touchUpInside)
        playPauseBtn.addTarget(self, action: #selector(playPauseTapped), for: .touchUpInside)
        playbackSlider.addTarget(self, action: #selector(playbackSliderChanged), for: .valueChanged)

        let tapGesture = UITapGestureRecognizer(target: self, action: #selector(dismissKeyboard))
        view.addGestureRecognizer(tapGesture)
    }

    @objc private func dismissKeyboard() {
        view.endEditing(true)
    }

    // MARK: - Engine state observation

    private func observeEngine() {
        engine.$state
            .receive(on: DispatchQueue.main)
            .sink { [weak self] state in
                self?.onEngineStateChanged(state)
            }
            .store(in: &cancellables)
    }

    private func onEngineStateChanged(_ state: TtsState) {
        switch state {
        case .uninitialized, .ready:
            isGenerating = false
            generateBtn.isEnabled = true
            generateBtn.setTitle(L.Tts.btnGenerate.loc, for: .normal)
            progressIndicator.stopAnimating()
            statusLabel.isHidden = true
        case .loadingModel:
            generateBtn.isEnabled = false
            progressIndicator.startAnimating()
            statusLabel.isHidden = false
            statusLabel.text = L.Tts.statusLoadingModel.loc
        case .generating:
            isGenerating = true
            generateBtn.isEnabled = true
            generateBtn.setTitle(L.Tts.btnCancel.loc, for: .normal)
            generateBtn.backgroundColor = .systemRed
            progressIndicator.startAnimating()
            statusLabel.isHidden = false
            statusLabel.text = L.Tts.statusGenerating.loc
        case .error(let error):
            isGenerating = false
            generateBtn.isEnabled = true
            generateBtn.setTitle(L.Tts.btnGenerate.loc, for: .normal)
            generateBtn.backgroundColor = .systemBlue
            progressIndicator.stopAnimating()
            statusLabel.isHidden = false
            statusLabel.text = error.localizedDescription
        }
    }

    // MARK: - Model loading

    private func checkModelAndLoad() {
        guard engine.modelsExist() else {
            promptDownloadModels()
            return
        }
        let hud = MBHUD.showAdded(to: view, animated: true)
        hud.label.text = L.Tts.statusLoadingModel.loc
        Task {
            let ok = await engine.loadModel()
            DispatchQueue.main.async {
                hud.hide(animated: true)
            }
            if !ok {
                let alert = UIAlertController(title: L.Common.error.loc,
                                              message: L.Tts.alertModelMissing.loc,
                                              preferredStyle: .alert)
                alert.addAction(UIAlertAction(title: L.Tts.alertGoDownload.loc, style: .default) { [weak self] _ in
                    self?.openModelManager()
                })
                alert.addAction(UIAlertAction(title: L.Common.cancel.loc, style: .cancel))
                present(alert, animated: true)
            }
        }
    }

    private func promptDownloadModels() {
        let alert = UIAlertController(title: L.Common.error.loc,
                                      message: L.Tts.alertModelMissing.loc,
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: L.Tts.alertGoDownload.loc, style: .default) { [weak self] _ in
            self?.openModelManager()
        })
        alert.addAction(UIAlertAction(title: L.Common.cancel.loc, style: .cancel))
        present(alert, animated: true)
    }

    // MARK: - Recording

    @objc private func recordTapped() {
        if isRecording {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private func startRecording() {
        recorder.requestPermission { [weak self] granted in
            guard let self = self else { return }
            if !granted {
                self.showToast(L.Tts.alertRecordPermissionDenied.loc)
                return
            }
            let url = URL(fileURLWithPath: NSTemporaryDirectory())
                .appendingPathComponent("tts_ref_\(Int(Date().timeIntervalSince1970)).wav")
            if self.recorder.startRecording(to: url) {
                self.isRecording = true
                self.recordBtn.setImage(UIImage(systemName: "stop.fill"), for: .normal)
                self.refInfoLabel.isHidden = false
                self.refInfoLabel.text = L.Tts.statusRecording.loc
                self.clearRefBtn.isHidden = true
                self.playRefBtn.isHidden = true
            }
        }
    }

    private func stopRecording() {
        _ = recorder.stopRecording()
        isRecording = false
        recordBtn.setImage(UIImage(systemName: "mic.fill"), for: .normal)

        // Find the recorded file
        let tmpDir = NSTemporaryDirectory()
        let fm = FileManager.default
        if let files = try? fm.contentsOfDirectory(atPath: tmpDir),
           let lastFile = files.filter({ $0.hasPrefix("tts_ref_") }).sorted().last {
            let url = URL(fileURLWithPath: tmpDir).appendingPathComponent(lastFile)
            let durMs = recorder.getDurationMs(url)
            let sizeKB = (try? fm.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 0
            referenceWavURL = url
            refInfoLabel.isHidden = false
            refInfoLabel.text = String(format: L.Tts.statusRefRecorded.loc, Double(durMs) / 1000.0, Int(sizeKB / 1024))
            clearRefBtn.isHidden = false
            playRefBtn.isHidden = false
        }
    }

    // MARK: - Presets

    @objc private func presetFemaleTapped() { selectPreset("默认女声.wav") }
    @objc private func presetMaleTapped()   { selectPreset("默认男声.wav") }

    private func selectPreset(_ name: String) {
        if isRecording { stopRecording() }

        guard let bundleURL = Bundle.main.url(forResource: name, withExtension: nil) else {
            showToast(String(format: L.Tts.presetFailed.loc, name))
            return
        }

        let destURL = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("preset_\(name)")
        try? FileManager.default.removeItem(at: destURL)
        do {
            try FileManager.default.copyItem(at: bundleURL, to: destURL)
        } catch {
            showToast(String(format: L.Tts.presetFailed.loc, name))
            return
        }

        referenceWavURL = destURL
        let durMs = recorder.getDurationMs(destURL)
        let sizeKB = (try? FileManager.default.attributesOfItem(atPath: destURL.path)[.size] as? Int64) ?? 0
        refInfoLabel.isHidden = false
        refInfoLabel.text = String(format: L.Tts.statusRefRecorded.loc, Double(durMs) / 1000.0, Int(sizeKB / 1024))
        clearRefBtn.isHidden = false
        playRefBtn.isHidden = false
    }

    // MARK: - Reference audio playback

    @objc private func playRefTapped() {
        guard let url = referenceWavURL else { return }
        audioPlayer.play(url: url)
    }

    @objc private func clearRefTapped() {
        audioPlayer.stop()
        referenceWavURL = nil
        refInfoLabel.isHidden = true
        clearRefBtn.isHidden = true
        playRefBtn.isHidden = true
    }

    // MARK: - Parameter handlers

    @objc private func cfgChanged() {
        cfgValueLabel.text = String(format: "%.1f", cfgSlider.value)
    }

    @objc private func timestepsChanged() {
        let steps = Int(timestepsSlider.value)
        timestepsValueLabel.text = "\(steps)"
        timestepsHintLabel.isHidden = steps <= 8
    }

    // MARK: - Generation

    @objc private func generateTapped() {
        if isGenerating {
            cancelGeneration()
            return
        }

        let text = textView.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            showToast(L.Tts.alertTextEmpty.loc)
            return
        }

        let outputURL = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("tts_out_\(Int(Date().timeIntervalSince1970)).wav")

        let cfg = cfgSlider.value
        let steps = Int(timestepsSlider.value)
        let refPath = referenceWavURL?.path

        Task {
            let startTime = Date()
            let ok = await engine.generate(text: text, cfgValue: cfg, timesteps: steps,
                                            refWavPath: refPath, outputPath: outputURL.path)
            let elapsed = Date().timeIntervalSince(startTime)
            print("[VoxCPM2] 生成完成: \(ok ? "✅" : "❌") 耗时 \(String(format: "%.2f", elapsed))s, "
                  + "文本长度=\(text.count), cfg=\(String(format: "%.1f", cfg)), steps=\(steps), "
                  + "ref=\(refPath != nil ? "yes" : "voice-design")")
            DispatchQueue.main.async {
                self.onGenerationComplete(ok: ok, outputURL: outputURL)
            }
        }
    }

    private func cancelGeneration() {
        engine.cancelGeneration()
        isGenerating = false
        generateBtn.setTitle(L.Tts.btnGenerate.loc, for: .normal)
        generateBtn.backgroundColor = .systemBlue
        progressIndicator.stopAnimating()
        statusLabel.isHidden = true
        showToast(L.Tts.toastCancelled.loc)
    }

    private func onGenerationComplete(ok: Bool, outputURL: URL) {
        isGenerating = false
        generateBtn.setTitle(L.Tts.btnGenerate.loc, for: .normal)
        generateBtn.backgroundColor = .systemBlue
        progressIndicator.stopAnimating()

        if ok && FileManager.default.fileExists(atPath: outputURL.path) {
            generatedWavURL = outputURL
            playbackCard.isHidden = false
            playbackSlider.value = 0
            statusLabel.isHidden = true
            showToast(L.Tts.toastGenerateDone.loc)
        } else {
            statusLabel.isHidden = false
            statusLabel.text = L.Tts.toastGenerateFailed.loc
            showToast(L.Tts.toastGenerateFailed.loc)
        }
    }

    // MARK: - Playback

    @objc private func playPauseTapped() {
        guard let url = generatedWavURL else { return }
        if audioPlayer.isPlaying {
            audioPlayer.pause()
            playPauseBtn.setImage(UIImage(systemName: "play.fill"), for: .normal)
        } else {
            audioPlayer.play(url: url)
            playPauseBtn.setImage(UIImage(systemName: "pause.fill"), for: .normal)
            audioPlayer.onProgress = { [weak self] progress in
                self?.playbackSlider.value = progress
                self?.updatePlaybackTime(progress: progress)
            }
            audioPlayer.onComplete = { [weak self] in
                self?.onPlaybackComplete()
            }
        }
    }

    @objc private func playbackSliderChanged() {
        audioPlayer.seek(to: playbackSlider.value / 100.0)
        updatePlaybackTime(progress: playbackSlider.value)
    }

    private func onPlaybackComplete() {
        playPauseBtn.setImage(UIImage(systemName: "play.fill"), for: .normal)
        playbackSlider.value = 100
    }

    private func updatePlaybackTime(progress: Float) {
        let dur = audioPlayer.duration
        let cur = dur * Double(progress) / 100.0
        let cm = Int(cur) / 60
        let cs = Int(cur) % 60
        let dm = Int(dur) / 60
        let ds = Int(dur) % 60
        playbackTimeLabel.text = String(format: "%d:%02d / %d:%02d", cm, cs, dm, ds)
    }

    // MARK: - Helpers

    private func showToast(_ message: String) {
        let hud = MBHUD.showAdded(to: view, animated: true)
        hud.mode = .text
        hud.label.text = message
        hud.hide(animated: true, afterDelay: 2.0)
    }
}
