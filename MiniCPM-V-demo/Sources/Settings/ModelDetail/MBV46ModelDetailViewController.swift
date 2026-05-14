//
//  MBV46ModelDetailViewController.swift
//  MiniCPM-V-demo
//
//  V4.6 模型详情页面 VC（下载管理 + 使用该模型）
//

import Foundation
import UIKit
import SnapKit
import llama

@objc public class MBV46ModelDetailViewController: UIViewController, UIGestureRecognizerDelegate {
    
    var modelName: String = "MiniCPM-V 4.6"
    
    private let downloadManager = MBV46ModelDownloadManager.shared
    
    private var mtmdWrapperExample: MTMDWrapperExample?
    
    lazy var tableView: UITableView = {
        let tv = UITableView(frame: .zero, style: .grouped)
        tv.register(MBSettingsTableViewCell.self, forCellReuseIdentifier: "MBSettingsTableViewCell")
        tv.estimatedRowHeight = 48
        tv.separatorStyle = .none
        tv.separatorColor = .clear
        tv.dataSource = self
        tv.delegate = self
        return tv
    }()
    
    var dataArray = [MBSettingsModel]()
    
    lazy var useModelButton: UIButton = {
        let button = UIButton(type: .system)
        button.setTitle("使用该模型", for: .normal)
        button.setTitleColor(.white, for: .normal)
        button.backgroundColor = UIColor.mb_color(with: "#007AFF")
        button.layer.cornerRadius = 8
        button.titleLabel?.font = UIFont.systemFont(ofSize: 16, weight: .medium)
        button.addTarget(self, action: #selector(useModelButtonTapped), for: .touchUpInside)
        return button
    }()
    
    // MARK: - view life cycle
    
    init() {
        super.init(nibName: nil, bundle: nil)
    }
    
    init(with wrapper: MTMDWrapperExample) {
        self.mtmdWrapperExample = wrapper
        super.init(nibName: nil, bundle: nil)
    }
    
    required init?(coder: NSCoder) {
        super.init(coder: coder)
    }
    
    public override func viewDidLoad() {
        super.viewDidLoad()
        
        setupSubViews()
        setupDownloadManager()
        loadTableViewData()
        setupDownloadManagerCallbacks()
        updateUseModelButtonState()
        
        UIApplication.shared.isIdleTimerDisabled = true
        self.navigationController?.interactivePopGestureRecognizer?.isEnabled = true
        self.navigationController?.interactivePopGestureRecognizer?.delegate = self
    }
    
    public override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        UIApplication.shared.isIdleTimerDisabled = false
    }
    
