package com.baseerat.vision.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.baseerat.vision.data.HistoryEntry
import com.baseerat.vision.data.ModelKind
import com.baseerat.vision.service.BaseeratAccessibilityService
import com.baseerat.vision.ui.CameraActivity
import com.baseerat.vision.ui.DescribeActivity
import com.baseerat.vision.ui.components.BigButton
import com.baseerat.vision.ui.components.BodyText
import com.baseerat.vision.ui.components.InfoCard
import com.baseerat.vision.ui.components.NavRow
import com.baseerat.vision.ui.components.PageColumn
import com.baseerat.vision.ui.components.PickerRow
import com.baseerat.vision.ui.components.RadioRow
import com.baseerat.vision.ui.components.SectionHeader
import com.baseerat.vision.ui.components.SwitchRow
import com.baseerat.vision.ui.components.findActivity
import com.baseerat.vision.util.BitmapUtil
import com.baseerat.vision.util.ShortcutActions
import com.baseerat.vision.vision.TesseractOcr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object Routes {
    const val HOME = "home"
    const val CHAT = "chat"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val HELP = "help"
    const val VOICE = "settings/voice"
    const val FLOATING = "settings/floating"
    const val MODELS = "settings/models"
}

internal fun openAccessibilitySettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}

/** Opens the system page of THIS service (with its "Use service" switch), if the phone supports it. */
internal fun openServicePage(context: Context) {
    try {
        val comp = ComponentName(context, BaseeratAccessibilityService::class.java).flattenToString()
        context.startActivity(
            Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra("android.intent.extra.COMPONENT_NAME", comp)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        openAccessibilitySettings(context)
    }
}

internal fun openAppDetails(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
    }
}

private fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("baseerat", text))
}

/**
 * Service on/off control that does not depend on the system "shortcut" switch.
 *  - turn ON: opens the service's own system page (switch "Use Baseerat")
 *  - turn OFF: the service switches itself off
 */
@Composable
internal fun ServiceControl(running: Boolean, snack: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val msgOff = stringResource(R.string.svc_turned_off)
    InfoCard(
        text = stringResource(if (running) R.string.status_service_on else R.string.status_service_off),
        container = if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        content = if (running) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    )
    if (running) {
        BigButton(
            text = stringResource(R.string.btn_service_off),
            icon = Icons.Filled.PowerSettingsNew,
            outlined = true,
            onClick = {
                BaseeratAccessibilityService.instance?.disableSelf()
                scope.launch { snack.showSnackbar(msgOff) }
            }
        )
    } else {
        BigButton(
            text = stringResource(R.string.btn_service_on),
            icon = Icons.Filled.PowerSettingsNew,
            onClick = { openServicePage(context) }
        )
    }
    BodyText(stringResource(R.string.svc_independent_note))
}

// =====================================================================================
// HOME
// =====================================================================================

