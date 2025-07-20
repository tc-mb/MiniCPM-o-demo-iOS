//
//  MBV26ModelDetailViewController.swift
//  MiniCPM-demo
//
//  Created by Assistant on 2024/12/19.
//

import Foundation
import UIKit
import SnapKit

/// v2.6模型详情页面 VC
@objc public class MBV26ModelDetailViewController: UIViewController, UIGestureRecognizerDelegate {
    
    /// 模型名称
    var modelName: String = "MiniCPM-V2.6 8B"
    
    /// V26 下载管理器
    private let downloadManager = MBV26ModelDownloadManager.shared
    
    /// 外部传入的 llamaState 引用
    private var mtmdWrapperExample: MTMDWrapperExample?
    
    /// 一个列表
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
    
    /// 列表对应的数据源
    var dataArray = [MBSettingsModel]()
    
    /// 使用该模型按钮
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
    
    /// 带 llamaState 的初始化方法
    init(with wrapper: MTMDWrapperExample) {
        self.mtmdWrapperExample = wrapper
        super.init(nibName: nil, bundle: nil)
    }
    
    required init?(coder: NSCoder) {
        super.init(coder: coder)
    }
    
    public override func viewDidLoad() {
        super.viewDidLoad()
        
        // step 1, create ui
        setupSubViews()
        
        // step 2, 初始化下载管理器
        setupDownloadManager()
        
        // step 3, 配置 UI 数据
        loadTableViewData()
        
        // step 4, 设置下载管理器回调
        setupDownloadManagerCallbacks()
        
        // step 5, 初始化按钮状态
        updateUseModelButtonState()
        
        // 禁止熄屏
        UIApplication.shared.isIdleTimerDisabled = true
        
        // Enable the interactive pop gesture recognizer
        self.navigationController?.interactivePopGestureRecognizer?.isEnabled = true
        self.navigationController?.interactivePopGestureRecognizer?.delegate = self
        
        testNetwork()
    }

