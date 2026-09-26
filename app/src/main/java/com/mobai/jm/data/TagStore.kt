package com.mobai.jm.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import com.mobai.jm.util.DiagLog
import com.mobai.jm.util.LangDetect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * 语言国旗 / 全彩 的「标签级」判定 + 静默补全。
 *
 * 列表接口没有 tags 字段，但 /album 详情里有（如 ["NTR","无修正","中文","全彩"]）。
 * 所以：列表滚动到时，后台按需抓取详情标签 → 用真实 tag 校准国旗与全彩，
 * 结果全局缓存（同时顺带预热了详情页，一举两得）。
 */
object TagStore {
    private val langFlags = mutableStateMapOf<String, String>()   // id -> emoji
    private val fullColor = mutableStateMapOf<String, Boolean>()  // id -> 全彩
    private val rawTags = mutableStateMapOf<String, List<String>>() // id -> 原始标签
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sem = Semaphore(2)

    /** 语言国旗：有 tag 判定用 tag，否则退回标题启发式 */
    fun flagFor(comic: Comic): String? =
        langFlags[comic.id] ?: LangDetect.emoji(comic.title, extras(comic))

    /** 全彩：有 tag 判定用 tag，否则退回标题启发式 */
    fun colorFor(comic: Comic): Boolean =
        fullColor[comic.id] ?: LangDetect.isFullColor(comic.title, extras(comic))

    /** 详情页加载完成时调用：直接登记标签判定 */
    fun remember(album: JmAlbum) {
        rawTags[album.id] = album.tags
        langFromTags(album.tags)?.let { langFlags[album.id] = it }
            ?: LangDetect.emoji(album.name, album.tags)?.let { langFlags[album.id] = it }
        fullColor[album.id] = album.tags.any {
            it.contains("全彩") || it.contains("フルカラー") || it.contains("彩漫")
        } || LangDetect.isFullColor(album.name, album.tags)
    }

    /** 后台补全：对列表前 limit 个条目静默抓详情，用 tag 校准国旗/全彩 */
    fun enrich(context: Context, comics: List<Comic>, limit: Int = 12) {
        val targets = comics.asSequence()
            .filter { it.id.isNotBlank() && !langFlags.containsKey(it.id) && pending.add(it.id) }
            .take(limit)
            .toList()
        if (targets.isEmpty()) return
        targets.forEach { c ->
            scope.launch {
                sem.withPermit {
                    try {
                        val album = AlbumCache.load(c.id)
                            ?: JmApi.album(c.id).also { AlbumCache.save(it) }
                        remember(album)
                    } catch (e: Exception) {
                        DiagLog.d("tag 补全失败 ${c.id}: ${e.message}")
                    } finally {
                        pending.remove(c.id)
                    }
                }
            }
        }
    }

    /** 原始标签（供屏蔽词匹配） */
    fun rawTagsOf(id: String): List<String>? = rawTags[id]

    private fun extras(comic: Comic): List<String> = listOfNotNull(
        comic.author.takeIf { it.isNotBlank() }, comic.category, comic.categorySub,
    )

    private fun langFromTags(tags: List<String>): String? {
        for (t in tags) {
            val s = t.lowercase()
            when {
                "中文" in s || "漢化" in s || "汉化" in s -> return "🇨🇳"
                "日本語" in s || "日文" in s || "japanese" in s -> return "🇯🇵"
                "english" in s || "英文" in s -> return "🇺🇸"
                "한국" in s || "한글" in s || "韓文" in s || "韩文" in s -> return "🇰🇷"
            }
        }
        return null
    }
}
