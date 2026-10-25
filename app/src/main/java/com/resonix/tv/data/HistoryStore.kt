package com.resonix.tv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val uri: String,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayed: Long,
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** "Recently watched": newest first, remembers where you stopped. Observable by Compose. */
class HistoryStore private constructor(private val prefs: SharedPreferences) {

    val entries = mutableStateListOf<HistoryEntry>()

    init {
        load()
    }

    fun find(uri: String): HistoryEntry? = entries.firstOrNull { it.uri == uri }

    fun record(uri: String, title: String, positionMs: Long, durationMs: Long) {
        val index = entries.indexOfFirst { it.uri == uri }
        if (index >= 0) entries.removeAt(index)
        entries.add(0, HistoryEntry(uri, title, positionMs, durationMs, System.currentTimeMillis()))
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.lastIndex)
        save()
    }

    fun clear() {
        entries.clear()
        save()
    }

    private fun load() {
        val text = prefs.getString("entries", null) ?: return
        runCatching {
            val array = JSONArray(text)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                entries.add(
                    HistoryEntry(
                        uri = o.getString("uri"),
                        title = o.optString("title"),
                        positionMs = o.optLong("pos"),
                        durationMs = o.optLong("dur"),
                        lastPlayed = o.optLong("ts"),
                    ),
                )
            }
        }
    }

    private fun save() {
        val array = JSONArray()
        for (e in entries) {
            array.put(
                JSONObject()
                    .put("uri", e.uri)
                    .put("title", e.title)
                    .put("pos", e.positionMs)
                    .put("dur", e.durationMs)
                    .put("ts", e.lastPlayed),
            )
        }
        prefs.edit().putString("entries", array.toString()).apply()
    }

    companion object {
        private const val MAX_ENTRIES = 30

        @Volatile
        private var instance: HistoryStore? = null

        fun get(context: Context): HistoryStore =
            instance ?: synchronized(this) {
                instance ?: HistoryStore(
                    context.applicationContext.getSharedPreferences("resonix_history", Context.MODE_PRIVATE),
                ).also { instance = it }
            }
    }
}
