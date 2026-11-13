package com.baseerat.vision.chat

import android.content.Context
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Keeps one chat model loaded in memory and runs all inference on a single background thread.
 * Loading a model takes several seconds and uses 0.5-2 GB of RAM, so it is loaded on first use
 * and can be unloaded from the chat screen.
 */
class ChatEngine(context: Context) {

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()

    @Volatile
    private var instance: Any? = null

    @Volatile
    private var loadedPath: String? = null

    val isLoaded: Boolean get() = instance != null

    suspend fun generate(modelFile: File, prompt: String): Result<String> = withContext(dispatcher) {
        try {
            val inst = ensureLoaded(modelFile)
            Result.success(LlmBridge.generate(inst, prompt))
        } catch (e: Throwable) {
            Result.failure(LlmBridge.unwrap(e))
        }
    }

    private fun ensureLoaded(file: File): Any {
        val path = file.absolutePath
        val cur = instance
        if (cur != null && loadedPath == path) return cur
        if (cur != null) LlmBridge.close(cur)
        instance = null
        loadedPath = null
        val created = LlmBridge.create(appContext, path, MAX_TOKENS)
        instance = created
        loadedPath = path
        return created
    }

    fun unload() {
        executor.execute {
            instance?.let { LlmBridge.close(it) }
            instance = null
            loadedPath = null
        }
    }

    companion object {
        /** Prompt + answer must fit in this many tokens (most phone models use a 1280 KV cache). */
        const val MAX_TOKENS = 1024
    }
}
