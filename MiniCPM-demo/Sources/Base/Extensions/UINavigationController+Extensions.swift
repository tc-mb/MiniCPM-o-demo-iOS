//
//  UINavigationController+Extensions.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/6/18.
//

import UIKit

extension UINavigationController {
    
    /// 自定义导航栏颜色的扩展方法
    /// - Parameter color: 要设置的背景颜色
    func setNavigationBackgroundColor(_ color: UIColor) {
        if #available(iOS 13.0, *) {
            let appearance = UINavigationBarAppearance()
            appearance.configureWithOpaqueBackground()
            appearance.backgroundColor = color
            
            // 设置标题文字颜色，根据背景色自动判断使用黑色或白色
            let isDarkBackground = color.isDarkColor
            appearance.titleTextAttributes = [
                .foregroundColor: isDarkBackground ? UIColor.white : UIColor.black
            ]
            appearance.largeTitleTextAttributes = [
                .foregroundColor: isDarkBackground ? UIColor.white : UIColor.black
            ]
            
            navigationBar.standardAppearance = appearance
            navigationBar.scrollEdgeAppearance = appearance
        } else {
            navigationBar.barTintColor = color
            
            // iOS 13 以下版本设置标题颜色
            let isDarkBackground = color.isDarkColor
            navigationBar.titleTextAttributes = [
                .foregroundColor: isDarkBackground ? UIColor.white : UIColor.black
            ]
        }
    }
} 