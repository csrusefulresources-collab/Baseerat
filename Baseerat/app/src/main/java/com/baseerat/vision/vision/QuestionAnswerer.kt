package com.baseerat.vision.vision

import android.content.Context
import com.baseerat.vision.R
import com.baseerat.vision.util.LabelTranslator
import com.baseerat.vision.util.LocaleHelper
import kotlin.math.roundToInt

/**
 * Offline question answering over the analysis result (text, colours, people, objects).
 * This is NOT a generative model: it answers the common questions reliably and says so
 * honestly for everything else. [ctx] must be localized.
 */
object QuestionAnswerer {

    private val textWords = listOf("text", "read", "written", "say", "says", "word", "لکھا", "لکھی", "لکھے", "متن", "پڑھ", "عبارت")
    private val colorWords = listOf("color", "colour", "رنگ")
    private val peopleWords = listOf("people", "person", "persons", "face", "faces", "how many", "کتنے", "لوگ", "شخص", "چہرے", "چہرہ", "افراد", "آدمی")
    private val whatWords = listOf("what", "object", "objects", "describe", "show", "کیا", "اشیاء", "چیز", "چیزیں", "بیان")
    private val personKeys = setOf("person", "people", "man", "woman", "boy", "girl", "child", "baby", "face", "crowd")

    fun answer(ctx: Context, question: String, a: Analysis): String {
        val q = question.lowercase().trim()
        val urdu = LocaleHelper.isUrdu(ctx)
        val sep = ctx.getString(R.string.list_sep)
        val mentioned = LabelTranslator.findMentioned(q)

        fun has(words: List<String>) = words.any { q.contains(it) }

        if (has(textWords)) {
            return if (a.ocr.text.isBlank()) ctx.getString(R.string.ans_no_text)
            else ctx.getString(R.string.ans_text_found, a.ocr.text.take(1200))
        }
        if (has(colorWords)) {
            return if (a.colors.isEmpty()) ctx.getString(R.string.ans_no_colors)
            else ctx.getString(R.string.ans_colors, a.colors.joinToString(sep) { ResultFormatter.colorName(ctx, it.key) })
        }
        if (has(peopleWords) || mentioned.any { it in personKeys }) {
            return when (a.faces.size) {
                0 -> ctx.getString(R.string.ans_people_none)
                1 -> ctx.getString(
                    R.string.ans_people_one,
                    ResultFormatter.positionName(ctx, a.faces[0].exactCenterX(), a.faces[0].exactCenterY(), a.width, a.height)
                )
                else -> ctx.getString(R.string.ans_people_many, a.faces.size)
            }
        }
        if (mentioned.isNotEmpty()) {
            val target = mentioned.first()
            val hit = a.labels.firstOrNull { it.text.lowercase() == target || it.text.lowercase().contains(target) }
            val shown = LabelTranslator.translate(target, urdu)
            return if (hit != null) ctx.getString(R.string.ans_present, shown, (hit.confidence * 100).roundToInt())
            else ctx.getString(R.string.ans_absent, shown, ResultFormatter.labelNames(ctx, a, 8).joinToString(sep).ifBlank { "-" })
        }
        if (has(whatWords)) {
            return if (a.labels.isEmpty()) ctx.getString(R.string.ans_no_objects)
            else ctx.getString(R.string.ans_objects, ResultFormatter.labelNames(ctx, a, 8).joinToString(sep))
        }
        return ctx.getString(R.string.ans_fallback, ResultFormatter.describe(ctx, a, false))
    }
}