    public override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)

        // 允许熄屏
        UIApplication.shared.isIdleTimerDisabled = false
    }
    
    public func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        return self.navigationController?.viewControllers.count ?? 0 > 1
    }
    
    // MARK: - 创建子视图
    
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
        
        // 添加重新下载按钮
        let refreshImg = UIImage(systemName: "arrow.clockwise")
        let rightNavButton = UIBarButtonItem(image: refreshImg,
                                            style: .plain,
                                            target: self,
                                            action: #selector(handleRightNavButton))
        rightNavButton.tintColor = .black
        rightNavButton.accessibilityLabel = "重新下载"
        rightNavButton.accessibilityHint = "删除所有已下载的模型文件并重新下载"
        self.navigationItem.rightBarButtonItem = rightNavButton
        
        // 白色顶导
        self.navigationController?.setNavigationBackgroundColor(UIColor.mb_color(with: "#F9FAFC") ?? .white)
    }

    // MARK: - 顶导返回按钮 点击 事件
    
    @objc public func handleLeftNavIcon() {
        self.navigationController?.popViewController(animated: true)
    }
    
    // MARK: - 重新下载按钮 点击 事件
    
    @objc public func handleRightNavButton() {
        showRedownloadAlert()
    }
    
    // MARK: - 使用该模型按钮 点击 事件
    
    @objc public func useModelButtonTapped() {
        // 检查是否全部下载完成
        guard checkAllModelsDownloaded() else {
            let alert = UIAlertController(title: "提示", message: "请先下载完成所有模型组件", preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "确定", style: .default))
            present(alert, animated: true)
            return
        }
        
        // 设置为当前使用的模型
        setAsCurrentModel()
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
        // 显示加载提示
        let hud = MBHUD.showAdded(to: self.view, animated: true)
        hud.label.text = "正在清理文件..."
        
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            
            // 1. 删除所有已下载的模型文件
            self.deleteAllDownloadedFiles()
            
            // 2. 清理缓存中的临时文件
            self.cleanupCacheFiles()
            
            // 3. 重置下载状态
            self.resetDownloadStates()
            
            DispatchQueue.main.async {
                hud.hide(animated: true)
                
                // 4. 重新加载UI数据
                self.loadTableViewData()
                
                // 5. 显示成功提示
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
        
        // 删除V26相关的所有文件
        let filesToDelete = [
            MiniCPMModelConst.modelQ4_K_MFileName,
            MiniCPMModelConst.mmprojFileName,
            MiniCPMModelConst.mlmodelcZipFileName
        ]
        
        for fileName in filesToDelete {
            let fileURL = documentsPath.appendingPathComponent(fileName)
            if fileManager.fileExists(atPath: fileURL.path) {
                do {
                    try fileManager.removeItem(at: fileURL)
                    debugLog("-->> 删除文件成功: \(fileName)")
                } catch {
                    debugLog("-->> 删除文件失败: \(fileName), 错误: \(error.localizedDescription)")
                }
            }
        }
        
        // 删除解压后的.mlmodelc文件夹
        let mlmodelcFolderName = "ane_MiniCPM-V-2_6_3_f32_b1.mlmodelc"
        let mlmodelcFolderURL = documentsPath.appendingPathComponent(mlmodelcFolderName)
        if fileManager.fileExists(atPath: mlmodelcFolderURL.path) {
            do {
                try fileManager.removeItem(at: mlmodelcFolderURL)
                debugLog("-->> 删除文件夹成功: \(mlmodelcFolderName)")
            } catch {
                debugLog("-->> 删除文件夹失败: \(mlmodelcFolderName), 错误: \(error.localizedDescription)")
            }
        }
    }
    
    private func cleanupCacheFiles() {
        let fileManager = FileManager.default
        let cachePath = fileManager.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let tmpPath = URL(fileURLWithPath: NSTemporaryDirectory())
        
        // 清理缓存目录中的临时文件
        let filesToClean = [
            MiniCPMModelConst.modelQ4_K_MFileName,
            MiniCPMModelConst.mmprojFileName,
            MiniCPMModelConst.mlmodelcZipFileName
        ]
        
        for fileName in filesToClean {
            // 清理缓存目录
            let cacheFileURL = cachePath.appendingPathComponent(fileName)
            if fileManager.fileExists(atPath: cacheFileURL.path) {
                do {
                    try fileManager.removeItem(at: cacheFileURL)
                    debugLog("-->> 清理缓存文件成功: \(fileName)")
                } catch {
                    debugLog("-->> 清理缓存文件失败: \(fileName), 错误: \(error.localizedDescription)")
                }
            }
            
            // 清理临时目录
            let tmpFileURL = tmpPath.appendingPathComponent(fileName)
            if fileManager.fileExists(atPath: tmpFileURL.path) {
                do {
                    try fileManager.removeItem(at: tmpFileURL)
                    debugLog("-->> 清理临时文件成功: \(fileName)")
                } catch {
                    debugLog("-->> 清理临时文件失败: \(fileName), 错误: \(error.localizedDescription)")
                }
            }
        }
    }
    
    private func resetDownloadStates() {
        // 重置下载管理器的状态
        downloadManager.resetDownloadStates()
        
        // 清理FDownLoaderManager中的下载信息
        FDownLoaderManager.shareInstance().downLoaderInfo.removeAllObjects()
        
        // 重置各个模型的状态
        downloadManager.mainModelManager?.status = "download"
        downloadManager.vitModelManager?.status = "download"
        downloadManager.aneModelManager?.status = "download"
    }
    
    public override func viewWillTransition(to size: CGSize, with coordinator: any UIViewControllerTransitionCoordinator) {
        self.tableView.reloadData()
    }
    
    // MARK: - 下载管理器设置
    
    private func setupDownloadManager() {
        // 使用传入的 llamaState
        if let mtmdWrapperExample = mtmdWrapperExample {
            downloadManager.setupDownloadManager(with: mtmdWrapperExample)
        } else {
            // 如果没有传入 llamaState，创建一个新的实例
            let alert = UIAlertController(title: "错误", message: "未传入 llamaState，无法初始化下载管理器。", preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "确定", style: .default, handler: { _ in
                self.navigationController?.popViewController(animated: true)
            }))
            self.present(alert, animated: true, completion: nil)
        }
    }
    
    // MARK: - 下载管理器回调设置
    
    private func setupDownloadManagerCallbacks() {
        // 设置进度回调
        downloadManager.progressHandler = { [weak self] modelName, progress in
            DispatchQueue.main.async {
                self?.updateCellProgress(modelName: modelName, progress: progress)
            }
        }
        
        // 设置完成回调
        downloadManager.completionHandler = { [weak self] modelName, success in
            DispatchQueue.main.async {
                self?.updateCellCompletion(modelName: modelName, success: success)
            }
        }
        
        // 设置详细进度回调
        downloadManager.detailedProgressHandler = { [weak self] progressInfo in
            DispatchQueue.main.async {
                self?.updateCellDetailedProgress(progressInfo: progressInfo)
            }
        }
    }
    
    // MARK: - 更新 Cell 状态
    
    private func updateCellProgress(modelName: String, progress: CGFloat) {
        let progressPercentage = String(format: "%.2f%%", progress * 100)
        debugLog("-->> V26 DetailVC: 收到进度回调 - 模型: \(modelName), 进度: \(progressPercentage)")
        
        for (index, model) in dataArray.enumerated() {
            if model.title == modelName {
                if progress >= 1.0 {
                    model.statusString = "已下载"
                } else if progress > 0 {
                    let percentage = Int(progress * 100)
                    model.statusString = "\(percentage)%"
                } else {
                    model.statusString = "下载中..."
                }
                
                // 刷新对应的 cell
                let indexPath = IndexPath(row: index, section: 0)
                if let cell = tableView.cellForRow(at: indexPath) as? MBSettingsTableViewCell {
                    cell.configure(with: model)
                }
                break
            }
        }
    }
    
    private func updateCellCompletion(modelName: String, success: Bool) {
        for (index, model) in dataArray.enumerated() {
            if model.title == modelName {
                if success {
                    model.statusString = "已下载"
                } else {
                    model.statusString = "下载失败"
                }
                
                // 刷新对应的 cell
                let indexPath = IndexPath(row: index, section: 0)
                if let cell = tableView.cellForRow(at: indexPath) as? MBSettingsTableViewCell {
                    cell.configure(with: model)
                }
                
                // 显示下载结果提示
                let hud = MBHUD.showAdded(to: self.view, animated: true)
                hud.mode = .text
                if success {
                    hud.label.text = "\(modelName) 下载成功"
                } else {
                    hud.label.text = "\(modelName) 下载失败"
                }
                hud.hide(animated: true, afterDelay: 2.0)
                
                // 更新按钮状态
                updateUseModelButtonState()
                
                break
            }
        }
    }
    
    private func updateCellDetailedProgress(progressInfo: V26DownloadProgressInfo) {
        for (index, model) in dataArray.enumerated() {
            if model.title == progressInfo.modelName {
                switch progressInfo.status {
                case .notStarted:
                    model.statusString = "未下载"
                case .downloading:
                    let percentage = Int(progressInfo.progress * 100)
                    model.statusString = "\(percentage)%"
                case .paused:
                    model.statusString = "已暂停"
                case .completed:
                    model.statusString = "已下载"
                case .failed:
                    model.statusString = "下载失败"
                }
                
                // 刷新对应的 cell
                let indexPath = IndexPath(row: index, section: 0)
                if let cell = tableView.cellForRow(at: indexPath) as? MBSettingsTableViewCell {
                    cell.configure(with: model)
                }
                break
            }
        }
    }
}

