//
//  UIColor+Extensions.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/6/18.
//

import UIKit

extension UIColor {
    
    /// 判断颜色是否为深色
    var isDarkColor: Bool {
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        
        self.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        
        // 计算亮度 (使用标准亮度公式)
        let brightness = (red * 299 + green * 587 + blue * 114) / 1000
        
        return brightness < 0.5
    }
    
    /// 获取UIColor对象
    /// - Parameter hexString: 以#开头的字符串（不区分大小写），如：#AbFFff，若需要alpha，则传#abcdef255，不传默认为1
    /// - Returns: UIColor对象，如果字符串格式不正确则返回nil
    static func mb_color(with hexString: String) -> UIColor? {
        // 检查字符串格式 - 必须以#开头且长度至少为7（# + 6位RGB）
        guard hexString.hasPrefix("#") && hexString.count >= 7 else {
            return nil
        }
        
        // 提取RGB部分（去掉#号后的6位）
        let startIndex = hexString.index(hexString.startIndex, offsetBy: 1)
        let rgbEndIndex = hexString.index(startIndex, offsetBy: 6)
        let rgbString = String(hexString[startIndex..<rgbEndIndex])
        
        // 手动解析RGB值，模拟Objective-C的逻辑
        guard rgbString.count == 6 else { return nil }
        
        func convertToInt(_ char: Character) -> Int? {
            switch char {
            case "0"..."9":
                return Int(String(char))
            case "a"..."f":
                return Int(char.asciiValue! - Character("a").asciiValue! + 10)
            case "A"..."F":
                return Int(char.asciiValue! - Character("A").asciiValue! + 10)
            default:
                return nil
            }
        }
        
        let chars = Array(rgbString)
        guard chars.count == 6,
              let r1 = convertToInt(chars[0]),
              let r2 = convertToInt(chars[1]),
              let g1 = convertToInt(chars[2]),
              let g2 = convertToInt(chars[3]),
              let b1 = convertToInt(chars[4]),
              let b2 = convertToInt(chars[5]) else {
            return nil
        }
        
        let red = CGFloat(r1 * 16 + r2) / 255.0
        let green = CGFloat(g1 * 16 + g2) / 255.0
        let blue = CGFloat(b1 * 16 + b2) / 255.0
        
        // 解析alpha值
        var alpha: CGFloat = 1.0
        if hexString.count > 7 {
            let alphaStartIndex = hexString.index(startIndex, offsetBy: 6)
            let alphaString = String(hexString[alphaStartIndex...])
            if !alphaString.isEmpty {
                alpha = CGFloat(Float(alphaString) ?? 255.0) / 255.0
            }
        }
        
        return UIColor(red: red, green: green, blue: blue, alpha: alpha)
    }
} 