@Composable
fun HomeScreen(snack: SnackbarHostState, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val settings by app.settings.flow.collectAsStateWithLifecycle(initialValue = app.settings.current)
    val running by BaseeratAccessibilityService.running.collectAsStateWithLifecycle()
    val installed by app.models.installed.collectAsStateWithLifecycle()

    val errOpen = stringResource(R.string.err_open_image)
    val msgEnable = stringResource(R.string.msg_enable_service_first)
    val msgLater = stringResource(R.string.msg_describe_later_started)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val f = withContext(Dispatchers.IO) { BitmapUtil.copyToCache(context, uri) }
                    context.startActivity(DescribeActivity.intent(context, f.absolutePath, "describe", "gallery"))
                } catch (_: Exception) {
                    snack.showSnackbar(errOpen)
                }
            }
        }
    }

    val needsUrduOcr = TesseractOcr.isAvailable && installed.none { it.kind == ModelKind.TESSERACT && it.langCode == "urd" }

    PageColumn {
        ServiceControl(running, snack)

        SectionHeader(stringResource(R.string.home_section_image))
        BigButton(
            text = stringResource(R.string.btn_camera_describe),
            icon = Icons.Filled.CameraAlt,
            onClick = {
                context.startActivity(Intent(context, CameraActivity::class.java).putExtra(CameraActivity.EXTRA_MODE, "describe"))
            }
        )
        BigButton(
            text = stringResource(R.string.btn_camera_read),
            icon = Icons.Filled.TextFields,
            onClick = {
                context.startActivity(Intent(context, CameraActivity::class.java).putExtra(CameraActivity.EXTRA_MODE, "read"))
            }
        )
        BigButton(
            text = stringResource(R.string.btn_gallery),
            icon = Icons.Filled.PhotoLibrary,
            outlined = true,
            onClick = { picker.launch("image/*") }
        )
        if (needsUrduOcr) {
            InfoCard(stringResource(R.string.home_urdu_ocr_hint))
            BigButton(
                text = stringResource(R.string.btn_install_urdu_ocr),
                icon = Icons.Filled.TextFields,
                outlined = true,
                onClick = { onNavigate(Routes.MODELS) }
            )
        }

        SectionHeader(stringResource(R.string.home_section_chat))
        BodyText(stringResource(R.string.home_chat_hint))
        BigButton(
            text = stringResource(R.string.btn_open_chat),
            icon = Icons.Filled.Chat,
            onClick = { onNavigate(Routes.CHAT) }
        )

        SectionHeader(stringResource(R.string.home_section_screen))
        BodyText(stringResource(R.string.home_screen_hint))
        SwitchRow(
            title = stringResource(R.string.setting_floating_button),
            checked = settings.overlayEnabled,
            onCheckedChange = { v ->
                scope.launch {
                    app.settings.setOverlayEnabled(v)
                    if (v && !running) snack.showSnackbar(msgEnable)
                }
            }
        )
        BigButton(
            text = stringResource(R.string.btn_describe_screen_later),
            icon = Icons.Filled.Timer,
            outlined = true,
            onClick = {
                val svc = BaseeratAccessibilityService.instance
                if (svc != null) {
                    svc.scheduleDescribeScreen(5000)
                    scope.launch { snack.showSnackbar(msgLater) }
                } else {
                    openServicePage(context)
                    scope.launch { snack.showSnackbar(msgEnable) }
                }
            }
        )

        if (!running) {
            SectionHeader(stringResource(R.string.setup_title))
            BodyText(stringResource(R.string.setup_steps))
            BigButton(
                text = stringResource(R.string.btn_open_accessibility),
                icon = Icons.Filled.Settings,
                outlined = true,
                onClick = { openAccessibilitySettings(context) }
            )
            BigButton(
                text = stringResource(R.string.btn_open_app_info),
                icon = Icons.Filled.Settings,
                outlined = true,
                onClick = { openAppDetails(context) }
            )
            BodyText(stringResource(R.string.setup_restricted_note))
        }
    }
}

// =====================================================================================
// HISTORY
// =====================================================================================

@Composable
fun HistoryScreen(snack: SnackbarHostState) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val items by app.history.items.collectAsStateWithLifecycle()
    val settings by app.settings.flow.collectAsStateWithLifecycle(initialValue = app.settings.current)
    var confirmClear by remember { mutableStateOf(false) }
    val copiedMsg = stringResource(R.string.copied)
    val lang = settings.language

    PageColumn {
        if (items.isEmpty()) {
            BodyText(stringResource(R.string.history_empty))
        } else {
            OutlinedButton(
                onClick = { confirmClear = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.history_clear)) }

            items.forEach { e ->
                HistoryCard(
                    entry = e,
                    lang = lang,
                    onSpeak = { app.speech.speak(e.text, settings.aiLang) },
                    onCopy = {
                        copyText(context, e.text)
                        scope.launch { snack.showSnackbar(copiedMsg) }
                    },
                    onDelete = { app.history.remove(e.id) }
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear)) },
            text = { Text(stringResource(R.string.history_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { app.history.clear(); confirmClear = false }) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun HistoryCard(
    entry: HistoryEntry,
    lang: String,
    onSpeak: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    val sourceLabel = stringResource(
        when (entry.source) {
            "camera" -> R.string.src_camera
            "gallery" -> R.string.src_gallery
            "share" -> R.string.src_share
            "focus" -> R.string.src_focus
            else -> R.string.src_screen
        }
    )
    val modeLabel = stringResource(
        when (entry.mode) {
            "read" -> R.string.mode_read
            "elements" -> R.string.mode_elements
            else -> R.string.mode_describe
        }
    )
    val date = remember(entry.time, lang) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale(lang)).format(Date(entry.time))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "$sourceLabel · $modeLabel · $date",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(entry.text.take(1200), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onSpeak) {
                    Icon(Icons.Filled.VolumeUp, contentDescription = stringResource(R.string.btn_speak))
                }
                IconButton(onClick = onCopy) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.btn_copy))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.btn_delete))
                }
            }
        }
    }
}

// =====================================================================================
// HELP
// =====================================================================================

