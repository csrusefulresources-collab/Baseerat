package com.baseerat.vision.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Two independent languages:
 *  - UI language        ("ur" | "en")           menus, buttons, settings
 *  - AI response language ("ur" | "en" | "auto") descriptions, answers, chat replies, spoken results
 * Both are mirrored in SharedPreferences so they can be read synchronously
 * (attachBaseContext, key events); DataStore cannot.
 */
object LocaleHelper {
    private const val PREFS = "baseerat_boot"
    private const val KEY_UI = "lang"
    private const val KEY_AI = "ai_lang"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ UI language
    fun saved(context: Context): String = prefs(context).getString(KEY_UI, "ur") ?: "ur"

    fun save(context: Context, lang: String) {
        prefs(context).edit().putString(KEY_UI, lang).commit()
    }

    // ------------------------------------------------------------------ AI language
    /** Raw setting: "ur", "en" or "auto" (= same as the UI language). */
    fun savedAi(context: Context): String = prefs(context).getString(KEY_AI, "auto") ?: "auto"

    fun saveAi(context: Context, value: String) {
        prefs(context).edit().putString(KEY_AI, value).commit()
    }

    /** Effective AI response language: always "ur" or "en". */
    fun aiLang(context: Context): String {
        val v = savedAi(context)
        return if (v == "ur" || v == "en") v else saved(context)
    }

    // ------------------------------------------------------------------ helpers
    /** True if THIS context's resources are Urdu (works for UI-wrapped and AI-wrapped contexts). */
    fun isUrdu(context: Context): Boolean =
        context.resources.configuration.locales.get(0).language == "ur"

    fun wrapFor(base: Context, lang: String): Context {
        val locale = Locale(lang)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** Context whose strings use the UI language (and matching layout direction). */
    fun wrap(base: Context): Context = wrapFor(base, saved(base))

    /** Context whose strings use the AI response language (for results, answers, chat). */
    fun wrapAi(base: Context): Context = wrapFor(base, aiLang(base))
}
