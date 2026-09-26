package com.mobai.jm.data

import android.content.Context
import com.mobai.jm.util.StorageUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 章节图片列表缓存（阅读秒开 + 预加载用） */
object PagesCache {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var dir: File? = null

    fun init(context: Context) {
        if (dir != null) return
        dir = File(StorageUtil.metaDir(context), "pages").also { it.mkdirs() }
    }

    private fun fileOf(photoId: String): File? = dir?.let { File(it, "$photoId.json") }

    fun load(photoId: String): List<String>? = runCatching {
        val f = fileOf(photoId)?.takeIf { it.exists() } ?: return null
        json.decodeFromString<List<String>>(f.readText())
    }.getOrNull()

    fun save(photoId: String, pages: List<String>) {
        val f = fileOf(photoId) ?: return
        scope.launch {
            runCatching { f.writeText(json.encodeToString(pages)) }
        }
    }
}