@Composable
fun HelpScreen() {
    val sections = listOf(
        R.string.help_h_start to R.string.help_b_start,
        R.string.help_h_service to R.string.help_b_service,
        R.string.help_h_fab to R.string.help_b_fab,
        R.string.help_h_shortcuts to R.string.help_b_shortcuts,
        R.string.help_h_buttons to R.string.help_b_buttons,
        R.string.help_h_chat to R.string.help_b_chat,
        R.string.help_h_ocr to R.string.help_b_ocr,
        R.string.help_h_models to R.string.help_b_models,
        R.string.help_h_talkback to R.string.help_b_talkback,
        R.string.help_h_trouble to R.string.help_b_trouble,
        R.string.help_h_privacy to R.string.help_b_privacy
    )
    PageColumn {
        sections.forEach { (h, b) ->
            SectionHeader(stringResource(h))
            BodyText(stringResource(b))
        }
        SectionHeader(stringResource(R.string.about_title))
        BodyText(stringResource(R.string.about_text, com.baseerat.vision.BuildConfig.VERSION_NAME))
    }
}

// =====================================================================================
// SETTINGS (main page)
// =====================================================================================

@Composable
fun SettingsScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val settings by app.settings.flow.collectAsStateWithLifecycle(initialValue = app.settings.current)

    fun setUiLang(code: String) {
        if (settings.language == code) return
        scope.launch {
            app.settings.setLanguage(code)
            context.findActivity()?.recreate()
        }
    }

    PageColumn {
        SectionHeader(stringResource(R.string.settings_section_ui_language))
        BodyText(stringResource(R.string.settings_ui_language_hint))
        Column(Modifier.selectableGroup()) {
            RadioRow(stringResource(R.string.lang_name_ur), settings.language == "ur", { setUiLang("ur") })
            RadioRow(stringResource(R.string.lang_name_en), settings.language == "en", { setUiLang("en") })
        }

        SectionHeader(stringResource(R.string.settings_section_ai_language))
        BodyText(stringResource(R.string.settings_ai_language_hint))
        Column(Modifier.selectableGroup()) {
            RadioRow(stringResource(R.string.lang_name_ur), settings.aiLanguage == "ur", { scope.launch { app.settings.setAiLanguage("ur") } })
            RadioRow(stringResource(R.string.lang_name_en), settings.aiLanguage == "en", { scope.launch { app.settings.setAiLanguage("en") } })
            RadioRow(stringResource(R.string.lang_follow_ui), settings.aiLanguage == "auto", { scope.launch { app.settings.setAiLanguage("auto") } })
        }

        SectionHeader(stringResource(R.string.settings_section_general))
        SwitchRow(
            title = stringResource(R.string.setting_auto_speak),
            subtitle = stringResource(R.string.setting_auto_speak_hint),
            checked = settings.autoSpeak,
            onCheckedChange = { scope.launch { app.settings.setAutoSpeak(it) } }
        )
        SwitchRow(
            title = stringResource(R.string.setting_detailed),
            subtitle = stringResource(R.string.setting_detailed_hint),
            checked = settings.detailed,
            onCheckedChange = { scope.launch { app.settings.setDetailed(it) } }
        )
        SwitchRow(
            title = stringResource(R.string.setting_vibrate),
            checked = settings.vibrate,
            onCheckedChange = { scope.launch { app.settings.setVibrate(it) } }
        )
        SwitchRow(
            title = stringResource(R.string.setting_save_history),
            subtitle = stringResource(R.string.setting_save_history_hint),
            checked = settings.saveHistory,
            onCheckedChange = { scope.launch { app.settings.setSaveHistory(it) } }
        )
        SwitchRow(
            title = stringResource(R.string.setting_high_contrast),
            checked = settings.highContrast,
            onCheckedChange = { scope.launch { app.settings.setHighContrast(it) } }
        )

        SectionHeader(stringResource(R.string.settings_section_more))
        NavRow(stringResource(R.string.settings_voice), stringResource(R.string.settings_voice_sub), { onNavigate(Routes.VOICE) })
        NavRow(stringResource(R.string.settings_floating), stringResource(R.string.settings_floating_sub), { onNavigate(Routes.FLOATING) })
        NavRow(stringResource(R.string.settings_models), stringResource(R.string.settings_models_sub), { onNavigate(Routes.MODELS) })

        SectionHeader(stringResource(R.string.privacy_title))
        BodyText(stringResource(R.string.privacy_text))
    }
}

// =====================================================================================
// FLOATING BUTTON, SERVICE & SHORTCUTS
// =====================================================================================

