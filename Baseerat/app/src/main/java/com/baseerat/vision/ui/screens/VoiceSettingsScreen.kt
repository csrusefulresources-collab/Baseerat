package com.baseerat.vision.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baseerat.vision.R
import com.baseerat.vision.app
import com.baseerat.vision.ui.components.BigButton
import com.baseerat.vision.ui.components.BodyText
import com.baseerat.vision.ui.components.InfoCard
import com.baseerat.vision.ui.components.PageColumn
import com.baseerat.vision.ui.components.PickerRow
import com.baseerat.vision.ui.components.SectionHeader
import com.baseerat.vision.ui.components.SliderRow
import com.baseerat.vision.util.TtsConfig
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private fun round1(v: Float): Float = (v * 10f).roundToInt() / 10f

private fun safeStart(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}

/**
 * Voice settings: choose ANY installed TTS engine, then its language and voice,
 * tune speed and pitch, test the result and save it.
 * Changes are applied live (so you can hear them) but only persisted when "Save" is pressed.
 */
@Composable
fun VoiceSettingsScreen(snack: SnackbarHostState) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val info by app.speech.state.collectAsStateWithLifecycle()
    val settings by app.settings.flow.collectAsStateWithLifecycle(initialValue = app.settings.current)

    var draft by remember { mutableStateOf(app.settings.current.tts) }
    var sample by remember { mutableStateOf("") }
    val savedMsg = stringResource(R.string.voice_saved)
    val defaultLabel = stringResource(R.string.voice_default)
    val autoLabel = stringResource(R.string.voice_lang_auto)
    val onlineLabel = stringResource(R.string.voice_online)
    val notInstalledLabel = stringResource(R.string.voice_not_installed)
    val sampleUr = stringResource(R.string.voice_sample_ur)
    val sampleEn = stringResource(R.string.voice_sample_en)

    // Apply the draft live so the user hears what they are choosing.
    LaunchedEffect(draft) { app.speech.applyConfig(draft) }
    // Leaving the screen without saving restores the saved voice.
    DisposableEffect(Unit) {
        onDispose {
            app.speech.stop()
            app.speech.applyConfig(app.settings.current.tts)
        }
    }

    val urduWanted = draft.langTag.startsWith("ur") || (draft.langTag.isBlank() && settings.aiLang == "ur")
    val defaultSample = if (urduWanted) sampleUr else sampleEn
    val textToSpeak = sample.ifBlank { defaultSample }
    val langForTest = when {
        draft.langTag.isNotBlank() -> draft.langTag
        settings.aiLang == "ur" -> "ur"
        else -> "en"
    }

    val effectiveEngine = draft.engine.ifBlank { info.defaultEngine }
    val engineOptions = info.engines.map { it.pkg to (it.label + "  (" + it.pkg + ")") }
    val engineLabel = info.engines.firstOrNull { it.pkg == effectiveEngine }?.label ?: effectiveEngine

    val langOptions = listOf("" to autoLabel) + info.languages.map { it.tag to (it.display + " [" + it.tag + "]") }
    val langLabel = if (draft.langTag.isBlank()) autoLabel
    else info.languages.firstOrNull { it.tag == draft.langTag }?.let { it.display + " [" + it.tag + "]" } ?: draft.langTag

    val voicesForLang = info.voices.filter {
        draft.langTag.isBlank() || it.localeTag.equals(draft.langTag, ignoreCase = true) ||
            Locale.forLanguageTag(it.localeTag).language == Locale.forLanguageTag(draft.langTag).language
    }
    val voiceOptions = listOf("" to defaultLabel) + voicesForLang.map { v ->
        val extra = buildString {
            if (v.needsNetwork) append(" · ").append(onlineLabel)
            if (!v.installed) append(" · ").append(notInstalledLabel)
        }
        v.name to (v.name + " — " + v.localeDisplay + extra)
    }
    val voiceLabel = if (draft.voice.isBlank()) defaultLabel else draft.voice

    val checkTag = if (draft.langTag.isNotBlank()) draft.langTag else if (settings.aiLang == "ur") "ur" else "en"
    val langMissing = info.ready && !app.speech.isLanguageAvailable(checkTag)

    PageColumn {
        if (!info.ready && !info.failed) BodyText(stringResource(R.string.voice_loading))
        if (info.failed) InfoCard(stringResource(R.string.voice_failed))
        if (langMissing) InfoCard(stringResource(R.string.voice_lang_missing))

        SectionHeader(stringResource(R.string.voice_section_engine))
        PickerRow(
            title = stringResource(R.string.voice_engine),
            valueLabel = engineLabel.ifBlank { defaultLabel },
            options = engineOptions,
            selectedKey = effectiveEngine,
            onSelect = { pkg ->
                // a new engine has its own languages/voices: reset those choices
                draft = draft.copy(engine = if (pkg == info.defaultEngine) "" else pkg, langTag = "", voice = "")
            },
            enabled = engineOptions.isNotEmpty()
        )
        PickerRow(
            title = stringResource(R.string.voice_language),
            valueLabel = langLabel,
            options = langOptions,
            selectedKey = draft.langTag,
            onSelect = { tag -> draft = draft.copy(langTag = tag, voice = "") },
            enabled = info.ready
        )
        PickerRow(
            title = stringResource(R.string.voice_voice),
            valueLabel = voiceLabel,
            options = voiceOptions,
            selectedKey = draft.voice,
            onSelect = { name -> draft = draft.copy(voice = name) },
            enabled = info.ready
        )

        SectionHeader(stringResource(R.string.voice_section_tune))
        SliderRow(
            label = stringResource(R.string.voice_rate),
            value = draft.rate,
            range = 0.5f..3.0f,
            steps = 24,
            valueText = String.format(Locale.US, "%.1fx", draft.rate),
            onChange = { draft = draft.copy(rate = round1(it)) }
        )
        SliderRow(
            label = stringResource(R.string.voice_pitch),
            value = draft.pitch,
            range = 0.5f..2.0f,
            steps = 14,
            valueText = String.format(Locale.US, "%.1f", draft.pitch),
            onChange = { draft = draft.copy(pitch = round1(it)) }
        )

        SectionHeader(stringResource(R.string.voice_section_test))
        OutlinedTextField(
            value = sample,
            onValueChange = { sample = it },
            label = { Text(stringResource(R.string.voice_test_text)) },
            placeholder = { Text(defaultSample) },
            modifier = Modifier.fillMaxWidth()
        )
        BigButton(
            text = stringResource(R.string.voice_test_play),
            icon = Icons.Filled.PlayArrow,
            onClick = { app.speech.speak(textToSpeak, langForTest) }
        )
        BigButton(
            text = stringResource(R.string.btn_stop),
            icon = Icons.Filled.Stop,
            outlined = true,
            onClick = { app.speech.stop() }
        )

        BigButton(
            text = stringResource(R.string.voice_save),
            icon = Icons.Filled.Check,
            onClick = {
                scope.launch {
                    app.settings.setTts(draft)
                    snack.showSnackbar(savedMsg)
                }
            }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { draft = TtsConfig() }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.voice_reset))
            }
        }

        SectionHeader(stringResource(R.string.voice_section_system))
        BodyText(stringResource(R.string.voice_system_hint))
        OutlinedButton(
            onClick = { safeStart(context, Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.voice_install_data)) }
        OutlinedButton(
            onClick = { safeStart(context, Intent("com.android.settings.TTS_SETTINGS")) },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.voice_system_settings)) }
    }
}
