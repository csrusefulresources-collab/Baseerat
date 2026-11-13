package com.baseerat.vision.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.max

object BitmapUtil {

    /** Folder for temporary inputs (camera shots, copied gallery images). */
    fun inputsDir(context: Context): File =
        File(context.cacheDir, "inputs").apply { mkdirs() }

    /**
     * Copies a content:// image into our own cache so other activities never hit
     * SecurityException (a content Uri grant is only valid for the receiving activity).
     */
    fun copyToCache(context: Context, uri: Uri): File {
        val out = File(inputsDir(context), "in_${System.currentTimeMillis()}.img")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open $uri")
        input.use { src -> FileOutputStream(out).use { dst -> src.copyTo(dst) } }
        trimCache(context)
        return out
    }

    private fun trimCache(context: Context) {
        val files = inputsDir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(8).forEach { it.delete() }
    }

    /** Decodes with down-sampling (no OOM on 50MP photos) and applies EXIF rotation. */
    fun decodeFile(path: String, maxSide: Int = 2048): Bitmap {
        val file = File(path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not an image")

        var sample = 1
        val longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= maxSide) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = file.inputStream().use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("Decode failed")

        val rotation = try {
            file.inputStream().use { readRotation(it) }
        } catch (_: Exception) {
            0
        }
        val scale = maxSide.toFloat() / max(bmp.width, bmp.height)
        if (rotation != 0 || scale < 1f) {
            val m = Matrix()
            if (rotation != 0) m.postRotate(rotation.toFloat())
            if (scale < 1f) m.postScale(scale, scale)
            val transformed = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (transformed !== bmp) bmp.recycle()
            bmp = transformed
        }
        return bmp
    }

    private fun readRotation(stream: InputStream): Int {
        val exif = ExifInterface(stream)
        return when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }

    fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val scale = maxSide.toFloat() / max(src.width, src.height)
        if (scale >= 1f) return src
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    /** Safe crop; returns null if the region is unusable. */
    fun crop(source: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Bitmap? {
        val l = left.coerceIn(0, source.width - 1)
        val t = top.coerceIn(0, source.height - 1)
        val r = right.coerceIn(l + 1, source.width)
        val b = bottom.coerceIn(t + 1, source.height)
        if (r - l < 8 || b - t < 8) return null
        return Bitmap.createBitmap(source, l, t, r - l, b - t)
    }

    /** Tiny crops are useless to OCR: upscale so the shortest side is at least [minSide]. */
    fun upscaleIfSmall(src: Bitmap, minSide: Int = 96): Bitmap {
        val shortest = minOf(src.width, src.height)
        if (shortest >= minSide) return src
        val f = minSide.toFloat() / shortest
        return Bitmap.createScaledBitmap(src, (src.width * f).toInt(), (src.height * f).toInt(), true)
    }
}