@Composable
fun FloatingSettingsScreen(snack: SnackbarHostState) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val settings by app.settings.flow.collectAsStateWithLifecycle(initialValue = app.settings.current)
    val running by BaseeratAccessibilityService.running.collectAsStateWithLifecycle()

    val allActions = ShortcutActions.all.map { it to stringResource(ShortcutActions.labelRes(it)) }
    val tapActions = ShortcutActions.withoutNone.map { it to stringResource(ShortcutActions.labelRes(it)) }
    fun labelOf(id: String): String = allActions.firstOrNull { it.first == id }?.second ?: id

    PageColumn {
        SectionHeader(stringResource(R.string.svc_section))
        ServiceControl(running, snack)
        if (!running) {
            BigButton(
                text = stringResource(R.string.btn_open_accessibility),
                icon = Icons.Filled.Settings,
                outlined = true,
                onClick = { openAccessibilitySettings(context) }
            )
            BigButton(
                text = stringResource(R.string.btn_open_app_info),
                icon = Icons.Filled.Settings,
                outlined = true,
                onClick = { openAppDetails(context) }
            )
            BodyText(stringResource(R.string.setup_restricted_note))
        }

        SectionHeader(stringResource(R.string.fab_section_button))
        SwitchRow(
            title = stringResource(R.string.setting_floating_button),
            subtitle = stringResource(R.string.setting_floating_hint),
            checked = settings.overlayEnabled,
            onCheckedChange = { scope.launch { app.settings.setOverlayEnabled(it) } }
        )
        PickerRow(
            title = stringResource(R.string.fab_tap_action),
            valueLabel = labelOf(settings.fabTap),
            options = tapActions,
            selectedKey = settings.fabTap,
            onSelect = { scope.launch { app.settings.setFabTap(it) } }
        )
        PickerRow(
            title = stringResource(R.string.fab_long_action),
            valueLabel = labelOf(settings.fabLong),
            options = allActions,
            selectedKey = settings.fabLong,
            onSelect = { scope.launch { app.settings.setFabLong(it) } }
        )

        SectionHeader(stringResource(R.string.fab_section_size))
        Column(Modifier.selectableGroup()) {
            RadioRow(stringResource(R.string.size_small), settings.overlaySize == "small", { scope.launch { app.settings.setOverlaySize("small") } })
            RadioRow(stringResource(R.string.size_medium), settings.overlaySize == "medium", { scope.launch { app.settings.setOverlaySize("medium") } })
            RadioRow(stringResource(R.string.size_large), settings.overlaySize == "large", { scope.launch { app.settings.setOverlaySize("large") } })
        }

        SectionHeader(stringResource(R.string.fab_section_position))
        BodyText(stringResource(R.string.fab_position_hint))
        val presets = listOf(
            "tl" to R.string.act_move_tl, "tr" to R.string.act_move_tr,
            "cl" to R.string.act_move_cl, "cr" to R.string.act_move_cr,
            "bl" to R.string.act_move_bl, "br" to R.string.act_move_br
        )
        presets.forEach { (key, label) ->
            OutlinedButton(
                onClick = { scope.launch { app.settings.setOverlayPreset(key) } },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(label)) }
        }

        SectionHeader(stringResource(R.string.vol_section))
        BodyText(stringResource(R.string.vol_hint))
        PickerRow(
            title = stringResource(R.string.vol_up_double),
            valueLabel = labelOf(settings.volUpDouble),
            options = allActions,
            selectedKey = settings.volUpDouble,
            onSelect = { scope.launch { app.settings.setVolUpDouble(it) } }
        )
        PickerRow(
            title = stringResource(R.string.vol_down_double),
            valueLabel = labelOf(settings.volDownDouble),
            options = allActions,
            selectedKey = settings.volDownDouble,
            onSelect = { scope.launch { app.settings.setVolDownDouble(it) } }
        )
        PickerRow(
            title = stringResource(R.string.vol_up_hold),
            valueLabel = labelOf(settings.volUpHold),
            options = allActions,
            selectedKey = settings.volUpHold,
            onSelect = { scope.launch { app.settings.setVolUpHold(it) } }
        )
        PickerRow(
            title = stringResource(R.string.vol_down_hold),
            valueLabel = labelOf(settings.volDownHold),
            options = allActions,
            selectedKey = settings.volDownHold,
            onSelect = { scope.launch { app.settings.setVolDownHold(it) } }
        )
        SwitchRow(
            title = stringResource(R.string.vol_restore),
            subtitle = stringResource(R.string.vol_restore_hint),
            checked = settings.volumeRestore,
            onCheckedChange = { scope.launch { app.settings.setVolumeRestore(it) } }
        )
        BodyText(stringResource(R.string.shortcuts_other))
    }
}
