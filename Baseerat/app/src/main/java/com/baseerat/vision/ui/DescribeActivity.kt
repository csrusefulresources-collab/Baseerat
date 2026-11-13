package com.baseerat.vision.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.baseerat.vision.BaseeratApp
import com.baseerat.vision.R
import com.baseerat.vision.app
import com.baseerat.vision.chat.ChatHub
import com.baseerat.vision.ui.components.BigButton
import com.baseerat.vision.ui.components.BodyText
import com.baseerat.vision.ui.components.InfoCard
import com.baseerat.vision.ui.components.PageColumn
import com.baseerat.vision.ui.components.RadioRow
import com.baseerat.vision.ui.components.SectionHeader
import com.baseerat.vision.ui.theme.AppTheme
import com.baseerat.vision.util.BitmapUtil
import com.baseerat.vision.util.Haptics
import com.baseerat.vision.util.LocaleHelper
import com.baseerat.vision.vision.Analysis
import com.baseerat.vision.vision.QuestionAnswerer
import com.baseerat.vision.vision.ResultFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================================ ViewModel

class DescribeViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val loading: Boolean = true,
        val failed: Boolean = false,
        val analysis: Analysis? = null,
        val preview: Bitmap? = null
    )

    private val baseeratApp = application as BaseeratApp
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private val _answer = MutableStateFlow<String?>(null)
    val answer: StateFlow<String?> = _answer
    private var loadedPath: String? = null

    /** Runs the analysis once; survives screen rotation. */
    fun load(path: String, mode: String, source: String) {
        if (loadedPath == path) return
        loadedPath = path
        viewModelScope.launch {
            _state.value = UiState(loading = true)
            try {
                val bmp = withContext(Dispatchers.IO) { BitmapUtil.decodeFile(path) }
                val analysis = baseeratApp.vision.analyze(bmp)
                val preview = BitmapUtil.scaleDown(bmp, 480)
                _state.value = UiState(loading = false, analysis = analysis, preview = preview)

                val s = baseeratApp.settings.current
                if (s.saveHistory) {
                    val ctx = LocaleHelper.wrapAi(baseeratApp)
                    val text = if (mode == "read") ResultFormatter.readText(ctx, analysis.ocr)
                    else ResultFormatter.describe(ctx, analysis, s.detailed)
                    baseeratApp.history.add(source, mode, text)
                }
            } catch (e: Exception) {
                _state.value = UiState(loading = false, failed = true)
            }
        }
    }

    fun ask(ctx: Context, question: String) {
        val a = _state.value.analysis ?: return
        if (question.isBlank()) return
        _answer.value = QuestionAnswerer.answer(ctx, question, a)
    }
}

// ============================================================================ Activity

class DescribeActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            finish()
            return
        }
        val mode = intent.getStringExtra(EXTRA_MODE) ?: "describe"
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "gallery"
        setContent {
            AppTheme {
                DescribeScreen(path = path, initialMode = mode, source = source, onBack = { finish() })
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_MODE = "mode"
        const val EXTRA_SOURCE = "source"

        fun intent(context: Context, path: String, mode: String, source: String): Intent =
            Intent(context, DescribeActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_SOURCE, source)
    }
}

