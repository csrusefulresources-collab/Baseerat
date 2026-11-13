package com.baseerat.vision.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.baseerat.vision.data.AppSettings
import com.baseerat.vision.data.ModelRepository
import com.baseerat.vision.util.BitmapUtil
import com.baseerat.vision.util.ColorNamer
import com.baseerat.vision.util.LabelTranslator
import com.baseerat.vision.util.LocaleHelper
import com.baseerat.vision.util.NamedColor
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.custom.CustomImageLabelerOptions
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class LabelItem(val text: String, val confidence: Float)

data class OcrOutcome(
    val text: String = "",
    val paragraphs: List<String> = emptyList(),
    val engine: String = "mlkit"      // mlkit | tesseract
)

data class Analysis(
    val width: Int,
    val height: Int,
    val labels: List<LabelItem>,
    val ocr: OcrOutcome,
    val faces: List<Rect>,
    val colors: List<NamedColor>,
    val customModelFailed: Boolean = false
)

/**
 * Fully offline vision pipeline:
 *  - Object labels: ML Kit built-in model, or an imported .tflite classifier
 *  - OCR: ML Kit (Latin) and/or Tesseract (Urdu/Arabic/... via imported .traineddata)
 *  - People: ML Kit face detection (count + position)
 *  - Dominant colours
 */
class VisionEngine(
    context: Context,
    private val settings: () -> AppSettings,
    private val models: ModelRepository
) {
    private val appContext = context.applicationContext

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val defaultLabeler: ImageLabeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build())
    }
    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.05f)
                .build()
        )
    }
    private val tess = TesseractOcr(models)

    private var customLabeler: ImageLabeler? = null
    private var customPath: String? = null

    private val nonLatin = setOf("urd", "ara", "fas", "pus", "snd", "uig", "kur", "heb", "hin", "ben", "pan")

    // ---------------------------------------------------------------- analysis

    suspend fun analyze(bitmap: Bitmap, withOcr: Boolean = true, withFaces: Boolean = true): Analysis =
        withContext(Dispatchers.Default) {
            val s = settings()
            val image = InputImage.fromBitmap(bitmap, 0)

            var customFailed = false
            val labeler = labelerFor(s)
            var raw = runCatching { labeler.process(image).await() }.getOrNull()
            if (raw == null && labeler !== defaultLabeler) {
                customFailed = true
                raw = runCatching { defaultLabeler.process(image).await() }.getOrNull()
            }
            val labels = raw.orEmpty()
                .sortedByDescending { it.confidence }
                .take(8)
                .map { LabelItem(it.text, it.confidence) }

            val faces = if (withFaces) {
                runCatching { faceDetector.process(image).await().map { it.boundingBox } }
                    .getOrDefault(emptyList())
            } else emptyList()

            val ocr = if (withOcr) readTextInternal(bitmap, s) else OcrOutcome()
            val colors = runCatching { ColorNamer.dominant(bitmap) }.getOrDefault(emptyList())

            Analysis(bitmap.width, bitmap.height, labels, ocr, faces, colors, customFailed)
        }

    suspend fun readText(bitmap: Bitmap): OcrOutcome =
        withContext(Dispatchers.Default) { readTextInternal(bitmap, settings()) }

    /** Short name guess for an unlabeled button crop: visible text first, then top object label. */
    suspend fun guessLabel(crop: Bitmap): String? = withContext(Dispatchers.Default) {
        val bmp = BitmapUtil.upscaleIfSmall(crop, 96)
        val image = InputImage.fromBitmap(bmp, 0)
        val text = runCatching { recognizer.process(image).await().text.trim() }.getOrDefault("")
            .replace('\n', ' ')
        if (text.isNotBlank() && text.length <= 40) return@withContext text
        val top = runCatching { defaultLabeler.process(image).await() }.getOrNull()
            ?.filter { it.confidence >= 0.5f }?.maxByOrNull { it.confidence }
        top?.text?.let { LabelTranslator.translate(it, LocaleHelper.aiLang(appContext) == "ur") }
    }

    // --------------------------------------------------------------------- OCR

    private fun tessLangsFor(s: AppSettings): List<String> {
        val installed = models.tessLangs()
        if (installed.isEmpty()) return emptyList()
        val chosen = s.ocrLangs.split("+").filter { it.isNotBlank() && it in installed }
        if (chosen.isNotEmpty()) return chosen
        return installed.sortedWith(compareBy({ if (it == "urd") 0 else if (it == "eng") 1 else 2 }, { it })).take(3)
    }

    private suspend fun readTextInternal(bitmap: Bitmap, s: AppSettings): OcrOutcome {
        val langs = tessLangsFor(s)
        val useTess = TesseractOcr.isAvailable && when (s.ocrEngine) {
            "mlkit" -> false
            "tess" -> langs.isNotEmpty()
            else -> langs.any { it in nonLatin }       // auto: ML Kit cannot read Urdu/Arabic script
        }
        if (useTess) {
            val text = runCatching { tess.recognize(prepareForTesseract(bitmap), langs) }.getOrDefault("")
            if (text.count { it.isLetterOrDigit() } >= 3) {
                val paragraphs = text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
                return OcrOutcome(text, paragraphs, "tesseract")
            }
        }
        val mk = runCatching { recognizer.process(InputImage.fromBitmap(bitmap, 0)).await() }.getOrNull()
            ?: return OcrOutcome()
        return OcrOutcome(
            text = mk.text.trim(),
            paragraphs = mk.textBlocks.map { it.text.trim() }.filter { it.isNotEmpty() },
            engine = "mlkit"
        )
    }

    /** Tesseract likes ~300 dpi text: enlarge small images, shrink huge ones. */
    private fun prepareForTesseract(src: Bitmap): Bitmap {
        val longest = maxOf(src.width, src.height)
        return when {
            longest > 2600 -> BitmapUtil.scaleDown(src, 2600)
            longest < 1200 -> {
                val f = 1600f / longest
                Bitmap.createScaledBitmap(src, (src.width * f).toInt(), (src.height * f).toInt(), true)
            }
            else -> src
        }
    }

    // ------------------------------------------------------------------ labels

    private fun labelerFor(s: AppSettings): ImageLabeler {
        val name = s.labelModel
        if (name.isBlank()) return defaultLabeler
        val file = models.tfliteFile(name) ?: return defaultLabeler
        val existing = customLabeler
        if (existing != null && customPath == file.absolutePath) return existing
        runCatching { customLabeler?.close() }
        return try {
            val local = LocalModel.Builder().setAbsoluteFilePath(file.absolutePath).build()
            val options = CustomImageLabelerOptions.Builder(local)
                .setConfidenceThreshold(0.45f)
                .setMaxResultCount(8)
                .build()
            val created = ImageLabeling.getClient(options)
            customLabeler = created
            customPath = file.absolutePath
            created
        } catch (_: Exception) {
            customLabeler = null
            customPath = null
            defaultLabeler
        }
    }
}
