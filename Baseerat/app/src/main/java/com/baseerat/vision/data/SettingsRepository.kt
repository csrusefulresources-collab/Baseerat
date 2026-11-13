package com.baseerat.vision.data

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.baseerat.vision.util.LocaleHelper
import com.baseerat.vision.util.TtsConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "baseerat_settings_v2")

/** Everything configurable, as one immutable snapshot. */
data class AppSettings(
    val language: String = "ur",
    val autoSpeak: Boolean = true,
    val detailed: Boolean = false,
    val vibrate: Boolean = true,
    val saveHistory: Boolean = true,
    val highContrast: Boolean = false,
    val volUpDouble: String = "screen",
    val volDownDouble: String = "focused",
    val volUpHold: String = "none",
    val volDownHold: String = "none",
    val volumeRestore: Boolean = true,
    val overlayEnabled: Boolean = false,
    val overlaySize: String = "medium",      // small | medium | large
    val overlayPreset: String = "cr",        // "" (free position) | tl | tr | cl | cr | bl | br
    val overlayX: Int = 0,
    val overlayY: Int = 0,
    val fabTap: String = "menu",
    val fabLong: String = "screen",
    val ocrEngine: String = "auto",          // auto | mlkit | tess
    val ocrLangs: String = "",               // "urd+eng" ; blank = all installed
    val labelModel: String = "",             // file name of an imported .tflite, blank = built-in
    val aiLanguage: String = "auto",         // "ur" | "en" | "auto" (= UI language)
    val chatModel: String = "",              // file name of the chat (LLM) model, blank = first installed
    val tts: TtsConfig = TtsConfig()
) {
    /** Effective language for results, answers and chat replies. */
    val aiLang: String get() = if (aiLanguage == "ur" || aiLanguage == "en") aiLanguage else language
}

class SettingsRepository(private val context: Context) {

    private object K {
        val LANGUAGE = stringPreferencesKey("language")
        val AUTO_SPEAK = booleanPreferencesKey("auto_speak")
        val DETAILED = booleanPreferencesKey("detailed_default")
        val VIBRATE = booleanPreferencesKey("vibrate")
        val SAVE_HISTORY = booleanPreferencesKey("save_history")
        val HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
        val VOL_UP_DOUBLE = stringPreferencesKey("vol_up_double")
        val VOL_DOWN_DOUBLE = stringPreferencesKey("vol_down_double")
        val VOL_UP_HOLD = stringPreferencesKey("vol_up_hold")
        val VOL_DOWN_HOLD = stringPreferencesKey("vol_down_hold")
        val VOL_RESTORE = booleanPreferencesKey("vol_restore")
        val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
        val OVERLAY_SIZE = stringPreferencesKey("overlay_size")
        val OVERLAY_PRESET = stringPreferencesKey("overlay_preset")
        val OVERLAY_X = intPreferencesKey("overlay_x")
        val OVERLAY_Y = intPreferencesKey("overlay_y")
        val FAB_TAP = stringPreferencesKey("fab_tap")
        val FAB_LONG = stringPreferencesKey("fab_long")
        val AI_LANG = stringPreferencesKey("ai_language")
        val CHAT_MODEL = stringPreferencesKey("chat_model")
        val OCR_ENGINE = stringPreferencesKey("ocr_engine")
        val OCR_LANGS = stringPreferencesKey("ocr_langs")
        val LABEL_MODEL = stringPreferencesKey("label_model")
        val TTS_ENGINE = stringPreferencesKey("tts_engine")
        val TTS_LANG = stringPreferencesKey("tts_lang")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val TTS_RATE = floatPreferencesKey("tts_rate")
        val TTS_PITCH = floatPreferencesKey("tts_pitch")
    }

    /** Last emitted value; safe to read from any thread (e.g. key-event callbacks). */
    @Volatile
    var current: AppSettings = AppSettings(language = LocaleHelper.saved(context))