// ============================================================================ Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DescribeScreen(path: String, initialMode: String, source: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val vm: DescribeViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val answer by vm.answer.collectAsStateWithLifecycle()
    val settings by app.settings.flow.collectAsStateWithLifecycle(initialValue = app.settings.current)
    val snack = remember { SnackbarHostState() }
    val view = LocalView.current

    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var detailed by rememberSaveable { mutableStateOf(app.settings.current.detailed) }
    var question by rememberSaveable { mutableStateOf("") }

    // results/answers use the AI response language; headings and buttons use the UI language
    val lctx = remember(settings.language, settings.aiLanguage) { LocaleHelper.wrapAi(context) }
    val lang = settings.aiLang
    val touchExploration = remember {
        context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    }
    val analyzingText = stringResource(R.string.analyzing)
    val copiedMsg = stringResource(R.string.copied)

    LaunchedEffect(path) { vm.load(path, initialMode, source) }
    DisposableEffect(Unit) { onDispose { app.speech.stop() } }

    val analysis = state.analysis
    val resultText: String? = remember(analysis, mode, detailed, lang) {
        analysis?.let {
            if (mode == "read") ResultFormatter.readText(lctx, it.ocr)
            else ResultFormatter.describe(lctx, it, detailed)
        }
    }

    LaunchedEffect(state.loading) {
        if (state.loading && touchExploration) view.announceForAccessibility(analyzingText)
    }
    LaunchedEffect(resultText) {
        val t = resultText ?: return@LaunchedEffect
        if (settings.vibrate) Haptics.tick(context)
        if (settings.autoSpeak) app.speech.speak(t, lang)
        else if (touchExploration) view.announceForAccessibility(t)
    }
    LaunchedEffect(answer) {
        val a = answer ?: return@LaunchedEffect
        if (settings.autoSpeak) app.speech.speak(a, lang)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.describe_title), modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { padding ->
        PageColumn(Modifier.padding(padding)) {
            state.preview?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,           // decorative: the description below IS the content
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit
                )
            }

            if (state.loading) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator()
                    Text(analyzingText, style = MaterialTheme.typography.titleMedium)
                }
            }
            if (state.failed) InfoCard(stringResource(R.string.describe_error))

            if (analysis != null) {
                SectionHeader(stringResource(R.string.describe_section_result))
                if (resultText != null) {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Text(
                            resultText,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }

                BigButton(
                    text = stringResource(R.string.btn_speak),
                    icon = Icons.Filled.VolumeUp,
                    onClick = { resultText?.let { app.speech.speak(it, lang) } }
                )
                BigButton(
                    text = stringResource(R.string.btn_stop),
                    icon = Icons.Filled.Stop,
                    outlined = true,
                    onClick = { app.speech.stop() }
                )
                BigButton(
                    text = stringResource(R.string.btn_copy),
                    icon = Icons.Filled.ContentCopy,
                    outlined = true,
                    onClick = {
                        resultText?.let {
                            context.getSystemService(android.content.ClipboardManager::class.java)
                                ?.setPrimaryClip(android.content.ClipData.newPlainText("baseerat", it))
                            scope.launch { snack.showSnackbar(copiedMsg) }
                        }
                    }
                )
                BigButton(
                    text = stringResource(R.string.btn_chat_about),
                    icon = Icons.Filled.Chat,
                    onClick = {
                        ChatHub.pendingPicture.value = path
                        context.startActivity(
                            Intent(context, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                .putExtra(MainActivity.EXTRA_OPEN, "chat")
                        )
                    }
                )
                BigButton(
                    text = stringResource(R.string.btn_share),
                    icon = Icons.Filled.Share,
                    outlined = true,
                    onClick = {
                        resultText?.let {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, it)
                            }
                            context.startActivity(Intent.createChooser(send, null))
                        }
                    }
                )

                SectionHeader(stringResource(R.string.describe_section_mode))
                Column(Modifier.selectableGroup()) {
                    RadioRow(stringResource(R.string.mode_describe), mode == "describe", { mode = "describe" })
                    RadioRow(stringResource(R.string.mode_read), mode == "read", { mode = "read" })
                }
                if (mode == "describe") {
                    Column(Modifier.selectableGroup()) {
                        RadioRow(stringResource(R.string.detail_brief), !detailed, { detailed = false })
                        RadioRow(stringResource(R.string.detail_full), detailed, { detailed = true })
                    }
                }

                SectionHeader(stringResource(R.string.ask_title))
                BodyText(stringResource(R.string.ask_hint))
                val quick = listOf(
                    R.string.q_objects, R.string.q_text, R.string.q_colors, R.string.q_people
                )
                quick.forEach { q ->
                    val label = stringResource(q)
                    OutlinedButton(
                        onClick = { vm.ask(lctx, label) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    ) { Text(label) }
                }
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    label = { Text(stringResource(R.string.ask_field)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.ask(lctx, question) })
                )
                Button(
                    onClick = { vm.ask(lctx, question) },
                    enabled = question.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                ) { Text(stringResource(R.string.ask_btn)) }

                answer?.let {
                    InfoCard(
                        text = it,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}
