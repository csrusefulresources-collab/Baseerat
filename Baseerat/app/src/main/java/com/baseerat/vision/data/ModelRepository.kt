package com.baseerat.vision.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

enum class ModelKind { TESSERACT, TFLITE, LLM }

data class InstalledModel(
    val name: String,       // file name, e.g. urd.traineddata
    val kind: ModelKind,
    val file: File,
    val sizeBytes: Long
) {
    /** For Tesseract this is the language code ("urd"). */
    val langCode: String get() = name.removeSuffix(".traineddata")
}

data class DownloadProgress(val id: String, val done: Long, val total: Long)

enum class ModelEventType { IMPORTED, DOWNLOADED, DELETED, FAILED, UNSUPPORTED, CANCELLED }
data class ModelEvent(val type: ModelEventType, val name: String, val detail: String? = null)

/**
 * Keeps imported / downloaded offline models.
 *  - *.traineddata  -> Tesseract OCR language data (Urdu, Arabic, English, ...)
 *  - *.tflite       -> image-classification model (with metadata) used for object labels
 * Files live in the app's private storage; nothing is uploaded anywhere.
 */
class ModelRepository(private val context: Context, private val scope: CoroutineScope) {

    val tessRoot: File = File(context.filesDir, "tesseract")
    private val tessdataDir: File = File(tessRoot, "tessdata")
    private val tfliteDir: File = File(context.filesDir, "models")
    private val llmDir: File = File(context.filesDir, "llm")

    private val _installed = MutableStateFlow<List<InstalledModel>>(emptyList())
    val installed: StateFlow<List<InstalledModel>> = _installed

    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress

    private val _events = MutableSharedFlow<ModelEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ModelEvent> = _events

    private val jobs = HashMap<String, Job>()

    init {
        tessdataDir.mkdirs()
        tfliteDir.mkdirs()
        llmDir.mkdirs()
        refresh()
    }

    fun refresh() {
        val list = ArrayList<InstalledModel>()
        tessdataDir.listFiles { f -> f.isFile && f.name.endsWith(".traineddata") }?.forEach {
            list.add(InstalledModel(it.name, ModelKind.TESSERACT, it, it.length()))
        }
        tfliteDir.listFiles { f -> f.isFile && f.name.endsWith(".tflite") }?.forEach {
            list.add(InstalledModel(it.name, ModelKind.TFLITE, it, it.length()))
        }
        llmDir.listFiles { f -> f.isFile && (f.name.endsWith(".task") || f.name.endsWith(".bin")) }?.forEach {
            list.add(InstalledModel(it.name, ModelKind.LLM, it, it.length()))
        }
        _installed.value = list.sortedWith(compareBy({ it.kind }, { it.name }))
    }

    fun tessLangs(): List<String> =
        _installed.value.filter { it.kind == ModelKind.TESSERACT }.map { it.langCode }

    fun tfliteFile(name: String): File? =
        _installed.value.firstOrNull { it.kind == ModelKind.TFLITE && it.name == name }?.file

    fun llmFile(name: String): File? =
        _installed.value.firstOrNull { it.kind == ModelKind.LLM && it.name == name }?.file

    fun firstLlm(): File? = _installed.value.firstOrNull { it.kind == ModelKind.LLM }?.file

    fun hasLlm(): Boolean = _installed.value.any { it.kind == ModelKind.LLM }

    fun isInstalled(fileName: String): Boolean = _installed.value.any { it.name == fileName }

    fun delete(model: InstalledModel) {
        runCatching { model.file.delete() }
        refresh()
        _events.tryEmit(ModelEvent(ModelEventType.DELETED, model.name))
    }

    // ------------------------------------------------------------------ import

    fun importFromUri(uri: Uri) {
        scope.launch {
            val name = withContext(Dispatchers.IO) { displayName(uri) } ?: "model.bin"
            val kind = kindOf(name)
            if (kind == null) {
                _events.tryEmit(ModelEvent(ModelEventType.UNSUPPORTED, name))
                return@launch
            }
            val safe = normalizeName(kind, sanitize(name))
            val id = "import:$safe"
            _progress.value = _progress.value + (id to DownloadProgress(id, 0, -1))
            try {
                withContext(Dispatchers.IO) {
                    val dest = destFor(kind, safe)
                    val part = File(dest.parentFile, dest.name + ".part")
                    val total = querySize(uri)
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Cannot open file")
                    input.use { copyWithProgress(it, part) { done -> setProgress(id, done, total) } }
                    commitFile(kind, part, dest)
                }
                refresh()
                _events.tryEmit(ModelEvent(ModelEventType.IMPORTED, safe))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(ModelEvent(ModelEventType.FAILED, safe, e.message))
            } finally {
                _progress.value = _progress.value - id
            }
        }
    }

    // ---------------------------------------------------------------- download

    fun download(item: CatalogItem) = startDownload(item.id, item.url, item.fileName)