    public func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        return self.navigationController?.viewControllers.count ?? 0 > 1
    }
    
    // MARK: - UI
    
    func setupSubViews() {
        self.title = modelName
        
        let titleDict: [NSAttributedString.Key : Any] = [NSAttributedString.Key.foregroundColor: UIColor.black]
        self.navigationController?.navigationBar.titleTextAttributes = titleDict
        self.view.backgroundColor = UIColor.mb_color(with: "#F9FAFC")
        
        setupNavView()
        
        tableView.sectionHeaderTopPadding = 0
        tableView.backgroundColor = UIColor.mb_color(with: "#F6F6F6")
        tableView.contentInsetAdjustmentBehavior = .never
        tableView.contentInset = UIEdgeInsets(top: 20, left: 0, bottom: 0, right: 0)
        
        view.addSubview(tableView)
        view.addSubview(useModelButton)
        
        tableView.snp.makeConstraints { make in
            make.top.equalTo(self.view.safeAreaLayoutGuide.snp.top)
            make.left.right.equalTo(self.view)
            make.bottom.equalTo(useModelButton.snp.top).offset(-20)
        }
        
        useModelButton.snp.makeConstraints { make in
            make.left.equalTo(self.view).offset(20)
            make.right.equalTo(self.view).offset(-20)
            make.bottom.equalTo(self.view.safeAreaLayoutGuide.snp.bottom).offset(-20)
            make.height.equalTo(50)
        }
    }
    
    func setupNavView() {
        let img = UIImage(systemName: "chevron.left")
        let leftNavIcon = UIBarButtonItem(image: img,
                                          style: .plain,
                                          target: self,
                                          action: #selector(handleLeftNavIcon))
        leftNavIcon.tintColor = .black
        self.navigationItem.leftBarButtonItem = leftNavIcon
        
        let refreshImg = UIImage(systemName: "arrow.clockwise")
        let rightNavButton = UIBarButtonItem(image: refreshImg,
                                            style: .plain,
                                            target: self,
                                            action: #selector(handleRightNavButton))
        rightNavButton.tintColor = .black
        rightNavButton.accessibilityLabel = "重新下载"
        rightNavButton.accessibilityHint = "删除所有已下载的模型文件并重新下载"
        self.navigationItem.rightBarButtonItem = rightNavButton
        
        self.navigationController?.setNavigationBackgroundColor(UIColor.mb_color(with: "#F9FAFC") ?? .white)
    }
    
    @objc public func handleLeftNavIcon() {
        self.navigationController?.popViewController(animated: true)
    }
    
    @objc public func handleRightNavButton() {
        showRedownloadAlert()
    }
    
    @objc public func useModelButtonTapped() {
        switch currentButtonState() {
        case .needsDownload:
            downloadManager.downloadAll()
            updateUseModelButtonState()
        case .downloading:
            break
        case .ready:
            setAsCurrentModel()
        }
    }

    private enum MainButtonState { case needsDownload, downloading, ready }

    private func currentButtonState() -> MainButtonState {
        if checkAllModelsDownloaded() { return .ready }
        if downloadManager.hasAnyDownloadActive() { return .downloading }
        return .needsDownload
    }
    
    private func showRedownloadAlert() {
        let alert = UIAlertController(title: "重新下载",
                                     message: "这将删除所有已下载的模型文件和缓存中的临时文件，然后重新下载。确定要继续吗？",
                                     preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "确定", style: .destructive) { [weak self] _ in
            self?.performRedownload()
        })
        present(alert, animated: true)
    }
    
    private func performRedownload() {
        let hud = MBHUD.showAdded(to: self.view, animated: true)
        hud.label.text = "正在清理文件..."
        
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            self.deleteAllDownloadedFiles()
            self.cleanupCacheFiles()
            self.resetDownloadStates()
            
            DispatchQueue.main.async {
                hud.hide(animated: true)
                self.loadTableViewData()
                let successHud = MBHUD.showAdded(to: self.view, animated: true)
                successHud.mode = .text
                successHud.label.text = "清理完成，可以重新下载"
                successHud.hide(animated: true, afterDelay: 2.0)
            }
        }
    }
    
    private func deleteAllDownloadedFiles() {
        let fileManager = FileManager.default
        let documentsPath = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
        
        let filesToDelete = [
            MiniCPMModelConst.modelv46_FileName,
            MiniCPMModelConst.mmprojv46_FileName,
            MiniCPMModelConst.mlmodelcv46_ZipFileName
        ]
        for fileName in filesToDelete {
            let fileURL = documentsPath.appendingPathComponent(fileName)
            if fileManager.fileExists(atPath: fileURL.path) {
                try? fileManager.removeItem(at: fileURL)
            }
        }
        
        for candidate in MiniCPMModelConst.mlmodelcv46_CandidateFileNames {
            let dirURL = documentsPath.appendingPathComponent(candidate)
            if fileManager.fileExists(atPath: dirURL.path) {
                try? fileManager.removeItem(at: dirURL)
            }
        }
    }
    
    private func cleanupCacheFiles() {
        let fileManager = FileManager.default
        let cachePath = fileManager.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let tmpPath = URL(fileURLWithPath: NSTemporaryDirectory())
        
        let filesToClean = [
            MiniCPMModelConst.modelv46_FileName,
            MiniCPMModelConst.mmprojv46_FileName,
            MiniCPMModelConst.mlmodelcv46_ZipFileName
        ]
        for fileName in filesToClean {
            let cacheFileURL = cachePath.appendingPathComponent(fileName)
            if fileManager.fileExists(atPath: cacheFileURL.path) {
                try? fileManager.removeItem(at: cacheFileURL)
            }
            let tmpFileURL = tmpPath.appendingPathComponent(fileName)
            if fileManager.fileExists(atPath: tmpFileURL.path) {
                try? fileManager.removeItem(at: tmpFileURL)
            }
        }
    }
    
    private func resetDownloadStates() {
        downloadManager.resetDownloadStates()
        FDownLoaderManager.shareInstance().downLoaderInfo.removeAllObjects()
        downloadManager.mainModelManager?.status = "download"
        downloadManager.vitModelManager?.status = "download"
        downloadManager.aneModelManager?.status = "download"
    }
    
    public override func viewWillTransition(to size: CGSize, with coordinator: any UIViewControllerTransitionCoordinator) {
        self.tableView.reloadData()
    }
    
    // MARK: - 下载管理器
    
    private func setupDownloadManager() {
        if let mtmdWrapperExample = mtmdWrapperExample {
            downloadManager.setupDownloadManager(with: mtmdWrapperExample)
        } else {
            let alert = UIAlertController(title: "错误", message: "未传入 llamaState，无法初始化下载管理器。", preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "确定", style: .default, handler: { _ in
                self.navigationController?.popViewController(animated: true)
            }))
            self.present(alert, animated: true, completion: nil)
        }
    }
    
    private func setupDownloadManagerCallbacks() {
        downloadManager.progressHandler = { [weak self] modelName, progress in
            DispatchQueue.main.async {
                self?.updateCellProgress(modelName: modelName, progress: progress)
            }
        }
        downloadManager.completionHandler = { [weak self] modelName, success in
            DispatchQueue.main.async {
                self?.updateCellCompletion(modelName: modelName, success: success)
            }
        }
        downloadManager.detailedProgressHandler = { [weak self] progressInfo in
            DispatchQueue.main.async {
                self?.updateCellDetailedProgress(progressInfo: progressInfo)
            }
        }
    }
    
    // MARK: - Cell 状态更新
    
    private func updateCellProgress(modelName: String, progress: CGFloat) {
        for (index, model) in dataArray.enumerated() where model.title == modelName {
            if progress >= 1.0 {
                model.statusString = "已下载"
            } else if progress > 0 {
                model.statusString = "\(Int(progress * 100))%"
            } else {
                model.statusString = "下载中..."
            }
            let indexPath = IndexPath(row: index, section: 0)
            if let cell = tableView.cellForRow(at: indexPath) as? MBSettingsTableViewCell {
                cell.configure(with: model)
            }
            break
        }
        updateUseModelButtonState()
    }
    
    private func updateCellCompletion(modelName: String, success: Bool) {
        for (index, model) in dataArray.enumerated() where model.title == modelName {
            model.statusString = success ? "已下载" : "下载失败"
            let indexPath = IndexPath(row: index, section: 0)
            if let cell = tableView.cellForRow(at: indexPath) as? MBSettingsTableViewCell {
                cell.configure(with: model)
            }
            let hud = MBHUD.showAdded(to: self.view, animated: true)
            hud.mode = .text
            hud.label.text = "\(modelName) \(success ? "下载成功" : "下载失败")"
            hud.hide(animated: true, afterDelay: 2.0)
            updateUseModelButtonState()
            break
        }
    }
    
    private func updateCellDetailedProgress(progressInfo: DownloadProgressInfo) {
        for (index, model) in dataArray.enumerated() where model.title == progressInfo.modelName {
            switch progressInfo.status {
            case .notStarted:  model.statusString = "未下载"
            case .downloading: model.statusString = "\(Int(progressInfo.progress * 100))%"
            case .paused:      model.statusString = "已暂停"
            case .completed:   model.statusString = "已下载"
            case .failed:      model.statusString = "下载失败"
            }
            let indexPath = IndexPath(row: index, section: 0)
            if let cell = tableView.cellForRow(at: indexPath) as? MBSettingsTableViewCell {
                cell.configure(with: model)
            }
            break
        }
        updateUseModelButtonState()
    }
}

