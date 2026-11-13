package com.baseerat.vision.util

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.UUID

/** User-selectable voice configuration (persisted in settings). Blank = "system default". */
data class TtsConfig(
    val engine: String = "",    // package name of the TTS engine
    val langTag: String = "",   // BCP-47, e.g. "ur-PK"; blank = follow the result language
    val voice: String = "",     // Voice.name; blank = engine default voice for the language
    val rate: Float = 1.0f,
    val pitch: Float = 1.0f
)

data class EngineEntry(val pkg: String, val label: String)
data class LangEntry(val tag: String, val display: String)
data class VoiceEntry(
    val name: String,
    val localeTag: String,
    val localeDisplay: String,
    val needsNetwork: Boolean,
    val installed: Boolean
)

data class TtsInfo(
    val ready: Boolean = false,
    val engines: List<EngineEntry> = emptyList(),
    val defaultEngine: String = "",
    val activeEngine: String = "",
    val languages: List<LangEntry> = emptyList(),
    val voices: List<VoiceEntry> = emptyList(),
    val failed: Boolean = false
)

/**
 * Wraps Android TextToSpeech with: engine selection (every installed engine), language,
 * voice, rate, pitch, a queue for utterances requested before the engine is ready, and
 * accessibility audio attributes (so speech follows the "accessibility" volume).
 * Must be used from the main thread.
 */
class SpeechHelper(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(TtsInfo())
    val state: StateFlow<TtsInfo> = _state

    private var tts: TextToSpeech? = null
    private var ready = false
    private var config = TtsConfig()
    private var engineInUse = ""
    private var generation = 0

    private class Pending(val text: String, val lang: String?, val flush: Boolean)

    private val pending = ArrayDeque<Pending>()

    /** Applies [c]; recreates the engine only when the engine package changed. */
    fun applyConfig(c: TtsConfig) {
        val engineChanged = tts == null || c.engine != engineInUse
        if (!engineChanged && c == config) return
        config = c
        if (engineChanged) createEngine(c.engine) else applyToEngine()
    }

    private fun createEngine(engine: String) {
        shutdownEngine()
        engineInUse = engine
        ready = false
        val gen = ++generation
        _state.value = _state.value.copy(ready = false, failed = false)
        val listener = TextToSpeech.OnInitListener { status ->
            main.post { if (gen == generation) onInit(status) }
        }
        tts = try {
            if (engine.isBlank()) TextToSpeech(appContext, listener)
            else TextToSpeech(appContext, listener, engine)
        } catch (_: Exception) {
            null
        }
        if (tts == null) onInit(TextToSpeech.ERROR)
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            if (engineInUse.isNotBlank()) {
                // chosen engine failed / was uninstalled: fall back to the system default
                config = config.copy(engine = "", voice = "", langTag = "")
                createEngine("")
            } else {
                ready = false
                _state.value = _state.value.copy(ready = false, failed = true)
            }
            return
        }
        ready = true
        applyToEngine()
        refreshInfo()
        while (pending.isNotEmpty()) {
            val p = pending.removeFirst()
            speakNow(p.text, p.lang, p.flush)
        }
    }

    private fun applyToEngine() {
        val t = tts ?: return
        if (!ready) return
        runCatching {
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
        t.setSpeechRate(config.rate.coerceIn(0.25f, 4.0f))
        t.setPitch(config.pitch.coerceIn(0.25f, 4.0f))
        if (config.langTag.isNotBlank()) {
            runCatching { t.setLanguage(Locale.forLanguageTag(config.langTag)) }
        }
        if (config.voice.isNotBlank()) {
            runCatching {
                val v = t.voices?.firstOrNull { it.name == config.voice }
                if (v != null) t.setVoice(v)
            }
        }
    }

    private fun refreshInfo() {
        val t = tts ?: return
        val engines = runCatching {
            t.engines.map { EngineEntry(it.name, it.label ?: it.name) }
        }.getOrDefault(emptyList())
        val langs = runCatching {
            t.availableLanguages
                ?.map { LangEntry(it.toLanguageTag(), it.displayName) }
                ?.distinctBy { it.tag }
                ?.sortedBy { it.display }
        }.getOrNull() ?: emptyList()
        val voices = runCatching {
            t.voices?.map { v ->
                VoiceEntry(
                    name = v.name,
                    localeTag = v.locale.toLanguageTag(),
                    localeDisplay = v.locale.displayName,
                    needsNetwork = v.isNetworkConnectionRequired,
                    installed = !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                )
            }?.sortedWith(compareBy({ it.localeDisplay }, { it.name }))
        }.getOrNull() ?: emptyList()
        val def = runCatching { t.defaultEngine }.getOrNull() ?: ""
        _state.value = TtsInfo(
            ready = true,
            engines = engines,
            defaultEngine = def,
            activeEngine = if (engineInUse.isBlank()) def else engineInUse,
            languages = langs,
            voices = voices
        )
    }

    /** True if the active engine can speak [langTag] (e.g. "ur"). */
    fun isLanguageAvailable(langTag: String): Boolean {
        val t = tts ?: return false
        if (!ready) return false
        return try {
            t.isLanguageAvailable(Locale.forLanguageTag(langTag)) >= TextToSpeech.LANG_AVAILABLE
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Speak [text]. [lang] ("ur"/"en") picks the language when the user has not forced a
     * language/voice in settings.
     */
    fun speak(text: String, lang: String? = null, flush: Boolean = true) {
        if (text.isBlank()) return
        if (!ready) {
            if (flush) pending.clear()
            pending.addLast(Pending(text, lang, flush))
            if (tts == null) createEngine(config.engine)
            return
        }
        speakNow(text, lang, flush)
    }

    private fun speakNow(text: String, lang: String?, flush: Boolean) {
        val t = tts ?: return
        if (config.voice.isBlank()) {
            val tag = config.langTag.ifBlank { detectLangByScript(text) ?: lang ?: "" }
            if (tag.isNotBlank()) runCatching { t.setLanguage(Locale.forLanguageTag(tag)) }
        }
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        // Engines reject very long strings: split on sentence ends.
        val chunks = splitForTts(text, TextToSpeech.getMaxSpeechInputLength() - 100)
        chunks.forEachIndexed { i, chunk ->
            t.speak(chunk, if (i == 0) mode else TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
        }
    }

    /** "ur" if the text is mostly Arabic-script, "en" if mostly Latin, null if undecided. */
    private fun detectLangByScript(text: String): String? {
        var arabic = 0
        var latin = 0
        for (ch in text) {
            val c = ch.code
            if (c in 0x0600..0x06FF || c in 0x0750..0x077F || c in 0xFB50..0xFDFF || c in 0xFE70..0xFEFF) arabic++
            else if (c in 0x41..0x5A || c in 0x61..0x7A) latin++
        }
        if (arabic + latin < 3) return null
        return if (arabic >= latin) "ur" else "en"
    }

    private fun splitForTts(text: String, limit: Int): List<String> {
        if (text.length <= limit) return listOf(text)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (part in text.split(Regex("(?<=[.۔!?؟\\n])"))) {
            if (sb.length + part.length > limit && sb.isNotEmpty()) {
                out.add(sb.toString()); sb.clear()
            }
            sb.append(part)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    fun isSpeaking(): Boolean = try { tts?.isSpeaking == true } catch (_: Exception) { false }

    fun stop() {
        pending.clear()
        runCatching { tts?.stop() }
    }

    private fun shutdownEngine() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    fun shutdown() {
        generation++
        pending.clear()
        shutdownEngine()
    }
}
