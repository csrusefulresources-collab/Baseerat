package com.baseerat.vision.vision

import android.content.Context
import com.baseerat.vision.R
import com.baseerat.vision.util.LabelTranslator
import com.baseerat.vision.util.LocaleHelper

/** Turns an [Analysis] into readable (and speakable) Urdu / English text. [ctx] must be localized. */
object ResultFormatter {

    fun colorName(ctx: Context, key: String): String = ctx.getString(
        when (key) {
            "black" -> R.string.color_black
            "white" -> R.string.color_white
            "light_gray" -> R.string.color_light_gray
            "gray" -> R.string.color_gray
            "red" -> R.string.color_red
            "orange" -> R.string.color_orange
            "yellow" -> R.string.color_yellow
            "green" -> R.string.color_green
            "cyan" -> R.string.color_cyan
            "blue" -> R.string.color_blue
            "purple" -> R.string.color_purple
            "pink" -> R.string.color_pink
            else -> R.string.color_brown
        }
    )

    /** 3x3 grid position name for a point inside a [w] x [h] area. */
    fun positionName(ctx: Context, cx: Float, cy: Float, w: Int, h: Int): String {
        val col = ((cx / w.coerceAtLeast(1)) * 3).toInt().coerceIn(0, 2)
        val row = ((cy / h.coerceAtLeast(1)) * 3).toInt().coerceIn(0, 2)
        return ctx.getString(
            when (row * 3 + col) {
                0 -> R.string.pos_top_left
                1 -> R.string.pos_top
                2 -> R.string.pos_top_right
                3 -> R.string.pos_left
                4 -> R.string.pos_center
                5 -> R.string.pos_right
                6 -> R.string.pos_bottom_left
                7 -> R.string.pos_bottom
                else -> R.string.pos_bottom_right
            }
        )
    }

    fun labelNames(ctx: Context, a: Analysis, count: Int): List<String> =
        LabelTranslator.translateAll(a.labels.take(count).map { it.text }, LocaleHelper.isUrdu(ctx))

    fun peopleSentence(ctx: Context, count: Int): String =
        if (count == 1) ctx.getString(R.string.people_one) else ctx.getString(R.string.people_many, count)

    fun describe(ctx: Context, a: Analysis, detailed: Boolean): String {
        val sep = ctx.getString(R.string.list_sep)
        val summaryParts = ArrayList<String>()
        if (a.labels.isNotEmpty()) {
            summaryParts.add(ctx.getString(R.string.fmt_looks_like, labelNames(ctx, a, 3).joinToString(sep)))
        }
        if (a.faces.isNotEmpty()) summaryParts.add(peopleSentence(ctx, a.faces.size))
        if (a.ocr.text.isNotBlank()) summaryParts.add(ctx.getString(R.string.fmt_has_text))
        if (a.colors.isNotEmpty()) {
            val names = a.colors.map { colorName(ctx, it.key) }.joinToString(sep)
            summaryParts.add(ctx.getString(R.string.fmt_colors, names))
        }
        if (summaryParts.isEmpty()) summaryParts.add(ctx.getString(R.string.fmt_nothing))
        val summary = summaryParts.joinToString(" ")
        if (!detailed) return summary + customNote(ctx, a)

        val sb = StringBuilder(summary)
        if (a.labels.size > 3) {
            sb.append("\n\n").append(ctx.getString(R.string.hdr_objects)).append(' ')
                .append(labelNames(ctx, a, 8).joinToString(sep))
        }
        if (a.faces.isNotEmpty()) {
            sb.append("\n\n").append(ctx.getString(R.string.hdr_people))
            a.faces.take(8).forEachIndexed { i, r ->
                val pos = positionName(ctx, r.exactCenterX(), r.exactCenterY(), a.width, a.height)
                sb.append('\n').append(ctx.getString(R.string.fmt_face_at, i + 1, pos))
            }
        }
        if (a.ocr.text.isNotBlank()) {
            sb.append("\n\n").append(ctx.getString(R.string.hdr_text)).append('\n')
                .append(a.ocr.text.take(1500))
        } else {
            sb.append("\n\n").append(ctx.getString(R.string.txt_none))
        }
        sb.append(customNote(ctx, a))
        return sb.toString()
    }

    fun readText(ctx: Context, o: OcrOutcome): String {
        if (o.text.isBlank()) return ctx.getString(R.string.txt_none)
        val engine = ctx.getString(if (o.engine == "tesseract") R.string.engine_tesseract else R.string.engine_mlkit)
        val body = if (o.paragraphs.isNotEmpty()) o.paragraphs.joinToString("\n\n") else o.text
        return body + "\n\n" + ctx.getString(R.string.fmt_text_engine, engine)
    }

    private fun customNote(ctx: Context, a: Analysis): String =
        if (a.customModelFailed) "\n\n" + ctx.getString(R.string.custom_model_failed) else ""
}