    /** Custom URL typed by the user; the file name must end with .traineddata or .tflite. */
    fun downloadCustom(url: String) {
        val trimmed = url.trim()
        val last = trimmed.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val rawName = sanitize(last)
        val kindGuess = kindOf(rawName)
        val name = if (kindGuess != null) normalizeName(kindGuess, rawName) else rawName
        if (!trimmed.startsWith("https://") || kindGuess == null) {
            _events.tryEmit(ModelEvent(ModelEventType.UNSUPPORTED, name.ifBlank { trimmed }))
            return
        }
        startDownload("custom:$name", trimmed, name)
    }

    private fun startDownload(id: String, url: String, fileName: String) {
        if (jobs[id]?.isActive == true) return
        val kind = kindOf(fileName) ?: return
        jobs[id] = scope.launch {
            _progress.value = _progress.value + (id to DownloadProgress(id, 0, -1))
            try {
                withContext(Dispatchers.IO) {
                    val dest = destFor(kind, fileName)
                    val part = File(dest.parentFile, dest.name + ".part")
                    httpDownload(url, part) { done, total -> setProgress(id, done, total) }
                    commitFile(kind, part, dest)
                }
                refresh()
                _events.tryEmit(ModelEvent(ModelEventType.DOWNLOADED, fileName))
            } catch (e: CancellationException) {
                _events.tryEmit(ModelEvent(ModelEventType.CANCELLED, fileName))
                throw e
            } catch (e: Exception) {
                _events.tryEmit(ModelEvent(ModelEventType.FAILED, fileName, e.message))
            } finally {
                _progress.value = _progress.value - id
                jobs.remove(id)
            }
        }
    }

    fun cancel(id: String) {
        jobs[id]?.cancel()
    }

    // ----------------------------------------------------------------- helpers

    private fun setProgress(id: String, done: Long, total: Long) {
        _progress.value = _progress.value + (id to DownloadProgress(id, done, total))
    }

    private fun kindOf(name: String): ModelKind? = when {
        name.endsWith(".traineddata", ignoreCase = true) -> ModelKind.TESSERACT
        name.endsWith(".tflite", ignoreCase = true) -> ModelKind.TFLITE
        name.endsWith(".task", ignoreCase = true) || name.endsWith(".bin", ignoreCase = true) -> ModelKind.LLM
        else -> null
    }

    /**
     * Browsers often save "urd.traineddata" as "urd (1).traineddata"; Tesseract needs the exact
     * language code as the file name, so keep only the leading letters.
     */
    private fun normalizeName(kind: ModelKind, name: String): String {
        if (kind != ModelKind.TESSERACT) return name
        val base = name.removeSuffix(".traineddata")
        val code = base.takeWhile { it.isLetter() || it == '_' }.trim('_')
        return (if (code.isNotEmpty()) code else base) + ".traineddata"
    }

    private fun destFor(kind: ModelKind, fileName: String): File = File(
        when (kind) {
            ModelKind.TESSERACT -> tessdataDir
            ModelKind.TFLITE -> tfliteDir
            ModelKind.LLM -> llmDir
        },
        fileName
    )

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
    } catch (_: Exception) {
        uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun querySize(uri: Uri): Long = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L
    } catch (_: Exception) {
        -1L
    }

    private fun copyWithProgress(input: InputStream, dest: File, onProgress: (Long) -> Unit) {
        FileOutputStream(dest).use { out ->
            val buf = ByteArray(64 * 1024)
            var done = 0L
            var lastReport = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                if (done - lastReport > 256 * 1024) {
                    onProgress(done); lastReport = done
                }
            }
            onProgress(done)
        }
    }

    /** Validates the temp file and moves it into place. */
    private fun commitFile(kind: ModelKind, part: File, dest: File) {
        try {
            if (part.length() < 1024) throw IOException("File is empty or too small")
            if (kind == ModelKind.LLM && part.length() < 1_000_000) throw IOException("File is too small to be a chat model")
            if (kind == ModelKind.TFLITE) {
                val head = ByteArray(8)
                part.inputStream().use { it.read(head) }
                val magic = String(head, 4, 4, Charsets.ISO_8859_1)
                if (magic != "TFL3") throw IOException("Not a valid TensorFlow Lite model")
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw IOException("Could not save model")
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }

    private suspend fun httpDownload(startUrl: String, dest: File, onProgress: (Long, Long) -> Unit) {
        var url = URL(startUrl)
        var redirects = 0
        while (true) {
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "Baseerat/2.0 (Android)")
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("Bad redirect")
                    url = URL(url, loc)
                    if (url.protocol != "https") throw IOException("Insecure redirect blocked")
                    if (++redirects > 6) throw IOException("Too many redirects")
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
                val total = conn.contentLengthLong
                val free = dest.parentFile?.usableSpace ?: Long.MAX_VALUE
                if (total > 0 && total + 64L * 1024 * 1024 > free) throw IOException("Not enough free storage")
                conn.inputStream.use { input ->
                    FileOutputStream(dest).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastReport > 128 * 1024) {
                                onProgress(done, total); lastReport = done
                            }
                        }
                        onProgress(done, total)
                    }
                }
                return
            } catch (e: Exception) {
                dest.delete()
                throw e
            } finally {
                conn.disconnect()
            }
        }
    }
}
