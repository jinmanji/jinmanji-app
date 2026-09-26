package com.mobai.jm.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.mobai.jm.util.AppPrefs
import com.mobai.jm.util.StorageUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class HistoryItem(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val viewedAt: Long,
)

/** 浏览历史（最多 300 条，最近的在最前；可在设置中关闭记录） */
object HistoryStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var file: File? = null
    private var appContext: Context? = null
    private val items = mutableStateListOf<HistoryItem>()

    val list: List<HistoryItem> get() = items

    fun init(context: Context) {
        if (file != null) return
        appContext = context.applicationContext
        file = File(StorageUtil.metaDir(context), "history.json")
        runCatching {
            file?.takeIf { it.exists() }?.readText()?.let { text ->
                json.decodeFromString<List<HistoryItem>>(text).forEach { items.add(it) }
            }
        }
    }

    fun record(album: JmAlbum, coverUrl: String) {
        val ctx = appContext ?: return
        if (!AppPrefs(ctx).historyEnabled) return
        val item = HistoryItem(
            id = album.id,
            title = album.name,
            author = album.author.joinToString(", "),
            coverUrl = coverUrl,
            viewedAt = System.currentTimeMillis(),
        )
        items.removeAll { it.id == album.id }
        items.add(0, item)
        while (items.size > 300) items.removeAt(items.size - 1)
        persist()
    }

    fun removeAll(ids: Collection<String>) {
        items.removeAll { it.id in ids }
        persist()
    }

    fun clear() {
        items.clear()
        persist()
    }

    /** 导入合并（同 id 保留较新浏览时间）：返回新增数量 */
    fun importItems(newItems: List<HistoryItem>): Int {
        var added = 0
        newItems.forEach { item ->
            val idx = items.indexOfFirst { it.id == item.id }
            if (idx < 0) {
                items.add(item)
                added++
            } else if (item.viewedAt > items[idx].viewedAt) {
                items[idx] = item
            }
        }
        if (added > 0) {
            val sorted = items.sortedByDescending { it.viewedAt }
            items.clear()
            items.addAll(sorted)
            persist()
        }
        return added
    }

    private fun persist() {
        val f = file ?: return
        val snapshot = items.toList()
        scope.launch {
            runCatching { f.writeText(json.encodeToString(snapshot)) }
        }
    }
}
