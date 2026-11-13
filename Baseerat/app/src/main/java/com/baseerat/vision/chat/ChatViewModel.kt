package com.baseerat.vision.chat

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.baseerat.vision.BaseeratApp
import com.baseerat.vision.R
import com.baseerat.vision.data.AppSettings
import com.baseerat.vision.util.BitmapUtil
import com.baseerat.vision.util.LocaleHelper
import com.baseerat.vision.vision.Analysis
import com.baseerat.vision.vision.PictureFacts
import com.baseerat.vision.vision.QuestionAnswerer
import com.baseerat.vision.vision.ResultFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Lets other screens (Describe screen, service) hand a picture / an "open chat" request to the chat tab. */
object ChatHub {
    val pendingPicture = MutableStateFlow<String?>(null)
    val openRequest = MutableStateFlow(0)
}

data class ChatMessage(val id: Long, val fromUser: Boolean, val text: String)

data class ChatPicture(val preview: Bitmap?, val analysis: Analysis, val facts: String)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BaseeratApp

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    /** String-resource id describing what is happening ("loading model", "thinking"), or null when idle. */
    private val _busy = MutableStateFlow<Int?>(null)
    val busy: StateFlow<Int?> = _busy

    private val _picture = MutableStateFlow<ChatPicture?>(null)
    val picture: StateFlow<ChatPicture?> = _picture

    private var nextId = 1L
    private var announcedId = 0L

    /** True once per message id: used so a screen-reader announcement is not repeated on tab switches. */
    fun shouldAnnounce(id: Long): Boolean {
        if (id <= announcedId) return false
        announcedId = id
        return true
    }

    private fun add(fromUser: Boolean, text: String) {
        _messages.value = _messages.value + ChatMessage(nextId++, fromUser, text)
    }

    private fun speak(s: AppSettings, text: String) {
        if (s.autoSpeak) app.speech.speak(text, s.aiLang)
    }

    fun attachPicture(path: String) {
        if (_busy.value != null) return
        viewModelScope.launch {
            _busy.value = R.string.analyzing
            val s = app.settings.current
            val actx = LocaleHelper.wrapAi(app)
            try {
                val bmp = withContext(Dispatchers.IO) { BitmapUtil.decodeFile(File(path).absolutePath) }
                val analysis = app.vision.analyze(bmp)
                val preview = BitmapUtil.scaleDown(bmp, 320)
                _picture.value = ChatPicture(preview, analysis, PictureFacts.build(analysis))
                val summary = ResultFormatter.describe(actx, analysis, false)
                val text = actx.getString(R.string.chat_picture_attached) + "\n" + summary
                add(false, text)
                speak(s, text)
            } catch (e: Exception) {
                add(false, actx.getString(R.string.describe_error))
            } finally {
                _busy.value = null
            }
        }
    }

    /** Adds an assistant message (used for small status/error texts from the UI). */
    fun say(text: String) {
        add(false, text)
    }

    fun removePicture() {
        _picture.value = null
    }

    fun clear() {
        _messages.value = emptyList()
        _picture.value = null
    }

    fun unloadModel() {
        app.chat.unload()
    }

    private fun pickModel(s: AppSettings): File? {
        if (s.chatModel.isNotBlank()) app.models.llmFile(s.chatModel)?.let { return it }
        return app.models.firstLlm()
    }

    fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty() || _busy.value != null) return
        add(true, q)
        viewModelScope.launch {
            val s = app.settings.current
            val actx = LocaleHelper.wrapAi(app)
            val model = pickModel(s)
            val pic = _picture.value

            // No chat engine / no model: answer picture questions with the offline rules, otherwise explain.
            if (model == null || !LlmBridge.isAvailable) {
                val reply = if (pic != null) {
                    QuestionAnswerer.answer(actx, q, pic.analysis)
                } else {
                    actx.getString(if (!LlmBridge.isAvailable) R.string.chat_no_engine else R.string.chat_no_model)
                }
                add(false, reply)
                speak(s, reply)
                return@launch
            }

            _busy.value = if (app.chat.isLoaded) R.string.chat_thinking else R.string.chat_loading_model
            val system = ChatTemplate.systemPrompt(s.aiLang, pic?.facts ?: "")
            var turns = recentTurns(8)
            var result = app.chat.generate(model, ChatTemplate.build(model.name, system, turns))
            if (result.isFailure && turns.size > 1) {
                // most common failure: prompt too long for the model's context window -> retry without history
                turns = listOf(ChatTurn("user", q))
                result = app.chat.generate(model, ChatTemplate.build(model.name, system, turns))
            }
            _busy.value = null
            val reply = result.fold(
                onSuccess = { ChatTemplate.clean(it).ifBlank { actx.getString(R.string.chat_empty_reply) } },
                onFailure = { actx.getString(R.string.chat_error, it.message ?: it.javaClass.simpleName) }
            )
            add(false, reply)
            speak(s, reply)
        }
    }

    /** Last few messages as chat turns, trimmed to a character budget so they fit the model's context. */
    private fun recentTurns(maxTurns: Int): List<ChatTurn> {
        val all = _messages.value.takeLast(maxTurns)
        val out = ArrayList<ChatTurn>()
        var budget = 1400
        for (m in all.asReversed()) {
            val t = if (m.text.length > 600) m.text.take(600) else m.text
            if (budget - t.length < 0 && out.isNotEmpty()) break
            budget -= t.length
            out.add(0, ChatTurn(if (m.fromUser) "user" else "assistant", t))
        }
        // a conversation must start with a user turn
        while (out.isNotEmpty() && out.first().role != "user") out.removeAt(0)
        return out
    }
}
