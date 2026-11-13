package com.baseerat.vision.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
    val id: Long,
    val time: Long,
    val source: String,   // camera | gallery | share | screen | focus | text | elements
    val mode: String,     // describe | read | elements
    val text: String
)

/** Small on-device history (text only, no images are stored). */
class HistoryRepository(context: Context) {

    private val file = File(context.filesDir, "history.json")
    private val maxItems = 100

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<HistoryEntry>> = _items

    private fun load(): List<HistoryEntry> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                HistoryEntry(
                    id = o.getLong("id"),
                    time = o.getLong("time"),
                    source = o.optString("source"),
                    mode = o.optString("mode"),
                    text = o.optString("text")
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    private fun persist(list: List<HistoryEntry>) {
        try {
            val arr = JSONArray()
            list.forEach {
                arr.put(
                    JSONObject()
                        .put("id", it.id).put("time", it.time)
                        .put("source", it.source).put("mode", it.mode).put("text", it.text)
                )
            }
            file.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun add(source: String, mode: String, text: String) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        val list = (listOf(HistoryEntry(now, now, source, mode, text)) + _items.value).take(maxItems)
        _items.value = list
        persist(list)
    }

    @Synchronized
    fun remove(id: Long) {
        val list = _items.value.filterNot { it.id == id }
        _items.value = list
        persist(list)
    }

    @Synchronized
    fun clear() {
        _items.value = emptyList()
        persist(emptyList())
    }
}
