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

/** 首页屏蔽标签（关键词黑名单）：标题/作者/分类包含这些词的本子从首页隐藏 */
object BlockTagsStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var file: File? = null
    private val tags = mutableStateListOf<String>()

    val list: List<String> get() = tags

    fun init(context: Context) {
        if (file != null) return
        file = File(StorageUtil.metaDir(context), "blocked_tags.json")
        runCatching {
            file?.takeIf { it.exists() }?.readText()?.let { text ->
                json.decodeFromString<List<String>>(text).forEach { tags.add(it) }
            }
        }
    }

    fun add(tag: String) {
        val t = tag.trim()
        if (t.isEmpty() || tags.contains(t)) return
        tags.add(t)
        persist()
    }

    fun remove(tag: String) {
        tags.remove(tag)
        persist()
    }

    fun importAll(newTags: List<String>) {
        var changed = false
        newTags.forEach { raw ->
            val t = raw.trim()
            if (t.isNotEmpty() && !tags.contains(t)) {
                tags.add(t)
                changed = true
            }
        }
        if (changed) persist()
    }

    /** 判断本子是否命中屏蔽词（标题/作者/分类 任一包含） */
    fun isBlocked(comic: Comic): Boolean {
        if (tags.isEmpty()) return false
        val hay = buildString {
            append(comic.title).append(' ')
            append(comic.author).append(' ')
            comic.category?.let { append(it).append(' ') }
            comic.categorySub?.let { append(it) }
        }.lowercase()
        if (tags.any { it.lowercase() in hay }) return true
        // 作品真实标签（后台校准拿到的 tags）也参与匹配
        val realTags = TagStore.rawTagsOf(comic.id) ?: return false
        return tags.any { word ->
            val w = word.lowercase()
            realTags.any { w in it.lowercase() }
        }
    }

    private fun persist() {
        val f = file ?: return
        val snapshot = tags.toList()
        scope.launch {
            runCatching { f.writeText(json.encodeToString(snapshot)) }
        }
    }
}
