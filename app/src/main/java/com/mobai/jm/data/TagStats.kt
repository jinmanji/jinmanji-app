package com.mobai.jm.data

import android.content.Context
import androidx.compose.runtime.mutableStateOf
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
private data class TagStatsData(
    val counts: Map<String, Int> = emptyMap(),
    val readIds: List<String> = emptyList(),
    val favIds: List<String> = emptyList(),
    val dlIds: List<String> = emptyList(),
)

/** 标签统计：阅读 / 收藏 / 下载的本子标签词频（词云数据源） */
object TagStats {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var file: File? = null
    private var data = TagStatsData()
    private val revision = mutableStateOf(0)

    fun init(context: Context) {
        if (file != null) return
        file = File(StorageUtil.metaDir(context), "tag_stats.json")
        runCatching {
            file?.takeIf { it.exists() }?.readText()?.let {
                data = json.decodeFromString<TagStatsData>(it)
            }
        }
    }

    fun onRead(album: JmAlbum) = mark(album, 0)
    fun onFavorite(album: JmAlbum) = mark(album, 1)
    fun onDownload(album: JmAlbum) = mark(album, 2)

    private fun mark(album: JmAlbum, kind: Int) {
        val id = album.id
        val already = when (kind) {
            0 -> data.readIds.contains(id)
            1 -> data.favIds.contains(id)
            else -> data.dlIds.contains(id)
        }
        if (already) return
        val newCounts = data.counts.toMutableMap()
        album.tags.forEach { tag ->
            if (tag.isNotBlank()) newCounts[tag] = (newCounts[tag] ?: 0) + 1
        }
        data = when (kind) {
            0 -> data.copy(counts = newCounts, readIds = data.readIds + id)
            1 -> data.copy(counts = newCounts, favIds = data.favIds + id)
            else -> data.copy(counts = newCounts, dlIds = data.dlIds + id)
        }
        revision.value++
        persist()
    }

    /** 返回 (tag, 次数) 按次数降序 */
    fun topTags(limit: Int): List<Pair<String, Int>> {
        revision.value
        return data.counts.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
    }

    private fun persist() {
        val f = file ?: return
        val snapshot = data
        scope.launch {
            runCatching { f.writeText(json.encodeToString(snapshot)) }
        }
    }
}
