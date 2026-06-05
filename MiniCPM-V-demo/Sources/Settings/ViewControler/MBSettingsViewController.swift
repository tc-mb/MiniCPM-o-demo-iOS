//
//  MBSettingsViewController.swift
//  MiniCPM-V-demo
//
//  Created by Assistant on 2024/12/19.
//

import Foundation
import UIKit
import SnapKit
import llama

/// 新设置页面 VC
@objc public class MBSettingsViewController: UIViewController, UIGestureRecognizerDelegate {
    
    /// 这是由外部（home）传入的引用
    var mtmdWrapperExample: MTMDWrapperExample?
    
    /// 更新选中的模型
    var updateUsingModeltype: ((CurrentUsingModelTypeV2) -> Void)?
    
    /// 一个列表
    lazy var tableView: UITableView = {
        // grouped has section title
        let tv = UITableView(frame: .zero, style: .grouped)
        tv.register(MBSettingsTableViewCell.self, forCellReuseIdentifier: "MBSettingsTableViewCell")
        tv.estimatedRowHeight = 48
        tv.separatorStyle = .none
        tv.separatorColor = .clear
        tv.dataSource = self
        tv.delegate = self
        return tv
    }()
    
    /// 列表对应的数据源 [[]]
    var dataArray = [[MBSettingsModel]]()
    
    // MARK: - view life cycle
    
    init(with wrapper: MTMDWrapperExample) {
        self.mtmdWrapperExample = wrapper
        super.init(nibName: nil, bundle: nil)
    }
    
    // 这是指定的初始化方法
    required init?(coder: NSCoder) {
        super.init(coder: coder)
    }
    
    public override func viewDidLoad() {
        super.viewDidLoad()
        
        // step 1, create ui
        setupSubViews()
        
        // step 2, 配置 UI 数据
        loadTableViewData()
        
        // 禁止熄屏
        UIApplication.shared.isIdleTimerDisabled = true
        
        // Enable the interactive pop gesture recognizer
        self.navigationController?.interactivePopGestureRecognizer?.isEnabled = true
        self.navigationController?.interactivePopGestureRecognizer?.delegate = self

        // 监听运行时语言切换：用户在本页选了 English 之后，此通知会让
        // 整页重建（标题 + 4 个 section header + 全部 cell title / detailText）
        NotificationCenter.default.addObserver(self,
                                               selector: #selector(applyLanguage),
                                               name: .languageDidChange,
                                               object: nil)
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    /// 当 LocalizationManager.shared.currentLanguage 变化时调用：刷新本页所有可见文案。
    @objc private func applyLanguage() {
        self.title = L.Settings.title.loc
        // loadTableViewData 内部 removeAll + 重新组装 4 个 section + reloadData
        loadTableViewData()
    }
    
    public override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        
        // 刷新模型管理section，以反映最新的选中状态
        refreshModelManagementSection()
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
        self.title = L.Settings.title.loc
        
        let titleDict: [NSAttributedString.Key : Any] = [NSAttributedString.Key.foregroundColor: UIColor.black]
        self.navigationController?.navigationBar.titleTextAttributes = titleDict

        self.view.backgroundColor = UIColor.mb_color(with: "#F9FAFC")

        setupNavView()
        
        tableView.sectionHeaderTopPadding = 0

        tableView.backgroundColor = UIColor.mb_color(with: "#F6F6F6")

        tableView.contentInsetAdjustmentBehavior = .never
        tableView.contentInset = UIEdgeInsets(top: 20, left: 0, bottom: 0, right: 0)
        view.addSubview(tableView)
        tableView.snp.makeConstraints { make in
            make.top.equalTo(self.view.safeAreaLayoutGuide.snp.top)
            make.left.right.bottom.equalTo(self.view)
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
        
        // 白色顶导
        self.navigationController?.setNavigationBackgroundColor(UIColor.mb_color(with: "#F9FAFC") ?? .white)
    }

    // MARK: - 顶导返回按钮 点击 事件
    
    @objc public func handleLeftNavIcon() {
        self.navigationController?.popViewController(animated: true)
    }
    
    public override func viewWillTransition(to size: CGSize, with coordinator: any UIViewControllerTransitionCoordinator) {
        self.tableView.reloadData()
    }
}

// MARK: - UITableViewDataSource
extension MBSettingsViewController: UITableViewDataSource {
    