    private fun touchExplorationOn(): Boolean =
        context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            language = p[K.LANGUAGE] ?: LocaleHelper.saved(context),
            // When TalkBack is on we default to NOT speaking with our own voice (avoids two voices at once).
            autoSpeak = p[K.AUTO_SPEAK] ?: !touchExplorationOn(),
            detailed = p[K.DETAILED] ?: false,
            vibrate = p[K.VIBRATE] ?: true,
            saveHistory = p[K.SAVE_HISTORY] ?: true,
            highContrast = p[K.HIGH_CONTRAST] ?: false,
            volUpDouble = p[K.VOL_UP_DOUBLE] ?: "screen",
            volDownDouble = p[K.VOL_DOWN_DOUBLE] ?: "focused",
            volUpHold = p[K.VOL_UP_HOLD] ?: "none",
            volDownHold = p[K.VOL_DOWN_HOLD] ?: "none",
            volumeRestore = p[K.VOL_RESTORE] ?: true,
            overlayEnabled = p[K.OVERLAY_ENABLED] ?: false,
            overlaySize = p[K.OVERLAY_SIZE] ?: "medium",
            overlayPreset = p[K.OVERLAY_PRESET] ?: "cr",
            overlayX = p[K.OVERLAY_X] ?: 0,
            overlayY = p[K.OVERLAY_Y] ?: 0,
            fabTap = p[K.FAB_TAP] ?: "menu",
            fabLong = p[K.FAB_LONG] ?: "screen",
            ocrEngine = p[K.OCR_ENGINE] ?: "auto",
            ocrLangs = p[K.OCR_LANGS] ?: "",
            labelModel = p[K.LABEL_MODEL] ?: "",
            aiLanguage = p[K.AI_LANG] ?: LocaleHelper.savedAi(context),
            chatModel = p[K.CHAT_MODEL] ?: "",
            tts = TtsConfig(
                engine = p[K.TTS_ENGINE] ?: "",
                langTag = p[K.TTS_LANG] ?: "",
                voice = p[K.TTS_VOICE] ?: "",
                rate = p[K.TTS_RATE] ?: 1.0f,
                pitch = p[K.TTS_PITCH] ?: 1.0f
            )
        )
    }

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setLanguage(value: String) {
        LocaleHelper.save(context, value)
        put(K.LANGUAGE, value)
    }

    suspend fun setAutoSpeak(v: Boolean) = put(K.AUTO_SPEAK, v)
    suspend fun setDetailed(v: Boolean) = put(K.DETAILED, v)
    suspend fun setVibrate(v: Boolean) = put(K.VIBRATE, v)
    suspend fun setSaveHistory(v: Boolean) = put(K.SAVE_HISTORY, v)
    suspend fun setHighContrast(v: Boolean) = put(K.HIGH_CONTRAST, v)
    suspend fun setVolUpDouble(v: String) = put(K.VOL_UP_DOUBLE, v)
    suspend fun setVolDownDouble(v: String) = put(K.VOL_DOWN_DOUBLE, v)
    suspend fun setVolUpHold(v: String) = put(K.VOL_UP_HOLD, v)
    suspend fun setVolDownHold(v: String) = put(K.VOL_DOWN_HOLD, v)
    suspend fun setVolumeRestore(v: Boolean) = put(K.VOL_RESTORE, v)
    suspend fun setOverlayEnabled(v: Boolean) = put(K.OVERLAY_ENABLED, v)
    suspend fun setOverlaySize(v: String) = put(K.OVERLAY_SIZE, v)
    suspend fun setFabTap(v: String) = put(K.FAB_TAP, v)
    suspend fun setFabLong(v: String) = put(K.FAB_LONG, v)
    suspend fun setChatModel(v: String) = put(K.CHAT_MODEL, v)
    suspend fun setAiLanguage(v: String) {
        LocaleHelper.saveAi(context, v)
        put(K.AI_LANG, v)
    }
    suspend fun setOcrEngine(v: String) = put(K.OCR_ENGINE, v)
    suspend fun setOcrLangs(v: String) = put(K.OCR_LANGS, v)
    suspend fun setLabelModel(v: String) = put(K.LABEL_MODEL, v)

    suspend fun setOverlayPreset(preset: String) = put(K.OVERLAY_PRESET, preset)

    /** Free (dragged) position: clears the preset. */
    suspend fun setOverlayPosition(x: Int, y: Int) {
        context.dataStore.edit {
            it[K.OVERLAY_PRESET] = ""
            it[K.OVERLAY_X] = x
            it[K.OVERLAY_Y] = y
        }
    }

    suspend fun setTts(c: TtsConfig) {
        context.dataStore.edit {
            it[K.TTS_ENGINE] = c.engine
            it[K.TTS_LANG] = c.langTag
            it[K.TTS_VOICE] = c.voice
            it[K.TTS_RATE] = c.rate
            it[K.TTS_PITCH] = c.pitch
        }
    }
}
