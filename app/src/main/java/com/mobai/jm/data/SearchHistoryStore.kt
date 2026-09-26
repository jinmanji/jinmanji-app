package com.mobai.jm.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.mobai.jm.util.StorageUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 搜索历史（最多 50 条，最近的在最前） */
object SearchHistoryStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var file: File? = null
    private val items = mutableStateListOf<String>()

    val list: List<String> get() = items

    fun init(context: Context) {
        if (file != null) return
        file = File(StorageUtil.metaDir(context), "search_history.json")
        runCatching {
            file?.takeIf { it.exists() }?.readText()?.let { text ->
                json.decodeFromString<List<String>>(text).forEach { items.add(it) }
            }
        }
    }

    fun record(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        items.remove(q)
        items.add(0, q)
        while (items.size > 50) items.removeAt(items.size - 1)
        persist()
    }

    fun removeAll(queries: Collection<String>) {
        items.removeAll { it in queries }
        persist()
    }

    fun clear() {
        items.clear()
        persist()
    }

    /** 导入合并（去重） */
    fun importAll(newQueries: List<String>) {
        var changed = false
        newQueries.reversed().forEach { q ->
            val v = q.trim()
            if (v.isNotEmpty() && !items.contains(v)) {
                items.add(0, v)
                changed = true
            }
        }
        while (items.size > 50) items.removeAt(items.size - 1)
        if (changed) persist()
    }

    private fun persist() {
        val f = file ?: return
        val snapshot = items.toList()
        scope.launch {
            runCatching { f.writeText(json.encodeToString(snapshot)) }
        }
    }
}