    public func numberOfSections(in tableView: UITableView) -> Int {
        return dataArray.count
    }
    
    // 返回表格中的行数
    public func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return dataArray[section].count
    }
    
    public func tableView(_ tableView: UITableView, heightForRowAt indexPath: IndexPath) -> CGFloat {
        return 48
    }
    
    // 设置每个单元格的内容
    public func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        
        let cell = tableView.dequeueReusableCell(withIdentifier: "MBSettingsTableViewCell", for: indexPath) as! MBSettingsTableViewCell
        
        let model = dataArray[indexPath.section][indexPath.row]
        cell.configure(with: model)
        
        return cell
    }
    
    public func tableView(_ tableView: UITableView, heightForHeaderInSection section: Int) -> CGFloat {
        if dataArray[section].isEmpty { return 0 }
        return 40
    }
    
    public func tableView(_ tableView: UITableView, viewForHeaderInSection section: Int) -> UIView? {
        let headerView = UIView()
        headerView.backgroundColor = UIColor.mb_color(with: "#F6F6F6")
        
        let titleLabel = UILabel()
        titleLabel.textColor = UIColor.black
        titleLabel.font = .systemFont(ofSize: 16, weight: .medium)
        titleLabel.textAlignment = .left
        
        switch section {
        case 0:
            titleLabel.text = L.Settings.sectionMultimodal.loc
        case 1:
            titleLabel.text = L.Settings.sectionLanguageModel.loc
        case 2:
            titleLabel.text = L.Settings.sectionTts.loc
        case 3:
            titleLabel.text = L.Settings.sectionFeature.loc
        case 4:
            titleLabel.text = L.Settings.sectionOther.loc
        default:
            titleLabel.text = ""
        }
        
        headerView.addSubview(titleLabel)
        titleLabel.snp.makeConstraints { make in
            make.left.equalTo(16)
            make.centerY.equalTo(headerView)
            make.right.equalTo(-16)
        }
        
        return headerView
    }
}

// MARK: - UITableViewDelegate
extension MBSettingsViewController: UITableViewDelegate {
    
    public func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        
        let model = dataArray[indexPath.section][indexPath.row]
        
