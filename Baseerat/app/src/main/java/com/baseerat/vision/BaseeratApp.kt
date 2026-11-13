package com.baseerat.vision

import android.app.Application
import android.content.Context
import com.baseerat.vision.chat.ChatEngine
import com.baseerat.vision.data.HistoryRepository
import com.baseerat.vision.data.ModelRepository
import com.baseerat.vision.data.SettingsRepository
import com.baseerat.vision.util.SpeechHelper
import com.baseerat.vision.vision.VisionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BaseeratApp : Application() {

    /** Lives as long as the process; used for downloads and settings collection. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var settings: SettingsRepository
        private set
    lateinit var history: HistoryRepository
        private set
    lateinit var models: ModelRepository
        private set
    lateinit var speech: SpeechHelper
        private set
    lateinit var vision: VisionEngine
        private set
    lateinit var chat: ChatEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsRepository(this)
        history = HistoryRepository(this)
        models = ModelRepository(this, appScope)
        speech = SpeechHelper(this)
        vision = VisionEngine(this, { settings.current }, models)
        chat = ChatEngine(this)

        // Keep a synchronous snapshot of the settings and push the voice config to the TTS engine.
        appScope.launch {
            settings.flow.collect {
                settings.current = it
                speech.applyConfig(it.tts)
            }
        }
    }

    companion object {
        lateinit var instance: BaseeratApp
            private set
    }
}

val Context.app: BaseeratApp get() = applicationContext as BaseeratApp
