//
//  debugLog.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/7/5.
//

import Foundation

extension Date {
    func toString(withFormat format: String) -> String {
        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = format
        dateFormatter.locale = Locale(identifier: "en_US_POSIX")
        dateFormatter.timeZone = TimeZone.current
        return dateFormatter.string(from: self)
    }
}

/// DEBUG print method
func debugLog(_ message: String?) {

    guard let message else {
        return
    }

    let formattedTime = Date().toString(withFormat: "mm:ss.SSS")
    print("\(formattedTime) \(message)")
}
