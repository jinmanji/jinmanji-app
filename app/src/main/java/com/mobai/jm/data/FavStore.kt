package com.mobai.jm.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
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
data class FavItem(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val addedAt: Long,
)

/** 本地收藏（JSON 文件持久化，不依赖网络） */
object FavStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private var file: File? = null
    private val items = mutableStateListOf<FavItem>()
    private val tags = mutableStateListOf<String>()
    private var tagFile: File? = null

    val list: List<FavItem> get() = items
    val favTags: List<String> get() = tags

    fun init(context: Context) {
        if (file != null) return
        file = StorageUtil.favoritesFile(context)
        tagFile = File(StorageUtil.favoritesFile(context).parentFile, "fav_tags.json")
        runCatching {
            file?.takeIf { it.exists() }?.readText()?.let { text ->
                json.decodeFromString<List<FavItem>>(text).forEach { items.add(it) }
            }
        }
        runCatching {
            tagFile?.takeIf { it.exists() }?.readText()?.let { text ->
                json.decodeFromString<List<String>>(text).forEach { tags.add(it) }
            }
        }
    }

    fun contains(id: String): Boolean = items.any { it.id == id }

    fun add(album: JmAlbum, coverUrl: String) {
        if (contains(album.id)) return
        items.add(
            0,
            FavItem(
                id = album.id,
                title = album.name,
                author = album.author.joinToString(", "),
                coverUrl = coverUrl,
                addedAt = System.currentTimeMillis(),
            )
        )
        persist()
    }

    fun remove(id: String) {
        items.removeAll { it.id == id }
        persist()
    }

    fun toggle(album: JmAlbum, coverUrl: String): Boolean {
        return if (contains(album.id)) {
            remove(album.id)
            false
        } else {
            add(album, coverUrl)
            true
        }
    }

    fun addTag(tag: String) {
        val t = tag.trim()
        if (t.isEmpty() || tags.contains(t)) return
        tags.add(t)
        persistTags()
    }

    fun removeTag(tag: String) {
        tags.remove(tag)
        persistTags()
    }

    fun toggleTag(tag: String): Boolean {
        val t = tag.trim()
        if (t.isEmpty()) return false
        return if (tags.contains(t)) {
            removeTag(t)
            false
        } else {
            addTag(t)
            true
        }
    }

    private fun persistTags() {
        val f = tagFile ?: return
        val snapshot = tags.toList()
        scope.launch {
            runCatching { f.writeText(json.encodeToString(snapshot)) }
        }
    }

    /** 导入合并：返回新增数量 */
    fun importItems(newItems: List<FavItem>): Int {
        var added = 0
        newItems.forEach { item ->
            if (items.none { it.id == item.id }) {
                items.add(item)
                added++
            }
        }
        if (added > 0) persist()
        return added
    }

    fun importTags(newTags: List<String>) {
        var changed = false
        newTags.forEach { raw ->
            val t = raw.trim()
            if (t.isNotEmpty() && !tags.contains(t)) {
                tags.add(t)
                changed = true
            }
        }
        if (changed) persistTags()
    }

    private fun persist() {
        val f = file ?: return
        val snapshot = items.toList()
        scope.launch {
            runCatching { f.writeText(json.encodeToString(snapshot)) }
        }
    }
}
