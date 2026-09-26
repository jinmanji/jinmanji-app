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

/** 本子详情（含章节目录）的持久化缓存：打开过一次后，下次秒开、断网也能看目录 */
object AlbumCache {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private var dir: File? = null

    fun init(context: Context) {
        if (dir != null) return
        dir = StorageUtil.metaDir(context).also { it.mkdirs() }
    }

    private fun fileOf(id: String): File? = dir?.let { File(it, "$id.json") }

    fun load(id: String): JmAlbum? = runCatching {
        val f = fileOf(id)?.takeIf { it.exists() } ?: return null
        json.decodeFromString<JmAlbum>(f.readText())
    }.getOrNull()

    fun save(album: JmAlbum) {
        val f = fileOf(album.id) ?: return
        scope.launch {
            runCatching { f.writeText(json.encodeToString(album)) }
        }
    }
}
