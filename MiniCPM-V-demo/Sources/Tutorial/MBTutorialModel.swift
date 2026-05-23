//
//  MBTutorialModel.swift
//  MiniCPM-V-demo
//
//  教程页面的卡片数据
//

import Foundation
import UIKit

struct MBTutorialStep {
    /// 步骤编号显示，例如 "1"、"2"
    let index: String
    /// 卡片标题
    let title: String
    /// 卡片正文（支持多段，用 \n\n 分段）
    let body: String
    /// SF Symbol 名称（顶部圆形图标）
    let symbolName: String
    /// 圆形图标背景色
    let symbolBgColor: UIColor
    /// 截图素材名（Assets 中的 imageset 名称，未提供则显示占位）
    let screenshotAsset: String?
    /// 占位提示文字（screenshotAsset == nil 或资源不存在时显示）
    let placeholderHint: String
}

enum MBTutorialContent {

    /// Async prewarm of the tutorial step screenshots so the first push of
    /// MBTutorialViewController doesn't synchronously decode 4 × ~1-3 MiB
    /// PNGs in the middle of the navigation transition (which the user
    /// feels as a 200-400 ms "stutter" when they tap the tutorial button).
    ///
    /// Safe + cheap to call from anywhere — UIImage(named:) caches are
    /// thread-safe for read, and we just discard the returned UIImage.
    /// Recommended call site: MBHomeViewController.viewDidLoad once.
    static func prewarmScreenshotsInBackground() {
        let assetNames = steps().compactMap { $0.screenshotAsset }
        DispatchQueue.global(qos: .utility).async {
            for name in assetNames {
                _ = UIImage(named: name)
            }
        }
    }


    /// 教程步骤数据。内容随当前语言切换：每次调用都重新读 i18n 字典，
    /// 这样 MBTutorialViewController 在 .languageDidChange 后调一次
    /// `steps()` + reload 即可拿到最新文案。
    static func steps() -> [MBTutorialStep] {
        return [
            MBTutorialStep(
                index: "1",
                title: L.Tutorial.page1Title.loc,
                body: L.Tutorial.page1Body.loc,
                symbolName: "icloud.and.arrow.down",
                symbolBgColor: UIColor.mb_color(with: "#007AFF") ?? .systemBlue,
                screenshotAsset: "tutorial_step1_download",
                placeholderHint: L.Tutorial.page1Placeholder.loc
            ),

            MBTutorialStep(
                index: "2",
                title: L.Tutorial.page2Title.loc,
                body: L.Tutorial.page2Body.loc,
                symbolName: "checkmark.seal.fill",
                symbolBgColor: UIColor.mb_color(with: "#34C759") ?? .systemGreen,
                screenshotAsset: "tutorial_step2_load",
                placeholderHint: L.Tutorial.page2Placeholder.loc
            ),

            MBTutorialStep(
                index: "3",
                title: L.Tutorial.page3Title.loc,
                body: L.Tutorial.page3Body.loc,
                symbolName: "bubble.left.and.bubble.right.fill",
                symbolBgColor: UIColor.mb_color(with: "#FF9500") ?? .systemOrange,
                screenshotAsset: "tutorial_step3_chat",
                placeholderHint: L.Tutorial.page3Placeholder.loc
            ),

            MBTutorialStep(
                index: "4",
                title: L.Tutorial.page4Title.loc,
                body: L.Tutorial.page4Body.loc,
                symbolName: "trash.fill",
                symbolBgColor: UIColor.mb_color(with: "#FF3B30") ?? .systemRed,
                screenshotAsset: "tutorial_step4_reset",
                placeholderHint: L.Tutorial.page4Placeholder.loc
            )
        ]
    }
}
