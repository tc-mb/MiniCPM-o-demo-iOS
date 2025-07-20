//
//  MBConstants.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/7/16.
//

import Foundation
import UIKit

/// Window and View 常用常量
class MBConstants {

    static let shared = MBConstants()
    
    // Cache the result at startup
    let isPhoneXSeries: Bool = {
        if #available(iOS 13.0, *) {
            let windows = UIApplication
                .shared
                .connectedScenes
                .flatMap { ($0 as? UIWindowScene)?.windows ?? [] }
                .last { $0.isKeyWindow }
            if let window = windows {
                return window.safeAreaInsets.bottom > 0
            }
        } else {
            if let window = UIApplication.shared.delegate?.window, 
                let safeWindow = window {
                return safeWindow.safeAreaInsets.bottom > 0
            }
        }
        return false
    }()
    
    // All constants
    let kNavBarContentHeight: CGFloat = 44.0
    var kStatusBarHeight: CGFloat { isPhoneXSeries ? 44.0 : 20.0 }
    var kNavBarHeight: CGFloat { isPhoneXSeries ? 88.0 : 64.0 }
    var kTabBarHeight: CGFloat { isPhoneXSeries ? 83.0 : 49.0 }
    var kBottomSafeHeight: CGFloat { isPhoneXSeries ? 34 : 0 }
}
