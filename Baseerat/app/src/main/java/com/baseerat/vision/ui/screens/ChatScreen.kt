package com.baseerat.vision.ui.screens

import android.app.Activity
import android.content.Intent
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.baseerat.vision.R
import com.baseerat.vision.app
import com.baseerat.vision.chat.ChatHub
import com.baseerat.vision.chat.ChatMessage
import com.baseerat.vision.chat.ChatPicture
import com.baseerat.vision.chat.ChatViewModel
import com.baseerat.vision.chat.LlmBridge
import com.baseerat.vision.data.ModelKind
import com.baseerat.vision.ui.CameraActivity
import com.baseerat.vision.ui.components.BigButton
import com.baseerat.vision.ui.components.BodyText
import com.baseerat.vision.ui.components.InfoCard
import com.baseerat.vision.ui.components.findActivity
import com.baseerat.vision.util.BitmapUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chat tab: talk to an offline chat model, optionally about a picture.
 * Without a chat model it still answers questions about an attached picture (offline rules).
 */
@Composable
fun ChatScreen(onOpenModels: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val owner = context.findActivity() as ComponentActivity
    val vm: ChatViewModel = viewModel(viewModelStoreOwner = owner)

    val messages by vm.messages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val picture by vm.picture.collectAsStateWithLifecycle()
    val pending by ChatHub.pendingPicture.collectAsStateWithLifecycle()
    val settings by app.settings.flow.collectAsStateWithLifecycle(initialValue = app.settings.current)
    val installed by app.models.installed.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }
    val touchExploration = remember {
        context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    }
    val engineThere = LlmBridge.isAvailable
    val hasModel = engineThere && installed.any { it.kind == ModelKind.LLM }
    val busyRes = busy
    val errOpen = stringResource(R.string.err_open_image)

    // a picture handed over from the Describe screen
    LaunchedEffect(pending) {
        val p = pending
        if (p != null) {
            vm.attachPicture(p)
            ChatHub.pendingPicture.value = null
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val f = withContext(Dispatchers.IO) { BitmapUtil.copyToCache(context, uri) }
                    vm.attachPicture(f.absolutePath)
                } catch (_: Exception) {
                    vm.say(errOpen)
                }
            }
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            res.data?.getStringExtra(CameraActivity.EXTRA_PATH)?.let { vm.attachPicture(it) }
        }
    }

    // screen readers: announce a new answer once (when we are not speaking it ourselves)
    val last = messages.lastOrNull()
    LaunchedEffect(last?.id) {
        val m = last ?: return@LaunchedEffect
        if (!m.fromUser && !settings.autoSpeak && touchExploration && vm.shouldAnnounce(m.id)) {
            view.announceForAccessibility(m.text)
        }
    }

    // keep the newest message in view
    val total = 1 + messages.size + (if (busyRes != null) 1 else 0)
    LaunchedEffect(messages.size, busyRes) {
        listState.animateScrollToItem((total - 1).coerceAtLeast(0))
    }

    fun submit() {
        if (input.isNotBlank() && busyRes == null) {
            vm.send(input)
            input = ""
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!hasModel) {
                        InfoCard(stringResource(if (!engineThere) R.string.chat_no_engine_banner else R.string.chat_no_model_banner))
                        BigButton(
                            text = stringResource(R.string.chat_open_models),
                            icon = Icons.Filled.PhotoLibrary,
                            outlined = true,
                            onClick = onOpenModels
                        )
                    }
                    BodyText(stringResource(R.string.chat_intro))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                cameraLauncher.launch(
                                    Intent(context, CameraActivity::class.java)
                                        .putExtra(CameraActivity.EXTRA_MODE, "describe")
                                        .putExtra(CameraActivity.EXTRA_RETURN, true)
                                )
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                        ) {
                            Icon(Icons.Filled.CameraAlt, contentDescription = null)
                            Text(stringResource(R.string.chat_attach_camera), modifier = Modifier.padding(start = 8.dp))
                        }
                        OutlinedButton(
                            onClick = { galleryLauncher.launch("image/*") },
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                        ) {
                            Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                            Text(stringResource(R.string.chat_attach_gallery), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    picture?.let { PictureCard(it, onRemove = { vm.removePicture() }) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { vm.clear() }) { Text(stringResource(R.string.chat_new)) }
                        if (app.chat.isLoaded) {
                            TextButton(onClick = { vm.unloadModel() }) { Text(stringResource(R.string.chat_unload)) }
                        }
                    }
                }
            }
            items(messages, key = { it.id }) { m -> MessageBubble(m) }
            if (busyRes != null) {
                item {
                    Row(
                        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                        Text(stringResource(busyRes), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.chat_input_label)) },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() })
                )
                Button(
                    onClick = { submit() },
                    enabled = input.isNotBlank() && busyRes == null,
                    modifier = Modifier.heightIn(min = 56.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    Text(stringResource(R.string.chat_send), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(m: ChatMessage) {
    val who = stringResource(if (m.fromUser) R.string.chat_you else R.string.chat_assistant)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (m.fromUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        // one focus stop for TalkBack: "You: ..." / "Baseerat: ..."
        Column(
            Modifier.padding(14.dp).semantics(mergeDescendants = true) { },
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(who, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(m.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun PictureCard(p: ChatPicture, onRemove: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            p.preview?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.chat_picture_ready), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onRemove) { Text(stringResource(R.string.chat_remove_picture)) }
            }
        }
    }
}