// MARK: - UITableViewDataSource
extension MBV26ModelDetailViewController: UITableViewDataSource {
    
    public func numberOfSections(in tableView: UITableView) -> Int {
        return 1
    }
    
    public func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return dataArray.count
    }
    
    public func tableView(_ tableView: UITableView, heightForRowAt indexPath: IndexPath) -> CGFloat {
        return 48
    }
    
    public func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "MBSettingsTableViewCell", for: indexPath) as! MBSettingsTableViewCell
        
        let model = dataArray[indexPath.row]
        cell.configure(with: model)
        
        return cell
    }
    
    public func tableView(_ tableView: UITableView, heightForHeaderInSection section: Int) -> CGFloat {
        return 0
    }
    
    public func tableView(_ tableView: UITableView, viewForHeaderInSection section: Int) -> UIView? {
        return nil
    }
}

// MARK: - UITableViewDelegate
extension MBV26ModelDetailViewController: UITableViewDelegate {
    
    public func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        
        let model = dataArray[indexPath.row]
        
        if let title = model.title {
            switch title {
            case MiniCPMModelConst.modelQ4_K_MDisplayedName:
                handleModelDownload(modelName: title, downloadAction: { [weak self] in
                    self?.downloadManager.downloadModelV26_Q4_K_M()
                })
            case MiniCPMModelConst.modelMMProjDisplayedName:
                handleModelDownload(modelName: title, downloadAction: { [weak self] in
                    self?.downloadManager.downloadMMProjV26()
                })
            case MiniCPMModelConst.mlmodelcZipFileDisplayedName:
                handleModelDownload(modelName: title, downloadAction: { [weak self] in
                    self?.downloadManager.downloadMLModelcV26()
                })
            default:
                break
            }
        }
    }
    
    private func handleModelDownload(modelName: String, downloadAction: @escaping () -> Void) {
        // 检查是否已下载
        let isDownloaded = checkIfModelDownloaded(modelName: modelName)
        
        if isDownloaded {
            // 已下载，显示提示
            let alert = UIAlertController(title: "模型已下载", message: "\(modelName) 已经下载完成，无需重复下载", preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "确定", style: .default))
            present(alert, animated: true)
        } else {
            // 未下载，开始下载
            downloadAction()
        }
    }
    
    private func checkIfModelDownloaded(modelName: String) -> Bool {
        switch modelName {
        case MiniCPMModelConst.modelQ4_K_MDisplayedName:
            return downloadManager.getModelV26_Q4_K_M_Status() == "downloaded"
        case MiniCPMModelConst.modelMMProjDisplayedName:
            return downloadManager.getMMProjV26_Status() == "downloaded"
        case MiniCPMModelConst.mlmodelcZipFileDisplayedName:
            return downloadManager.getMLModelcV26_Status() == "downloaded"
        default:
            return false
        }
    }
}

