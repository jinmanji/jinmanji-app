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
data class BrowserRecord(
    val url: String,
    val title: String,
    val time: Long,
    val extra: String = "",
)

@Serializable
data class UserScript(
    val name: String,
    val matches: String,
    val code: String,
    val enabled: Boolean = true,
)

@Serializable
private data class BrowserData(
    val history: List<BrowserRecord> = emptyList(),
    val bookmarks: List<BrowserRecord> = emptyList(),
    val downloads: List<BrowserRecord> = emptyList(),
    val scripts: List<UserScript> = emptyList(),
)

/** 内置浏览器数据：历史 / 收藏 / 下载 / 用户脚本（全部持久化） */
object BrowserStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var file: File? = null

    val history = mutableStateListOf<BrowserRecord>()
    val bookmarks = mutableStateListOf<BrowserRecord>()
    val downloads = mutableStateListOf<BrowserRecord>()
    val scripts = mutableStateListOf<UserScript>()

    fun init(context: Context) {
        if (file != null) return
        file = File(StorageUtil.metaDir(context), "browser.json")
        runCatching {
            file?.takeIf { it.exists() }?.readText()?.let { text ->
                val d = json.decodeFromString<BrowserData>(text)
                history.addAll(d.history)
                bookmarks.addAll(d.bookmarks)
                downloads.addAll(d.downloads)
                scripts.addAll(d.scripts)
            }
        }
    }

    fun addHistory(url: String, title: String) {
        if (url.isBlank() || url.startsWith("about:")) return
        history.removeAll { it.url == url }
        history.add(0, BrowserRecord(url, title, System.currentTimeMillis()))
        while (history.size > 500) history.removeAt(history.size - 1)
        persist()
    }

    fun removeHistory(url: String) {
        history.removeAll { it.url == url }
        persist()
    }

    fun clearHistory() {
        history.clear()
        persist()
    }

    fun isBookmarked(url: String): Boolean = bookmarks.any { it.url == url }

    fun toggleBookmark(url: String, title: String): Boolean {
        return if (isBookmarked(url)) {
            bookmarks.removeAll { it.url == url }
            persist()
            false
        } else {
            bookmarks.add(0, BrowserRecord(url, title, System.currentTimeMillis()))
            persist()
            true
        }
    }

    fun removeBookmark(url: String) {
        bookmarks.removeAll { it.url == url }
        persist()
    }

    fun addDownload(record: BrowserRecord) {
        downloads.add(0, record)
        persist()
    }

    fun removeDownload(record: BrowserRecord) {
        downloads.remove(record)
        persist()
    }

    fun addScript(script: UserScript): String? {
        val name = script.name.trim()
        val code = script.code.trim()
        if (name.isEmpty()) return "请填写脚本名称"
        if (code.isEmpty()) return "脚本内容为空"
        scripts.removeAll { it.name == name }
        scripts.add(UserScript(name, script.matches.trim().ifBlank { "*" }, code))
        persist()
        return null
    }

    fun removeScript(name: String) {
        scripts.removeAll { it.name == name }
        persist()
    }

    fun toggleScript(name: String) {
        val i = scripts.indexOfFirst { it.name == name }
        if (i >= 0) {
            scripts[i] = scripts[i].copy(enabled = !scripts[i].enabled)
            persist()
        }
    }

    /** 匹配某 URL 的脚本（简单通配：* 任意；空=全部） */
    fun scriptsFor(url: String): List<UserScript> = scripts.filter { s ->
        if (!s.enabled) return@filter false
        val m = s.matches.trim()
        if (m.isEmpty() || m == "*") return@filter true
        m.split(",").map { it.trim() }.any { pattern -> matchPattern(url, pattern) }
    }

    private fun matchPattern(url: String, pattern: String): Boolean {
        if (pattern == "*") return true
        val regex = buildString {
            append('^')
            for (ch in pattern) {
                when (ch) {
                    '*' -> append(".*")
                    '.', '?', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|', '\\' ->
                        append('\\').append(ch)
                    else -> append(ch)
                }
            }
            append('$')
        }
        return runCatching { Regex(regex, RegexOption.IGNORE_CASE).containsMatchIn(url) }
            .getOrDefault(false)
    }

    private fun persist() {
        val f = file ?: return
        val snapshot = BrowserData(
            history = history.toList(),
            bookmarks = bookmarks.toList(),
            downloads = downloads.toList(),
            scripts = scripts.toList(),
        )
        scope.launch {
            runCatching { f.writeText(json.encodeToString(snapshot)) }
        }
    }
}
