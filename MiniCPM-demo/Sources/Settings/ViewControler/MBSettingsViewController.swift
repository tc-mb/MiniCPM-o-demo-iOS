//
//  MBSettingsViewController.swift
//  MiniCPM-demo
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
        self.title = "设置"
        
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
            titleLabel.text = "模型管理"
        case 1:
            titleLabel.text = "功能设置"
        case 2:
            titleLabel.text = "其他设置"
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
        case 0: // 模型管理
            handleModelSelection(model: model, at: indexPath)
        case 1: // 功能设置
            handleFeatureSelection(model: model, at: indexPath)
        case 2: // 其他设置
            handleOtherSettings(model: model, at: indexPath)
        default:
            break
        }
    }
    
    private func handleModelSelection(model: MBSettingsModel, at indexPath: IndexPath) {
        guard let mtmdWrapperExample else {
            // 如果没有传入 mtmdWrapperExample，创建一个新的实例
            let alert = UIAlertController(title: "错误", message: "未传入 mtmdWrapperExample，无法初始化下载管理器。", preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "确定", style: .default, handler: { _ in
                self.navigationController?.popViewController(animated: true)
            }))
            self.present(alert, animated: true, completion: nil)
            return
        }
        
        // 处理模型选择逻辑 - 现在改为跳转到详情页面
        if let title = model.title {
            if title == "MiniCPM-V 2.6 8B" {
                // 跳转到 MiniCPM-V 2.6 8B 详情页面
                let detailVC = MBV26ModelDetailViewController(with: mtmdWrapperExample)
                self.navigationController?.pushViewController(detailVC, animated: true)
            } else if title == "MiniCPM-V 4 3.4B" {
                // 跳转到 MiniCPM-V 4 3.4B 详情页面
                let detailVC = MB4V3BModelDetailViewController(with: mtmdWrapperExample)
                self.navigationController?.pushViewController(detailVC, animated: true)
            }
        }
    }
    
    private func handleFeatureSelection(model: MBSettingsModel, at indexPath: IndexPath) {
        // 处理功能设置逻辑
        if let title = model.title, title == "实时理解设置" {
            // 实时理解设置的处理逻辑
            showRealtimeUnderstandingPage()
        }
    }
    
    private func handleOtherSettings(model: MBSettingsModel, at indexPath: IndexPath) {
        // 处理其他设置逻辑
        if let title = model.title, title == "关于我们" {
            // 关于我们的处理逻辑
            showAboutUs()
        }
    }

    private func showRealtimeUnderstandingPage() {
        // 显示实时理解设置页面
        let realtimeVC = MBRealtimeUnderstandingViewController()
        self.navigationController?.pushViewController(realtimeVC, animated: true)
    }
    
    private func showAboutUs() {
        // 显示关于我们的页面
        let alert = UIAlertController(title: "关于", message: "MiniCPM Demo App\n版本 1.0.4", preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "确定", style: .default))
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
        setupModelManagementSection()
        setupFeatureSettingsSection()
        setupThirdSection()
        
        tableView.reloadData()
    }
    
    /// 配置模型管理 section
    private func setupModelManagementSection() {
        var sectionA = [MBSettingsModel]()
        
        // 获取当前选中的模型
        let currentSelectedModel = UserDefaults.standard.string(forKey: "current_selected_model")
        
        // MiniCPM-V 2.6 8B
        let model1 = MBSettingsModel()
        model1.title = "MiniCPM-V 2.6 8B"
        model1.icon = UIImage(systemName: "cpu")
        model1.accessoryIcon = UIImage(named: "setting_accessory_icon")
        model1.selectedIcon = UIImage(systemName: "checkmark.circle.fill")
        
        // 检查是否已选中
        if currentSelectedModel == "V26MultiModel" {
            model1.status = "selected"
            model1.statusString = "正在使用"
            debugLog("-->> SettingsVC: V26模型设置为选中状态")
        } else {
            model1.status = "none"
            debugLog("-->> SettingsVC: V26模型设置为未选中状态")
        }
        
        sectionA.append(model1)
        
        // MiniCPM-V 4 3.4B
        let model2 = MBSettingsModel()
        model2.title = "MiniCPM-V 4 3.4B"
        model2.icon = UIImage(systemName: "cpu")
        model2.accessoryIcon = UIImage(named: "setting_accessory_icon")
        model2.selectedIcon = UIImage(systemName: "checkmark.circle.fill")
        
        // 检查是否已选中
        if currentSelectedModel == "V4V3BMultiModel" {
            model2.status = "selected"
            model2.statusString = "正在使用"
            debugLog("-->> SettingsVC: 4V3B模型设置为选中状态")
        } else {
            model2.status = "none"
            debugLog("-->> SettingsVC: 4V3B模型设置为未选中状态")
        }
        
        sectionA.append(model2)
        
        // 添加到数据源
        if dataArray.count > 0 {
            dataArray[0] = sectionA
        } else {
            dataArray.append(sectionA)
        }
    }
    
    /// 配置功能设置 section
    private func setupFeatureSettingsSection() {
        var sectionB = [MBSettingsModel]()
        
        // 实时理解设置
        let realtimeSetting = MBSettingsModel()
        realtimeSetting.title = "实时理解设置"
        realtimeSetting.icon = UIImage(systemName: "brain.head.profile")
        realtimeSetting.accessoryIcon = UIImage(named: "setting_accessory_icon")
        
        sectionB.append(realtimeSetting)
        
        // 添加到数据源
        if dataArray.count > 1 {
            dataArray[1] = sectionB
        } else {
            dataArray.append(sectionB)
        }
    }
    
    /// 配置第三个 section
    private func setupThirdSection() {
        var sectionC = [MBSettingsModel]()
        
        // 关于我们
        let aboutModel = MBSettingsModel()
        aboutModel.title = "关于我们"
        aboutModel.icon = UIImage(systemName: "info.circle")
        aboutModel.accessoryIcon = UIImage(named: "setting_accessory_icon")
        
        sectionC.append(aboutModel)
        
        // 添加到数据源
        if dataArray.count > 2 {
            dataArray[2] = sectionC
        } else {
            dataArray.append(sectionC)
        }
    }
    
    /// 刷新模型管理section
    private func refreshModelManagementSection() {
        // 获取当前选中的模型
        let currentSelectedModel = UserDefaults.standard.string(forKey: "current_selected_model")
        
        // 更新llamaState的当前模型类型
        if currentSelectedModel == "V26MultiModel" {
            mtmdWrapperExample?.currentUsingModelType = .V26MultiModel
        } else if currentSelectedModel == "V4V3BMultiModel" {
            mtmdWrapperExample?.currentUsingModelType = .V4V3BMultiModel
        }
        
        // 重新配置模型管理section
        setupModelManagementSection()
        
        // 刷新表格
        tableView.reloadData()
        
        // 调用回调通知外部模型类型已更新
        if let currentType = mtmdWrapperExample?.currentUsingModelType {
            updateUsingModeltype?(currentType)
        }
    }
} 
