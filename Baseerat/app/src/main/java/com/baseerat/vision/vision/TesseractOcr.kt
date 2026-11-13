package com.baseerat.vision.vision

import android.graphics.Bitmap
import com.baseerat.vision.data.ModelRepository

/**
 * Tesseract access through REFLECTION, so the project builds with or without the library.
 * If `cz.adaptech.tesseract4android.TessBaseAPI` is on the classpath (auto-detected in
 * app/build.gradle.kts) it is used; otherwise [isAvailable] is false and OCR falls back to ML Kit.
 */
class TesseractOcr(private val models: ModelRepository) {

    fun recognize(bitmap: Bitmap, langs: List<String>): String {
        val cls = apiClass ?: return ""
        if (langs.isEmpty()) return ""
        val api = cls.getDeclaredConstructor().newInstance()
        try {
            // dataPath must be the PARENT of the "tessdata" folder
            val ok = cls.getMethod("init", String::class.java, String::class.java)
                .invoke(api, models.tessRoot.absolutePath, langs.joinToString("+")) as? Boolean ?: false
            if (!ok) return ""
            cls.getMethod("setImage", Bitmap::class.java).invoke(api, bitmap)
            val text = cls.getMethod("getUTF8Text").invoke(api) as? String
            return text?.trim().orEmpty()
        } finally {
            try {
                cls.getMethod("recycle").invoke(api)
            } catch (_: Throwable) {
                try {
                    cls.getMethod("end").invoke(api)
                } catch (_: Throwable) {
                }
            }
        }
    }

    companion object {
        private val apiClass: Class<*>? = try {
            Class.forName("cz.adaptech.tesseract4android.TessBaseAPI")
        } catch (_: Throwable) {
            null
        }

        /** True only when the Tesseract library was bundled into this build. */
        val isAvailable: Boolean get() = apiClass != null
    }
}
