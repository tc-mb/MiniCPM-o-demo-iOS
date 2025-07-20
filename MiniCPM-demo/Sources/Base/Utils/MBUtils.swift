//
//  MBUtils.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/6/11.
//

import Foundation
import UIKit

/// 一些快捷判断及返回值
class MBUtils: NSObject {
    
    static private var cachedUDID = ""
    
    /// 判断设备是不是 iPhone
    public static func isDeviceIPhone() -> Bool {
        return UIDevice.current.userInterfaceIdiom == .phone
    }
    
    /// 判断设备是不是 iPad
    public static func isDeviceIPad() -> Bool {
        return UIDevice.current.userInterfaceIdiom == .pad
    }
    
    /// 返回设备唯一 UDID
    public static func udid() -> String? {
        return "\(arc4random()%10000000)"
    }
    
    /// 返回（可选）的 keyWindow
    public static func keyWindow() -> UIWindow? {
        if #available(iOS 13.0, *) {
            let optionalWindow = UIApplication
                .shared
                .connectedScenes
                .flatMap { ($0 as? UIWindowScene)?.windows ?? [] }
                .last { $0.isKeyWindow }
            return optionalWindow
        } else {
            return UIApplication.shared.delegate?.window ?? nil
        }
    }

}