        switch indexPath.section {
        case 0, 1, 2: // 多模态 / 语言 / TTS 模型
            handleModelSelection(model: model, at: indexPath)
        case 3: // 功能设置
            handleFeatureSelection(model: model, at: indexPath)
        case 4: // 其他设置
            handleOtherSettings(model: model, at: indexPath)
        default:
            break
        }
    }
    
    private func handleModelSelection(model: MBSettingsModel, at indexPath: IndexPath) {
        guard let mtmdWrapperExample else {
            // 如果没有传入 mtmdWrapperExample，创建一个新的实例
            let alert = UIAlertController(title: L.Common.error.loc,
                                          message: L.Settings.alertNoWrapper.loc,
                                          preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: L.Common.ok.loc, style: .default, handler: { _ in
                self.navigationController?.popViewController(animated: true)
            }))
            self.present(alert, animated: true, completion: nil)
            return
        }
        
        if model.status == "disabled" {
            let totalRAM = ProcessInfo.processInfo.physicalMemory
            let ramGB = String(format: "%.0f", Double(totalRAM) / 1024 / 1024 / 1024)
            let modelName = model.title ?? L.Settings.alertModelFallback.loc
            let alertMessage = String(format: L.Settings.alertDeviceUnsupportedMessageFormat.loc, modelName, ramGB)
            let alert = UIAlertController(title: L.Settings.alertDeviceUnsupportedTitle.loc,
                                          message: alertMessage,
                                          preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: L.Common.gotIt.loc, style: .default))
            present(alert, animated: true)
            return
        }
        
        if let title = model.title {
            if title == "MiniCPM-V 2.6 8B" {
                let detailVC = MBV26ModelDetailViewController(with: mtmdWrapperExample)
                self.navigationController?.pushViewController(detailVC, animated: true)
            } else if title == "MiniCPM-V 4.0 4B" {
                let detailVC = MBV4ModelDetailViewController(with: mtmdWrapperExample)
                self.navigationController?.pushViewController(detailVC, animated: true)
            } else if title == "MiniCPM-V 4.6" {
                let detailVC = MBV46ModelDetailViewController(with: mtmdWrapperExample)
                self.navigationController?.pushViewController(detailVC, animated: true)
            } else if title == "MiniCPM5-1B" {
                let detailVC = MBV5ModelDetailViewController(with: mtmdWrapperExample)
                self.navigationController?.pushViewController(detailVC, animated: true)
            } else if title == MiniCPMModelConst.voxcpm2_DisplayedName {
                let detailVC = MBVoxcpm2ModelDetailViewController(with: mtmdWrapperExample)
                self.navigationController?.pushViewController(detailVC, animated: true)
            }
        }
    }
    
    private func handleFeatureSelection(model: MBSettingsModel, at indexPath: IndexPath) {
        // 处理功能设置逻辑。
        // 注意：用 .loc 后的字符串比较，意味着用户在英文环境下点 "Language"
        // cell 也能命中（loc 返回 "Language"），中文环境下点"语言"也能命中。
        guard let title = model.title else { return }
        if title == L.Settings.rowLanguage.loc {
            showLanguagePicker()
        } else if title == L.Realtime.title.loc {
            showRealtimeUnderstandingPage()
        }
    }
    
    private func handleOtherSettings(model: MBSettingsModel, at indexPath: IndexPath) {
        // 处理其他设置逻辑
        if let title = model.title, title == L.Settings.rowAbout.loc {
            // 关于我们的处理逻辑
            showAboutUs()
        }
    }

    /// 弹 ActionSheet 让用户在中文 / English 之间二选一。
    /// 选好后调 LocalizationManager.setLanguage()，会通过通知触发本页 + 已打开
    /// 的其它 VC 全部 reload，无需重启 app。
    private func showLanguagePicker() {
        let alert = UIAlertController(title: L.LanguagePicker.title.loc,
                                      message: L.LanguagePicker.message.loc,
                                      preferredStyle: .actionSheet)

        for lang in AppLanguage.allCases {
            let isCurrent = (LocalizationManager.shared.currentLanguage == lang)
            // 给当前选中那行末尾加 ✓ 提示，避免用户重复点同一项造成困惑。
            let title = isCurrent ? "\(lang.displayName)  ✓" : lang.displayName
            alert.addAction(UIAlertAction(title: title, style: .default) { [weak self] _ in
                LocalizationManager.shared.setLanguage(lang)
                // 立刻刷新本页：监听 .languageDidChange 已经会做这件事，
                // 这里再调一次保证用户在切换瞬间就看到反馈，不依赖通知派发顺序。
                self?.applyLanguage()
            })
        }
        alert.addAction(UIAlertAction(title: L.Common.cancel.loc, style: .cancel))

        // iPad action sheet 必须 anchor 到 sourceView 否则 crash。anchor 到
        // tableView 的当前选中行，没选中行则 fallback 到 view 中央。
        if let pop = alert.popoverPresentationController {
            pop.sourceView = self.view
            if let idx = tableView.indexPathForSelectedRow,
               let cell = tableView.cellForRow(at: idx) {
                pop.sourceView = cell
                pop.sourceRect = cell.bounds
            } else {
                pop.sourceRect = CGRect(x: self.view.bounds.midX, y: self.view.bounds.midY, width: 0, height: 0)
                pop.permittedArrowDirections = []
            }
        }
        present(alert, animated: true)
    }

    private func showRealtimeUnderstandingPage() {
        // 显示实时理解设置页面
        let realtimeVC = MBRealtimeUnderstandingViewController()
        self.navigationController?.pushViewController(realtimeVC, animated: true)
    }
    
    private func showAboutUs() {
        // 显示关于我们的页面
        let appName = Bundle.main.infoDictionary?["CFBundleDisplayName"] as? String ??
        Bundle.main.infoDictionary?["CFBundleName"] as? String ?? "MiniCPM Demo App"
        let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "Unknown"
        let appBuildNumber = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "Unknown"
        let versionInfo = "\(appVersion) (\(appBuildNumber))"
        let message = "\(appName)\n\(L.Settings.aboutVersionLabel.loc) \(versionInfo)"
        let alert = UIAlertController(title: L.Common.about.loc, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: L.Common.ok.loc, style: .default))
        present(alert, animated: true)
    }
    
    private func showInfoTips(_ message: String) {
        let hud = MBHUD.showAdded(to: self.view, animated: true)
        hud.mode = .text
        hud.label.text = message
        hud.hide(animated: true, afterDelay: 1.5)
    }
}

