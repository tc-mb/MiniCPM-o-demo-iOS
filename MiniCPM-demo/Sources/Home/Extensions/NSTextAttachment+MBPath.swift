//
//  NSTextAttachment+MBPath.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/6/28.
//

import Foundation
import UIKit
import ObjectiveC

// 定义关联键
private var AssociatedMBPathObjectKey: UInt8 = 0

extension NSTextAttachment {

    // 新增属性 through Associated Object，保存 image 对应的 path url
    var customImageLocalURL: URL? {
        get {
            return objc_getAssociatedObject(self, &AssociatedMBPathObjectKey) as? URL
        }
        set(newValue) {
            objc_setAssociatedObject(self, &AssociatedMBPathObjectKey, newValue, objc_AssociationPolicy.OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        }
    }
}
