//
//  MBHomeTableViewHeaderView.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/6/8.
//

import Foundation
import UIKit
import SnapKit

/// 首页 tableview header view
class MBHomeTableViewHeaderView: UITableViewHeaderFooterView {

    /// 欢迎 view
    lazy var welcomeView: MBHomeWelcomeView = {
        let welcome = MBHomeWelcomeView(frame: .zero)
        
        return welcome
    }()
    
    override init(reuseIdentifier: String?) {
        super.init(reuseIdentifier: reuseIdentifier)
        setupWelcomeView()
    }
    
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
    
    /// 创建 Welcome view
    func setupWelcomeView() {
        
        self.contentView.addSubview(self.welcomeView)
        
        self.welcomeView.snp.makeConstraints { make in
            make.top.equalTo(240)
            make.centerX.equalTo(self.contentView)
            make.left.right.equalTo(self.contentView)
            make.height.equalTo(240)
        }
    }
    
    public func setupTapEvent(_ tapImp : ((String?) -> Void)? ) {
        welcomeView.setupTapEvent(tapImp)
    }
}