// MARK: - 数据配置
extension MBSettingsViewController {
    
    /// 配置列表数据用于展示 cell
    public func loadTableViewData() {
        dataArray.removeAll()
        setupMultimodalModelSection()
        setupLanguageModelSection()
        setupTtsModelSection()
        setupFeatureSettingsSection()
        setupOtherSettingsSection()
        
        tableView.reloadData()
    }
    
    /// Section 0: 多模态模型 (VLM)
    private func setupMultimodalModelSection() {
        var section = [MBSettingsModel]()
        
        let currentSelectedModel = UserDefaults.standard.string(forKey: "current_selected_model")
        
        let totalRAM = ProcessInfo.processInfo.physicalMemory
        let deviceSupports8B = totalRAM >= 12 * 1024 * 1024 * 1024

        // MiniCPM-V 2.6 8B
        let model1 = MBSettingsModel()
        model1.title = "MiniCPM-V 2.6 8B"
        model1.icon = UIImage(systemName: "cpu")
        model1.accessoryIcon = UIImage(named: "setting_accessory_icon")
        model1.selectedIcon = UIImage(systemName: "checkmark.circle.fill")
        
        if !deviceSupports8B {
            model1.status = "disabled"
            let ramGB = String(format: "%.0f", Double(totalRAM) / 1024 / 1024 / 1024)
            model1.statusString = String(format: L.Settings.statusInsufficientRAMFormat.loc, ramGB)
        } else if currentSelectedModel == "V26MultiModel" {
            model1.status = "selected"
            model1.statusString = L.Settings.statusInUse.loc
        } else {
            model1.status = "none"
        }
        
        section.append(model1)
        
        // MiniCPM-V 4.0 4B
        let model2 = MBSettingsModel()
        model2.title = "MiniCPM-V 4.0 4B"
        model2.icon = UIImage(systemName: "cpu")
        model2.accessoryIcon = UIImage(named: "setting_accessory_icon")
        model2.selectedIcon = UIImage(systemName: "checkmark.circle.fill")
        
        if currentSelectedModel == "V4MultiModel" {
            model2.status = "selected"
            model2.statusString = L.Settings.statusInUse.loc
        } else {
            model2.status = "none"
        }
        
        section.append(model2)
        
        // MiniCPM-V 4.6
        let model3 = MBSettingsModel()
        model3.title = "MiniCPM-V 4.6"
        model3.icon = UIImage(systemName: "cpu")
        model3.accessoryIcon = UIImage(named: "setting_accessory_icon")
        model3.selectedIcon = UIImage(systemName: "checkmark.circle.fill")
        
        if currentSelectedModel == "V46MultiModel" {
            model3.status = "selected"
            model3.statusString = L.Settings.statusInUse.loc
        } else {
            model3.status = "none"
        }
        
        section.append(model3)
        
        dataArray.append(section)
    }
    
    /// Section 1: 语言模型 (LLM)
    private func setupLanguageModelSection() {
        var section = [MBSettingsModel]()
        
        let currentSelectedModel = UserDefaults.standard.string(forKey: "current_selected_model")
        
        let model = MBSettingsModel()
        model.title = "MiniCPM5-1B"
        model.icon = UIImage(systemName: "text.bubble")
        model.accessoryIcon = UIImage(named: "setting_accessory_icon")
        model.selectedIcon = UIImage(systemName: "checkmark.circle.fill")
        
        if currentSelectedModel == "V5TextModel" {
            model.status = "selected"
            model.statusString = L.Settings.statusInUse.loc
        } else {
            model.status = "none"
        }
        
        section.append(model)
        
        dataArray.append(section)
    }

