package com.tat.quickreels.downloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small local history store. It intentionally stores metadata/URIs, not media bytes. */
object HistoryStore {
    private const val PREFS = "tat_download_history"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 200

    data class Entry(
        val title: String,
        val uri: String,
        val sizeBytes: Long,
        val mediaType: String,
        val createdAt: Long
    )

    @Synchronized
    fun add(context: Context, title: String, uri: String, sizeBytes: Long, mediaType: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = try { JSONArray(prefs.getString(KEY_ITEMS, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val next = JSONArray()
        next.put(JSONObject().apply {
            put("title", title)
            put("uri", uri)
            put("size", sizeBytes)
            put("type", mediaType)
            put("created", System.currentTimeMillis())
        })
        for (i in 0 until minOf(old.length(), MAX_ITEMS - 1)) {
            val item = old.optJSONObject(i)
            if (item != null) next.put(item)
        }
        prefs.edit().putString(KEY_ITEMS, next.toString()).apply()
    }

    @Synchronized
    fun getAll(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, "[]")
        val array = try { JSONArray(raw ?: "[]") } catch (_: Exception) { JSONArray() }
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(Entry(
                    title = item.optString("title", "Downloaded file"),
                    uri = item.optString("uri", ""),
                    sizeBytes = item.optLong("size", 0L),
                    mediaType = item.optString("type", "Media"),
                    createdAt = item.optLong("created", 0L)
                ))
            }
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ITEMS).apply()
    }
}
