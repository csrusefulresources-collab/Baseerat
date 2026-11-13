package com.baseerat.vision.util

import android.graphics.Bitmap
import android.graphics.Color

data class NamedColor(val key: String, val share: Float)

/** Finds the dominant colours of a bitmap and maps them to simple colour keys. */
object ColorNamer {

    fun dominant(bitmap: Bitmap, max: Int = 3): List<NamedColor> {
        val small = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        val counts = HashMap<String, Int>()
        val hsv = FloatArray(3)
        var total = 0
        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                Color.colorToHSV(small.getPixel(x, y), hsv)
                val key = classify(hsv[0], hsv[1], hsv[2])
                counts[key] = (counts[key] ?: 0) + 1
                total++
            }
        }
        if (small !== bitmap) small.recycle()
        if (total == 0) return emptyList()
        return counts.entries
            .sortedByDescending { it.value }
            .map { NamedColor(it.key, it.value.toFloat() / total) }
            .filter { it.share >= 0.12f }
            .take(max)
    }

    fun classify(h: Float, s: Float, v: Float): String {
        if (v < 0.16f) return "black"
        if (s < 0.14f) {
            return when {
                v > 0.86f -> "white"
                v > 0.55f -> "light_gray"
                else -> "gray"
            }
        }
        if (v < 0.45f && h in 15f..50f) return "brown"
        return when {
            h < 15f || h >= 345f -> if (s < 0.45f && v > 0.8f) "pink" else "red"
            h < 40f -> if (v < 0.65f) "brown" else "orange"
            h < 70f -> "yellow"
            h < 170f -> "green"
            h < 200f -> "cyan"
            h < 260f -> "blue"
            h < 300f -> "purple"
            else -> "pink"
        }
    }
}
