package com.fastreader.app.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class RecentDocument(
    val uri: Uri,
    val name: String,
    val type: String,
    val openedAt: Long,
)

/** 최근 문서 목록과 문서별 마지막 읽은 위치를 SharedPreferences 에 저장한다. */
class RecentDocuments(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("recent", Context.MODE_PRIVATE)

    fun list(): List<RecentDocument> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val uri = o.optString("uri").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                RecentDocument(Uri.parse(uri), o.optString("name"), o.optString("type"), o.optLong("time"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(doc: RecentDocument) {
        val items = list().filterNot { it.uri == doc.uri }.toMutableList()
        items.add(0, doc)
        save(items.take(MAX))
    }

    fun remove(uri: Uri) {
        save(list().filterNot { it.uri == uri })
        prefs.edit().remove(posKey(uri)).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun savePosition(uri: Uri, index: Int, offset: Int) {
        prefs.edit().putString(posKey(uri), "$index,$offset").apply()
    }

    fun position(uri: Uri): Pair<Int, Int>? {
        val v = prefs.getString(posKey(uri), null) ?: return null
        val parts = v.split(',')
        val index = parts.getOrNull(0)?.toIntOrNull() ?: return null
        return index to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private fun save(items: List<RecentDocument>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("uri", it.uri.toString())
                    .put("name", it.name)
                    .put("type", it.type)
                    .put("time", it.openedAt),
            )
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun posKey(uri: Uri) = "pos:" + uri.toString().hashCode()

    companion object {
        private const val KEY_LIST = "list"
        private const val MAX = 30
    }
}

class ViewerPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("viewer", Context.MODE_PRIVATE)

    var textSizeSp: Float
        get() = prefs.getFloat("textSize", 17f)
        set(v) = prefs.edit().putFloat("textSize", v).apply()

    var pdfNightMode: Boolean
        get() = prefs.getBoolean("pdfNight", false)
        set(v) = prefs.edit().putBoolean("pdfNight", v).apply()
}
