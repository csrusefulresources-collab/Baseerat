package com.baseerat.vision.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baseerat.vision.R
import com.baseerat.vision.app
import com.baseerat.vision.chat.LlmBridge
import com.baseerat.vision.data.CatalogItem
import com.baseerat.vision.data.InstalledModel
import com.baseerat.vision.data.ModelCatalog
import com.baseerat.vision.data.ModelEventType
import com.baseerat.vision.data.ModelKind
import com.baseerat.vision.ui.components.BigButton
import com.baseerat.vision.ui.components.BodyText
import com.baseerat.vision.ui.components.InfoCard
import com.baseerat.vision.ui.components.PageColumn
import com.baseerat.vision.ui.components.RadioRow
import com.baseerat.vision.ui.components.SectionHeader
import com.baseerat.vision.ui.components.SwitchRow
import com.baseerat.vision.vision.TesseractOcr
import kotlinx.coroutines.launch
import java.util.Locale

private fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1048576.0)

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}

@Composable
fun ModelsScreen(snack: SnackbarHostState) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val settings by app.settings.flow.collectAsStateWithLifecycle(initialValue = app.settings.current)
    val installed by app.models.installed.collectAsStateWithLifecycle()
    val progress by app.models.progress.collectAsStateWithLifecycle()

    var toDelete by remember { mutableStateOf<InstalledModel?>(null) }
    var customUrl by remember { mutableStateOf("") }

    // Snackbar messages for import / download results (read once per event)
    val mImported = stringResource(R.string.model_imported)
    val mDownloaded = stringResource(R.string.model_downloaded)
    val mDeleted = stringResource(R.string.model_deleted)
    val mFailed = stringResource(R.string.model_failed)
    val mUnsupported = stringResource(R.string.model_unsupported)
    val mCancelled = stringResource(R.string.model_cancelled)
    LaunchedEffect(Unit) {
        app.models.events.collect { e ->
            val msg = when (e.type) {
                ModelEventType.IMPORTED -> String.format(mImported, e.name)
                ModelEventType.DOWNLOADED -> String.format(mDownloaded, e.name)
                ModelEventType.DELETED -> String.format(mDeleted, e.name)
                ModelEventType.FAILED -> String.format(mFailed, e.name, e.detail ?: "")
                ModelEventType.UNSUPPORTED -> String.format(mUnsupported, e.name)
                ModelEventType.CANCELLED -> String.format(mCancelled, e.name)
            }
            snack.showSnackbar(msg)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) app.models.importFromUri(uri)
    }

    val tessModels = installed.filter { it.kind == ModelKind.TESSERACT }
    val tfliteModels = installed.filter { it.kind == ModelKind.TFLITE }
    val llmModels = installed.filter { it.kind == ModelKind.LLM }
    val enabledLangs: Set<String> = if (settings.ocrLangs.isBlank()) tessModels.map { it.langCode }.toSet()
    else settings.ocrLangs.split("+").filter { it.isNotBlank() }.toSet()

    PageColumn {
        BodyText(stringResource(R.string.models_intro))

        // ---------------------------------------------------------- transfers in progress
        if (progress.isNotEmpty()) {
            SectionHeader(stringResource(R.string.models_section_progress))
            progress.values.forEach { p ->
                val pct = if (p.total > 0) ((p.done * 100) / p.total).toInt().coerceIn(0, 100) else -1
                val label = if (pct >= 0) "$pct%" else mb(p.done)
                val name = p.id.substringAfter(':')
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("$name — $label", style = MaterialTheme.typography.bodyLarge)
                        val mod = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = name
                                stateDescription = label
                            }
                        if (pct >= 0) LinearProgressIndicator(progress = { pct / 100f }, modifier = mod)
                        else LinearProgressIndicator(modifier = mod)
                        if (!p.id.startsWith("import:")) {
                            OutlinedButton(onClick = { app.models.cancel(p.id) }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------- OCR
        SectionHeader(stringResource(R.string.models_section_ocr))
        Column(Modifier.selectableGroup()) {
            RadioRow(
                title = stringResource(R.string.ocr_auto),
                subtitle = stringResource(R.string.ocr_auto_hint),
                selected = settings.ocrEngine == "auto",
                onClick = { scope.launch { app.settings.setOcrEngine("auto") } }
            )
            RadioRow(
                title = stringResource(R.string.ocr_mlkit),
                subtitle = stringResource(R.string.ocr_mlkit_hint),
                selected = settings.ocrEngine == "mlkit",
                onClick = { scope.launch { app.settings.setOcrEngine("mlkit") } }
            )
            RadioRow(
                title = stringResource(R.string.ocr_tess),
                subtitle = stringResource(R.string.ocr_tess_hint),
                selected = settings.ocrEngine == "tess",
                onClick = { scope.launch { app.settings.setOcrEngine("tess") } }
            )
        }
        if (!TesseractOcr.isAvailable) {
            InfoCard(stringResource(R.string.ocr_engine_missing))
        }
        if (tessModels.isEmpty()) {
            InfoCard(stringResource(R.string.ocr_no_tess))
        } else {
            BodyText(stringResource(R.string.ocr_langs_title))
            tessModels.forEach { m ->
                SwitchRow(
                    title = m.langCode,
                    subtitle = mb(m.sizeBytes),
                    checked = m.langCode in enabledLangs,
                    onCheckedChange = { on ->
                        val next = if (on) enabledLangs + m.langCode else enabledLangs - m.langCode
                        // keep urd first, eng second for best results
                        val ordered = next.sortedWith(compareBy({ if (it == "urd") 0 else if (it == "eng") 1 else 2 }, { it }))
                        scope.launch { app.settings.setOcrLangs(ordered.joinToString("+")) }
                    }
                )
            }
        }

        // ---------------------------------------------------------- object model
        SectionHeader(stringResource(R.string.models_section_objects))
        Column(Modifier.selectableGroup()) {
            RadioRow(
                title = stringResource(R.string.objects_builtin),
                subtitle = stringResource(R.string.objects_builtin_hint),
                selected = settings.labelModel.isBlank(),
                onClick = { scope.launch { app.settings.setLabelModel("") } }
            )
            tfliteModels.forEach { m ->
                RadioRow(
                    title = m.name,
                    subtitle = mb(m.sizeBytes),
                    selected = settings.labelModel == m.name,
                    onClick = { scope.launch { app.settings.setLabelModel(m.name) } }
                )
            }
        }

        // ---------------------------------------------------------- chat model
        SectionHeader(stringResource(R.string.models_section_chat))
        BodyText(stringResource(R.string.models_chat_intro))
        if (!LlmBridge.isAvailable) {
            InfoCard(stringResource(R.string.chat_engine_missing_models))
        }
        Column(Modifier.selectableGroup()) {
            RadioRow(
                title = stringResource(R.string.chat_model_auto),
                selected = settings.chatModel.isBlank(),
                onClick = { scope.launch { app.settings.setChatModel("") } }
            )
            llmModels.forEach { m ->
                RadioRow(
                    title = m.name,
                    subtitle = mb(m.sizeBytes),
                    selected = settings.chatModel == m.name,
                    onClick = { scope.launch { app.settings.setChatModel(m.name) } }
                )
            }
        }
        if (llmModels.isEmpty()) BodyText(stringResource(R.string.chat_models_none))

        // ---------------------------------------------------------- installed list
        SectionHeader(stringResource(R.string.models_section_installed))
        if (installed.isEmpty()) {
            BodyText(stringResource(R.string.models_none))
        } else {
            installed.forEach { m ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(
                                when (m.kind) {
                                    ModelKind.TESSERACT -> R.string.kind_tess
                                    ModelKind.TFLITE -> R.string.kind_tflite
                                    ModelKind.LLM -> R.string.kind_llm
                                }
                            ) +
                                " · " + mb(m.sizeBytes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { toDelete = m }) { Text(stringResource(R.string.btn_delete)) }
                }
            }
        }

        // ---------------------------------------------------------- import
        SectionHeader(stringResource(R.string.models_section_import))
        BodyText(stringResource(R.string.models_import_hint))
        BigButton(
            text = stringResource(R.string.models_import_btn),
            icon = Icons.Filled.UploadFile,
            onClick = { importLauncher.launch(arrayOf("*/*")) }
        )

        // ---------------------------------------------------------- download
        SectionHeader(stringResource(R.string.models_section_download))
        BodyText(stringResource(R.string.models_download_hint))
        ModelCatalog.items.forEach { item ->
            CatalogCard(
                item = item,
                isInstalled = installed.any { it.name == item.fileName },
                inProgress = progress.containsKey(item.id),
                onDownload = { app.models.download(item) }
            )
        }

        BodyText(stringResource(R.string.models_custom_url))
        OutlinedTextField(
            value = customUrl,
            onValueChange = { customUrl = it },
            label = { Text(stringResource(R.string.models_custom_url_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        BigButton(
            text = stringResource(R.string.models_custom_download),
            icon = Icons.Filled.Download,
            outlined = true,
            enabled = customUrl.isNotBlank(),
            onClick = { app.models.downloadCustom(customUrl) }
        )

        // ---------------------------------------------------------- web links
        SectionHeader(stringResource(R.string.models_section_links))
        BodyText(stringResource(R.string.models_links_hint))
        ModelCatalog.links.forEach { link ->
            OutlinedButton(
                onClick = { openUrl(context, link.url) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(link.titleRes)) }
        }

        BodyText(stringResource(R.string.models_links_chat_hint))
        ModelCatalog.llmLinks.forEach { link ->
            OutlinedButton(
                onClick = { openUrl(context, link.url) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(link.titleRes)) }
        }

        InfoCard(stringResource(R.string.models_unsupported_note))
    }

    toDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(m.name) },
            text = { Text(stringResource(R.string.models_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    if (settings.labelModel == m.name) scope.launch { app.settings.setLabelModel("") }
                    if (settings.chatModel == m.name) {
                        scope.launch { app.settings.setChatModel("") }
                        app.chat.unload()
                    }
                    app.models.delete(m)
                    toDelete = null
                }) { Text(stringResource(R.string.yes)) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun CatalogCard(item: CatalogItem, isInstalled: Boolean, inProgress: Boolean, onDownload: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(item.titleRes), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(item.descRes), style = MaterialTheme.typography.bodyMedium)
            if (isInstalled) {
                Text(
                    stringResource(R.string.models_installed_badge),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            BigButton(
                text = stringResource(if (isInstalled) R.string.models_redownload else R.string.models_download_btn),
                icon = Icons.Filled.Download,
                outlined = isInstalled,
                enabled = !inProgress,
                onClick = onDownload
            )
        }
    }
}
