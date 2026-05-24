// App-level zh/en language switch manager.
//
// Mirrors Android LocaleManager.kt. Persists the user's choice in
// @ohos.data.preferences and applies it via
// i18n.System.setAppPreferredLanguage(). ArkUI components using $r()
// re-render automatically after the locale changes; code paths that
// resolve strings via resourceManager.getStringByNameSync() must
// refresh their @State variables manually.
//
// Persistence key & values are aligned across all three platforms:
//   key = "app_language", values = "zh" | "en"
// so cross-platform debugging / log grepping uses the same tokens.

import { i18n } from '@kit.LocalizationKit';
import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import hilog from '@ohos.hilog';

const DOMAIN = 0xC0DE;
const TAG = 'LocaleManager';

const PREFS_NAME: string = 'minicpm_locale';
const KEY_APP_LANGUAGE: string = 'app_language';

export enum AppLanguage {
  ZH = 'zh-Hans',
  EN = 'en-US',
}

export class LocaleManager {
  private static prefs: preferences.Preferences | null = null;

  // Synchronous cache so EntryAbility.onCreate can apply the locale
  // before the first page renders, without awaiting Preferences I/O.
  private static cachedTag: string = 'zh';

  private static async ensurePrefs(context: common.Context): Promise<preferences.Preferences> {
    if (LocaleManager.prefs) {
      return LocaleManager.prefs;
    }
    LocaleManager.prefs = await preferences.getPreferences(context, PREFS_NAME);
    return LocaleManager.prefs;
  }

  private static tagToLanguage(tag: string): AppLanguage {
    return tag === 'en' ? AppLanguage.EN : AppLanguage.ZH;
  }

  private static languageToTag(lang: AppLanguage): string {
    return lang === AppLanguage.EN ? 'en' : 'zh';
  }

  static async currentLanguage(context: common.Context): Promise<AppLanguage> {
    const p = await LocaleManager.ensurePrefs(context);
    const raw = await p.get(KEY_APP_LANGUAGE, '') as string;
    if (raw === 'en') {
      LocaleManager.cachedTag = 'en';
      return AppLanguage.EN;
    }
    LocaleManager.cachedTag = 'zh';
    return AppLanguage.ZH;
  }

  /** Synchronous read of the cached language tag. Only valid after
   *  applyOnAppStart() or currentLanguage() has been called at least once. */
  static currentLanguageSync(): AppLanguage {
    return LocaleManager.tagToLanguage(LocaleManager.cachedTag);
  }

  /**
   * Call once in EntryAbility.onCreate, before onWindowStageCreate.
   * Reads the persisted choice, updates the sync cache, and applies
   * the locale so the first page renders in the correct language.
   */
  static async applyOnAppStart(context: common.Context): Promise<void> {
    const lang = await LocaleManager.currentLanguage(context);
    await LocaleManager.persist(context, lang);
    LocaleManager.applyLocale(lang);
  }

  /** Settings page calls this when the user picks a language. */
  static async setLanguage(context: common.Context, lang: AppLanguage): Promise<void> {
    LocaleManager.cachedTag = LocaleManager.languageToTag(lang);
    await LocaleManager.persist(context, lang);
    LocaleManager.applyLocale(lang);
  }

  private static async persist(context: common.Context, lang: AppLanguage): Promise<void> {
    const p = await LocaleManager.ensurePrefs(context);
    const tag = LocaleManager.languageToTag(lang);
    await p.put(KEY_APP_LANGUAGE, tag);
    await p.flush();
  }

  private static applyLocale(lang: AppLanguage): void {
    try {
      i18n.System.setAppPreferredLanguage(lang);
      hilog.info(DOMAIN, TAG, 'setAppPreferredLanguage → %{public}s', lang);
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'setAppPreferredLanguage failed: %{public}s', `${err}`);
    }
  }

  /** Display name for the picker — always in the language's own script,
   *  never translated (so the user can always identify their language). */
  static displayName(lang: AppLanguage): string {
    return lang === AppLanguage.EN ? 'English' : '中文';
  }
}
