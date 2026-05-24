package com.example.minicpm_v_demo

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object LocaleManager {

    private const val PREFS_NAME = "minicpm_locale"
    private const val KEY_APP_LANGUAGE = "app_language"

    enum class AppLanguage(val tag: String, val displayName: String) {
        ZH("zh", "中文"),
        EN("en", "English");

        companion object {
            fun fromTag(tag: String?): AppLanguage? =
                entries.firstOrNull { it.tag == tag }
        }
    }

    fun currentLanguage(context: Context): AppLanguage {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_APP_LANGUAGE, null)
        return AppLanguage.fromTag(raw) ?: AppLanguage.ZH
    }

    fun applyOnAppStart(context: Context) {
        val lang = currentLanguage(context)
        persist(context, lang)
        applyLocale(lang)
    }

    /**
     * Switch language and seamlessly restart the calling Activity.
     * Because both Activities declare configChanges="locale|layoutDirection",
     * the system won't auto-recreate them. We manually finish+start so we
     * have full control over the transition animation (no black flash).
     */
    fun setLanguageAndRestart(activity: Activity, lang: AppLanguage) {
        persist(activity, lang)
        applyLocale(lang)
        activity.finish()
        activity.startActivity(Intent(activity, activity::class.java))
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }

    /**
     * Seamlessly restart an Activity to pick up a locale change that
     * happened elsewhere (e.g. user switched language in the model
     * manager, now returning to the main chat screen).
     */
    fun recreateSeamlessly(activity: Activity) {
        activity.finish()
        activity.startActivity(Intent(activity, activity::class.java))
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }

    private fun persist(context: Context, lang: AppLanguage) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_LANGUAGE, lang.tag)
            .apply()
    }

    private fun applyLocale(lang: AppLanguage) {
        val locales = LocaleListCompat.forLanguageTags(lang.tag)
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
