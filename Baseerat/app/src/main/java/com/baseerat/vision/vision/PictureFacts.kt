package com.baseerat.vision.vision

import java.util.Locale

/** Plain-English fact sheet about a picture; fed to the chat model as context. */
object PictureFacts {

    fun build(a: Analysis): String {
        val sb = StringBuilder()
        if (a.labels.isNotEmpty()) {
            sb.append("- Objects detected: ")
                .append(a.labels.take(8).joinToString(", ") {
                    it.text + " (" + (it.confidence * 100).toInt() + "%)"
                })
                .append('\n')
        } else {
            sb.append("- No clear objects were detected.\n")
        }
        if (a.faces.isEmpty()) {
            sb.append("- No people/faces detected.\n")
        } else {
            sb.append("- People visible: ").append(a.faces.size).append(" (")
                .append(a.faces.take(6).joinToString("; ") { r ->
                    position(r.exactCenterX(), r.exactCenterY(), a.width, a.height)
                })
                .append(")\n")
        }
        if (a.colors.isNotEmpty()) {
            sb.append("- Dominant colors: ").append(a.colors.joinToString(", ") { it.key.replace('_', ' ') }).append('\n')
        }
        if (a.ocr.text.isNotBlank()) {
            sb.append("- Text found in the picture: \"").append(a.ocr.text.take(700).replace('\n', ' ')).append("\"\n")
        } else {
            sb.append("- No readable text found.\n")
        }
        sb.append(String.format(Locale.US, "- Picture size: %dx%d pixels.\n", a.width, a.height))
        return sb.toString().trim()
    }

    private fun position(cx: Float, cy: Float, w: Int, h: Int): String {
        val col = ((cx / w.coerceAtLeast(1)) * 3).toInt().coerceIn(0, 2)
        val row = ((cy / h.coerceAtLeast(1)) * 3).toInt().coerceIn(0, 2)
        val v = arrayOf("top", "middle", "bottom")[row]
        val hz = arrayOf("left", "center", "right")[col]
        return "$v $hz"
    }
}
