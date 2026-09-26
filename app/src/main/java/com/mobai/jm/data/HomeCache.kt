package com.mobai.jm.data

import android.content.Context
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
data class HomeData(
    val random: List<Comic> = emptyList(),
    val ranking: List<Comic> = emptyList(),
    val latest: List<Comic> = emptyList(),
)

/** 首页预加载缓存：下次启动优先读它秒开，然后后台用新内容替换 */
object HomeCache {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        file = File(StorageUtil.metaDir(context), "home.json")
    }

    fun load(): HomeData? = runCatching {
        val f = file?.takeIf { it.exists() } ?: return null
        json.decodeFromString<HomeData>(f.readText())
    }.getOrNull()

    fun save(random: List<Comic>, ranking: List<Comic>, latest: List<Comic>) {
        val f = file ?: return
        val data = HomeData(random = random, ranking = ranking, latest = latest)
        scope.launch {
            runCatching { f.writeText(json.encodeToString(data)) }
        }
    }
}