    /// Section 2: TTS 模型 (VoxCPM2)
    private func setupTtsModelSection() {
        var section = [MBSettingsModel]()

        let currentSelectedModel = UserDefaults.standard.string(forKey: "current_selected_model")

        let model = MBSettingsModel()
        model.title = MiniCPMModelConst.voxcpm2_DisplayedName
        model.icon = UIImage(systemName: "waveform.circle")
        model.accessoryIcon = UIImage(named: "setting_accessory_icon")
        model.selectedIcon = UIImage(systemName: "checkmark.circle.fill")

        if currentSelectedModel == "Voxcpm2Model" {
            model.status = "selected"
            model.statusString = L.Settings.statusInUse.loc
        } else {
            model.status = "none"
        }

        section.append(model)
        dataArray.append(section)
    }

    /// Section 3: 功能设置
    private func setupFeatureSettingsSection() {
        var section = [MBSettingsModel]()
        
        // 实时理解设置（暂未调通，暂时隐藏）
        // let realtimeSetting = MBSettingsModel()
        // realtimeSetting.title = L.Realtime.title.loc
        // realtimeSetting.icon = UIImage(systemName: "brain.head.profile")
        // realtimeSetting.accessoryIcon = UIImage(named: "setting_accessory_icon")
        // section.append(realtimeSetting)

        // 语言切换：detailText 显示当前选中的语言，点击后弹 ActionSheet。
        // detailText 永远在 displayName 上读（"中文" / "English"），不参与
        // i18n 字典——这是有意的：用户不论在哪种 UI 语言下都能直接识别母语。
        let langModel = MBSettingsModel()
        langModel.title = L.Settings.rowLanguage.loc
        langModel.icon = UIImage(systemName: "globe")
        langModel.accessoryIcon = UIImage(named: "setting_accessory_icon")
        langModel.detailText = LocalizationManager.shared.currentLanguage.displayName
        section.append(langModel)

        dataArray.append(section)
    }
    
    /// Section 3: 其他设置
    private func setupOtherSettingsSection() {
        var section = [MBSettingsModel]()
        
        let aboutModel = MBSettingsModel()
        aboutModel.title = L.Settings.rowAbout.loc
        aboutModel.icon = UIImage(systemName: "info.circle")
        aboutModel.accessoryIcon = UIImage(named: "setting_accessory_icon")
        
        section.append(aboutModel)
        
        dataArray.append(section)
    }
    
    /// 刷新模型相关 section
    private func refreshModelManagementSection() {
        var currentSelectedModel = UserDefaults.standard.string(forKey: "current_selected_model")
        
        let totalRAM = ProcessInfo.processInfo.physicalMemory
        let hasEnoughRAMFor8B = totalRAM >= 12 * 1024 * 1024 * 1024
        if currentSelectedModel == "V26MultiModel" && !hasEnoughRAMFor8B {
            UserDefaults.standard.removeObject(forKey: "current_selected_model")
            currentSelectedModel = nil
            mtmdWrapperExample?.currentUsingModelType = .Unknown
        }
        
        if currentSelectedModel == "V26MultiModel" {
            mtmdWrapperExample?.currentUsingModelType = .V26MultiModel
        } else if currentSelectedModel == "V4MultiModel" {
            mtmdWrapperExample?.currentUsingModelType = .V4MultiModel
        } else if currentSelectedModel == "V46MultiModel" {
            mtmdWrapperExample?.currentUsingModelType = .V46MultiModel
        } else if currentSelectedModel == "V5TextModel" {
            mtmdWrapperExample?.currentUsingModelType = .V5TextModel
        } else if currentSelectedModel == "Voxcpm2Model" {
            mtmdWrapperExample?.currentUsingModelType = .Voxcpm2Model
        }
        
        // 完整重建数据源（loadTableViewData 内部 removeAll + append）
        loadTableViewData()
        
        if let currentType = mtmdWrapperExample?.currentUsingModelType {
            updateUsingModeltype?(currentType)
        }
    }
} 