// MARK: - UITableViewDataSource
extension MBV46ModelDetailViewController: UITableViewDataSource {
    public func numberOfSections(in tableView: UITableView) -> Int { 1 }
    
    public func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return dataArray.count
    }
    
    public func tableView(_ tableView: UITableView, heightForRowAt indexPath: IndexPath) -> CGFloat { 48 }
    
    public func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "MBSettingsTableViewCell", for: indexPath) as! MBSettingsTableViewCell
        cell.configure(with: dataArray[indexPath.row])
        return cell
    }
    
    public func tableView(_ tableView: UITableView, heightForHeaderInSection section: Int) -> CGFloat { 0 }
    public func tableView(_ tableView: UITableView, viewForHeaderInSection section: Int) -> UIView? { nil }
}

// MARK: - UITableViewDelegate
extension MBV46ModelDetailViewController: UITableViewDelegate {
    public func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let model = dataArray[indexPath.row]
        guard let title = model.title else { return }
        
        switch title {
        case MiniCPMModelConst.modelv46_DisplayedName:
            handleModelDownload(modelName: title) { [weak self] in
                self?.downloadManager.downloadModelv46_Q4_K_M()
            }
        case MiniCPMModelConst.modelMMProjv46_DisplayedName:
            handleModelDownload(modelName: title) { [weak self] in
                self?.downloadManager.downloadMMProjv46()
            }
        // ANE/CoreML 包默认禁用，UI 不展示，理论上走不到这里。保留 case 待恢复时取消注释。
        // case MiniCPMModelConst.mlmodelcv46_DisplayedName:
        //     handleModelDownload(modelName: title) { [weak self] in
        //         self?.downloadManager.downloadMLModelcv46()
        //     }
        default:
            break
        }
    }
    
    private func handleModelDownload(modelName: String, downloadAction: @escaping () -> Void) {
        if checkIfModelDownloaded(modelName: modelName) {
            let alert = UIAlertController(title: "模型已下载", message: "\(modelName) 已经下载完成，无需重复下载", preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "确定", style: .default))
            present(alert, animated: true)
        } else {
            downloadAction()
        }
    }
    
    private func checkIfModelDownloaded(modelName: String) -> Bool {
        switch modelName {
        case MiniCPMModelConst.modelv46_DisplayedName:
            return downloadManager.getModelv46_Q4_K_M_Status() == "downloaded"
        case MiniCPMModelConst.modelMMProjv46_DisplayedName:
            return downloadManager.getMMProjv46_Status() == "downloaded"
        // case MiniCPMModelConst.mlmodelcv46_DisplayedName:
        //     return downloadManager.getMLModelcv46_Status() == "downloaded"
        default:
            return false
        }
    }
}

