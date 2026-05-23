//
//  L10n.swift
//  MiniCPM-V-demo
//
//  App-level runtime i18n manager. Self-contained — no Apple
//  Localizations / .strings / .xcstrings infrastructure needed.
//
//  Why not Apple-standard NSLocalizedString:
//  the demo never opted in to Localizations, so Apple-standard would
//  require editing the .pbxproj (add zh-Hans.lproj / en.lproj folders,
//  enable Localizations) AND give a worse switching UX (system language
//  follows or app restart). A code-side dictionary keeps the diff
//  purely in Swift and lets the user toggle inside Settings, taking
//  effect on the next runloop spin via NotificationCenter.
//
//  Trade-offs (Apple-toolchain features we give up):
//   - No Xcode "Export For Localization" .xliff workflow.
//   - No String Catalog GUI editor.
//   - No compile-time check for missing keys.
//   - VoiceOver still uses the system language for pronunciation.
//   - App Store metadata multi-language must be filled manually.
//  These are acceptable for a demo that only ships zh + en.
//

import Foundation

/// Languages we support at runtime. `rawValue` is what we persist in
/// UserDefaults, so be careful renaming these (would need a migration).
public enum AppLanguage: String, CaseIterable {
    case zh
    case en

    /// Human-readable label shown in the language picker. Always in the
    /// language's *own* script ("中文" / "English"), never translated —
    /// users navigating an unfamiliar UI language must still recognise
    /// their target language to switch back.
    public var displayName: String {
        switch self {
        case .zh: return "中文"
        case .en: return "English"
        }
    }
}

extension Notification.Name {
    /// Posted on the main thread *after* `LocalizationManager.shared.currentLanguage`
    /// has been updated and persisted. Observers should treat the new
    /// value as already in effect and refresh their UI text immediately.
    public static let languageDidChange = Notification.Name("MBLanguageDidChange")
}

/// Singleton holding the active language and the zh/en dictionaries.
///
/// All access is expected from the main thread (UIKit code path). We
/// don't add a lock because cross-thread switching of the UI language
/// would already be a logic error.
public final class LocalizationManager {

    public static let shared = LocalizationManager()

    /// UserDefaults key for the persisted language choice.
    private static let userDefaultsKey = "app_language"

    /// Currently active language. Only mutated via `setLanguage(_:)`.
    public private(set) var currentLanguage: AppLanguage

    /// Pre-baked dictionaries. The two static members
    /// `LocalizationManager.zhDict` / `.enDict` live in dedicated files
    /// (L10n+zh.swift / L10n+en.swift) so the ~hundreds of key/value
    /// pairs don't bloat this file.
    private let dictionaries: [AppLanguage: [String: String]]

    private init() {
        // Determine starting language: persisted choice wins (so users
        // who manually switched to en in Settings keep en across cold
        // launches). When nothing is persisted (fresh install), default
        // to zh unconditionally — regardless of the device system
        // language. The product intent is "Chinese-first demo, English
        // is opt-in via Settings", so an English-locale phone should
        // still see Chinese on first launch.
        let stored = UserDefaults.standard.string(forKey: Self.userDefaultsKey)
        if let raw = stored, let lang = AppLanguage(rawValue: raw) {
            currentLanguage = lang
        } else {
            currentLanguage = .zh
        }

        dictionaries = [
            .zh: Self.zhDict,
            .en: Self.enDict,
        ]
    }

    /// Switch the active language.
    ///
    /// Always writes to UserDefaults, even when `lang == currentLanguage`.
    /// The "no-op early return" optimisation looks tempting, but if the
    /// in-memory state ever drifts from the on-disk state (e.g. stale
    /// value from a previous build, or a migration mid-debugging), an
    /// unconditional write lets the user "re-pick" the current language
    /// in Settings to force-persist and recover. The cost — one
    /// additional `set:forKey:` per identical tap — is irrelevant.
    /// Notification is still posted unconditionally so any observing VC
    /// can refresh; identical-language reload is cheap.
    public func setLanguage(_ lang: AppLanguage) {
        currentLanguage = lang
        UserDefaults.standard.set(lang.rawValue, forKey: Self.userDefaultsKey)
        NotificationCenter.default.post(name: .languageDidChange, object: nil)
    }

    /// Look up a localized string by key.
    ///
    /// Lookup order:
    ///   1. current language's dictionary (zh or en)
    ///   2. zh dictionary (so an en table that forgets a key surfaces
    ///      Chinese rather than crashing or showing an empty label)
    ///   3. the key itself (so a globally-missing key surfaces as
    ///      `home.welcome.title` — easy to spot during QA, never blank)
    public func string(forKey key: String) -> String {
        if let v = dictionaries[currentLanguage]?[key] { return v }
        if let v = dictionaries[.zh]?[key] { return v }
        return key
    }
}

// MARK: - Call-site sugar

extension String {
    /// `L.Settings.title.loc` → "Settings" / "设置". The receiver is
    /// the lookup key. Read-only, evaluated on every access (cheap —
    /// it's a dictionary get).
    public var loc: String {
        LocalizationManager.shared.string(forKey: self)
    }
}

/// Free-function form, mostly for code paths that already use string
/// interpolation or value transforms and want to avoid the `.loc`
/// trailing-property style. Equivalent to `key.loc`.
public func L10n(_ key: String) -> String {
    LocalizationManager.shared.string(forKey: key)
}