// MARK: - 数据配置
extension MBV26ModelDetailViewController {
    
    /// 配置列表数据用于展示 cell
    public func loadTableViewData() {
        dataArray.removeAll()
        
        // 语言模型
        let languageModel = MBSettingsModel()
        languageModel.title = MiniCPMModelConst.modelQ4_K_MDisplayedName
        languageModel.icon = UIImage(systemName: "cpu")
        languageModel.statusString = getInitialStatus(for: downloadManager.getModelV26_Q4_K_M_Status())
        languageModel.shouldShowStatusText = true
        dataArray.append(languageModel)
        
        // 多模态模型
        let multimodalModel = MBSettingsModel()
        multimodalModel.title = MiniCPMModelConst.modelMMProjDisplayedName
        multimodalModel.icon = UIImage(systemName: "cpu")
        multimodalModel.statusString = getInitialStatus(for: downloadManager.getMMProjV26_Status())
        multimodalModel.shouldShowStatusText = true
        dataArray.append(multimodalModel)
        
        // ANE
        let aneModel = MBSettingsModel()
        aneModel.title = MiniCPMModelConst.mlmodelcZipFileDisplayedName
        aneModel.icon = UIImage(systemName: "cpu")
        aneModel.statusString = getInitialStatus(for: downloadManager.getMLModelcV26_Status())
        aneModel.shouldShowStatusText = true
        dataArray.append(aneModel)
        
        tableView.reloadData()
    }
    
    private func getInitialStatus(for downloadStatus: String) -> String {
        switch downloadStatus {
        case "downloaded":
            return "已下载"
        case "downloading":
            return "下载中..."
        case "failed":
            return "下载失败"
        default:
            return "未下载"
        }
    }
    
    // MARK: - 模型使用相关方法
    
    /// 检查所有模型是否已下载完成
    private func checkAllModelsDownloaded() -> Bool {
        let mainModelStatus = downloadManager.getModelV26_Q4_K_M_Status()
        let vitModelStatus = downloadManager.getMMProjV26_Status()
        let aneModelStatus = downloadManager.getMLModelcV26_Status()
        
        return mainModelStatus == "downloaded" && 
               vitModelStatus == "downloaded" && 
               aneModelStatus == "downloaded"
    }
    
    /// 更新按钮状态
    private func updateUseModelButtonState() {
        let allDownloaded = checkAllModelsDownloaded()
        
        if allDownloaded {
            useModelButton.isEnabled = true
            useModelButton.backgroundColor = UIColor.mb_color(with: "#007AFF")
            useModelButton.setTitleColor(.white, for: .normal)
        } else {
            useModelButton.isEnabled = false
            useModelButton.backgroundColor = UIColor.mb_color(with: "#CCCCCC")
            useModelButton.setTitleColor(.gray, for: .normal)
        }
    }
    
    /// 设置为当前使用的模型
    private func setAsCurrentModel() {
        // 更新llamaState的当前模型类型
        mtmdWrapperExample?.currentUsingModelType = .V26MultiModel
        
        // 保存到UserDefaults
        UserDefaults.standard.setValue("V26MultiModel", forKey: "current_selected_model")
        
        // 显示成功提示
        let hud = MBHUD.showAdded(to: self.view, animated: true)
        hud.mode = .text
        hud.label.text = "已设置 \(modelName) 为当前模型"
        hud.hide(animated: true, afterDelay: 2.0)
        
        // 返回上一页
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
            self.navigationController?.popViewController(animated: true)
        }
    }
    
    func testNetwork() {
        // 通过访问本地网络地址触发本地网络权限弹窗
        guard let url = URL(string: "http://192.168.11.125:5081/test.html") else { return }
        let task = URLSession.shared.dataTask(with: url) { data, response, error in
            // 这里只是触发权限弹窗，不需要处理返回
        }
        task.resume()
    }
}