// MARK: - 数据配置 / 使用模型
extension MBV46ModelDetailViewController {
    
    public func loadTableViewData() {
        dataArray.removeAll()
        
        let languageModel = MBSettingsModel()
        languageModel.title = MiniCPMModelConst.modelv46_DisplayedName
        languageModel.icon = UIImage(systemName: "cpu")
        languageModel.statusString = getInitialStatus(for: downloadManager.getModelv46_Q4_K_M_Status())
        languageModel.shouldShowStatusText = true
        dataArray.append(languageModel)
        
        let multimodalModel = MBSettingsModel()
        multimodalModel.title = MiniCPMModelConst.modelMMProjv46_DisplayedName
        multimodalModel.icon = UIImage(systemName: "cpu")
        multimodalModel.statusString = getInitialStatus(for: downloadManager.getMMProjv46_Status())
        multimodalModel.shouldShowStatusText = true
        dataArray.append(multimodalModel)

        // ANE/CoreML 模块当前默认禁用：mtmd_coreml.mm 已切到 MLComputeUnitsCPUAndGPU（走 Metal 不走 ANE），
        // ggml/Metal 路径可独立完成 ViT+merger，不再要求用户额外下载 ~1 GB 的 mlmodelc 包。
        // 旧版用户磁盘上残留的 mlmodelc 仍会被 MiniCPMV46CoreMLBootstrap.resolvedCoreMLPathInDocuments() 自动 pick up，
        // 走 CoreML/Metal 路径（同样不走 ANE）。以后想恢复入口时只需取消注释。
        /*
        let aneModel = MBSettingsModel()
        aneModel.title = MiniCPMModelConst.mlmodelcv46_DisplayedName
        aneModel.icon = UIImage(systemName: "cpu")
        aneModel.statusString = getInitialStatus(for: downloadManager.getMLModelcv46_Status())
        aneModel.shouldShowStatusText = true
        dataArray.append(aneModel)
        */

        tableView.reloadData()
    }
    
