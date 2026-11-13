package com.baseerat.vision.chat

/** One chat turn. role = "user" | "assistant". */
data class ChatTurn(val role: String, val text: String)

/**
 * Builds the raw prompt text. Different model families expect different chat templates;
 * the family is guessed from the model's file name.
 */
object ChatTemplate {

    fun build(modelFileName: String, system: String, turns: List<ChatTurn>): String {
        val n = modelFileName.lowercase()
        return when {
            n.contains("gemma") -> gemma(system, turns)
            n.contains("qwen") || n.contains("smollm") -> chatMl(system, turns)
            else -> plain(system, turns)
        }
    }

    private fun gemma(system: String, turns: List<ChatTurn>): String {
        val sb = StringBuilder()
        var first = true
        for (t in turns) {
            if (t.role == "user") {
                sb.append("<start_of_turn>user\n")
                if (first) sb.append(system).append("\n\n")
                sb.append(t.text).append("<end_of_turn>\n")
                first = false
            } else {
                sb.append("<start_of_turn>model\n").append(t.text).append("<end_of_turn>\n")
            }
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    private fun chatMl(system: String, turns: List<ChatTurn>): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        for (t in turns) {
            val role = if (t.role == "user") "user" else "assistant"
            sb.append("<|im_start|>").append(role).append('\n').append(t.text).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    private fun plain(system: String, turns: List<ChatTurn>): String {
        val sb = StringBuilder()
        sb.append(system).append("\n\n")
        for (t in turns) {
            sb.append(if (t.role == "user") "User: " else "Assistant: ").append(t.text).append('\n')
        }
        sb.append("Assistant:")
        return sb.toString()
    }

    private val stopMarkers = listOf(
        "<end_of_turn>", "<start_of_turn>", "<|im_end|>", "<|im_start|>", "<|endoftext|>", "</s>", "<eos>"
    )

    /** Removes template tokens the model may have echoed. */
    fun clean(raw: String): String {
        var s = raw
        for (m in stopMarkers) {
            val i = s.indexOf(m)
            if (i >= 0) s = s.substring(0, i)
        }
        return s.trim()
    }

    /** System prompt. [lang] is "ur" or "en"; [facts] describes an attached picture (may be blank). */
    fun systemPrompt(lang: String, facts: String): String {
        val langRule = if (lang == "ur") {
            "Reply ONLY in Urdu (Arabic script) with simple, short sentences. Even if the user writes in English, answer in Urdu."
        } else {
            "Reply in clear, simple English with short sentences."
        }
        val base = "You are Baseerat, a friendly offline assistant for blind and low-vision users. " +
            "Be helpful, honest and brief. If you are not sure, say so. $langRule"
        if (facts.isBlank()) return base
        return base + "\n\nThe user shared a picture. These facts come from an on-device detector and may be imperfect. " +
            "For questions about the picture use only these facts; if something is not listed, say you cannot tell.\n" + facts
    }
}
