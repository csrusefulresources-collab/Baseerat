package com.baseerat.vision.chat

import android.content.Context
import java.lang.reflect.InvocationTargetException

/**
 * Access to the MediaPipe LLM Inference engine through REFLECTION, so the project builds with or
 * without `com.google.mediapipe:tasks-genai` (it is auto-detected in app/build.gradle.kts).
 * Only the long-stable, text-only part of the API is used.
 */
object LlmBridge {
    private const val PKG = "com.google.mediapipe.tasks.genai.llminference."

    private val llmClass: Class<*>? = try {
        Class.forName(PKG + "LlmInference")
    } catch (_: Throwable) {
        null
    }

    /** True only when the MediaPipe GenAI library is part of this build. */
    val isAvailable: Boolean get() = llmClass != null

    fun create(context: Context, modelPath: String, maxTokens: Int): Any {
        val cls = llmClass ?: throw IllegalStateException("LLM engine is not part of this build")
        val optionsCls = Class.forName(PKG + "LlmInference\$LlmInferenceOptions")
        val builderCls = Class.forName(PKG + "LlmInference\$LlmInferenceOptions\$Builder")
        val builder = optionsCls.getMethod("builder").invoke(null)
        builderCls.getMethod("setModelPath", String::class.java).invoke(builder, modelPath)
        builderCls.getMethod("setMaxTokens", Int::class.javaPrimitiveType).invoke(builder, maxTokens)
        val options = builderCls.getMethod("build").invoke(builder)
        return cls.getMethod("createFromOptions", Context::class.java, optionsCls).invoke(null, context, options)
            ?: throw IllegalStateException("Model could not be loaded")
    }

    fun generate(instance: Any, prompt: String): String {
        val cls = llmClass ?: throw IllegalStateException("LLM engine is not part of this build")
        return cls.getMethod("generateResponse", String::class.java).invoke(instance, prompt) as? String ?: ""
    }

    fun close(instance: Any) {
        try {
            llmClass?.getMethod("close")?.invoke(instance)
        } catch (_: Throwable) {
        }
    }

    /** Reflection wraps real errors in InvocationTargetException: show the real one. */
    fun unwrap(e: Throwable): Throwable =
        if (e is InvocationTargetException) (e.targetException ?: e) else e
}