    private func getInitialStatus(for downloadStatus: String) -> String {
        switch downloadStatus {
        case "downloaded":  return "已下载"
        case "downloading": return "下载中..."
        case "failed":      return "下载失败"
        default:            return "未下载"
        }
    }
    
    /// LLM + VPM 就绪即允许使用（ANE/CoreML 包已默认禁用，参见 loadTableViewData 注释）。
    /// 调用前先按磁盘 reconcile，避免 helper.status 因 callback race 卡住与磁盘不一致
    private func checkAllModelsDownloaded() -> Bool {
        downloadManager.reconcileStatusFromDisk()
        return downloadManager.getModelv46_Q4_K_M_Status() == "downloaded" &&
               downloadManager.getMMProjv46_Status()       == "downloaded"
               // && downloadManager.getMLModelcv46_Status() == "downloaded"  // ANE 暂禁用，恢复时取消注释
    }

    /// 三态主按钮刷新
    fileprivate func updateUseModelButtonState() {
        switch currentButtonState() {
        case .needsDownload:
            useModelButton.isEnabled = true
            useModelButton.setTitle("一键下载（约 1.6 GB）", for: .normal)
            useModelButton.backgroundColor = UIColor.mb_color(with: "#007AFF")
            useModelButton.setTitleColor(.white, for: .normal)
        case .downloading:
            useModelButton.isEnabled = false
            let percent = Int(downloadManager.overallProgress() * 100)
            useModelButton.setTitle("下载中 \(percent)%", for: .normal)
            useModelButton.backgroundColor = UIColor.mb_color(with: "#CCCCCC")
            useModelButton.setTitleColor(.darkGray, for: .normal)
        case .ready:
            useModelButton.isEnabled = true
            useModelButton.setTitle("使用该模型", for: .normal)
            useModelButton.backgroundColor = UIColor.mb_color(with: "#007AFF")
            useModelButton.setTitleColor(.white, for: .normal)
        }
    }
    
    private func setAsCurrentModel() {
        mtmdWrapperExample?.currentUsingModelType = .V46MultiModel
        UserDefaults.standard.setValue("V46MultiModel", forKey: "current_selected_model")

        // Tell the home VC the selection just changed so it can reset the
        // wrapper and reload — otherwise its one-shot didStartInitialModelLoad
        // flag suppresses every reload after the first viewDidAppear.
        NotificationCenter.default.post(name: .mbModelSelectionChanged, object: nil)

        let hud = MBHUD.showAdded(to: self.view, animated: true)
        hud.mode = .text
        hud.label.text = "已设置 \(modelName) 为当前模型"
        hud.hide(animated: true, afterDelay: 2.0)
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
            self.navigationController?.popViewController(animated: true)
        }
    }
}
