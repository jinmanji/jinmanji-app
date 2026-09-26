package com.mobai.jm.ui.search

import android.content.Context
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mobai.jm.data.Comic
import com.mobai.jm.data.JmApi
import com.mobai.jm.data.TagStore
import com.mobai.jm.util.ImagePrefetch
import com.mobai.jm.util.LangDetect
import com.mobai.jm.util.net.MasqueManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.max

/**
 * 搜索页全局状态：切换标签页/返回后保留结果与滚动位置，不重复搜索。
 * 筛选：语言多选（标题关键词识别）+ 自定义筛选词（≤16 字符）+ 全彩。
 */
object SearchStore {

    var query by mutableStateOf("")
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var results by mutableStateOf<List<Comic>>(emptyList())
        private set
    var total by mutableStateOf(0)
        private set
    var page by mutableStateOf(1)
        private set
    var searched by mutableStateOf(false)
        private set

    /** 滚动位置随结果保留 */
    val gridState = LazyGridState()

    // ── 筛选状态 ──
    /** 勾选的语言 code；"unknown" 表示"未识别" */
    var selectedLangs by mutableStateOf<Set<String>>(emptySet())
    /** 用户自定义筛选词（持久化） */
    var customTerms by mutableStateOf<List<String>>(emptyList())
    /** 勾选的自定义词 */
    var selectedTerms by mutableStateOf<Set<String>>(emptySet())
    var fullColorOnly by mutableStateOf(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { ignoreUnknownKeys = true }
    private var sp: android.content.SharedPreferences? = null
    private var appContext: Context? = null
    private var pageSize = 45

    fun init(context: Context) {
        if (sp != null) return
        appContext = context.applicationContext
        val p = context.applicationContext.getSharedPreferences("mobai_prefs", Context.MODE_PRIVATE)
        sp = p
        runCatching {
            customTerms = json.decodeFromString<List<String>>(p.getString("search_terms", "[]") ?: "[]")
            selectedLangs = json.decodeFromString<List<String>>(p.getString("search_langs", "[]") ?: "[]").toSet()
            selectedTerms = json.decodeFromString<List<String>>(p.getString("search_terms_sel", "[]") ?: "[]").toSet()
            fullColorOnly = p.getBoolean("search_fc", false)
        }
    }

    private fun persist() {
        val p = sp ?: return
        runCatching {
            p.edit()
                .putString("search_terms", json.encodeToString(customTerms))
                .putString("search_langs", json.encodeToString(selectedLangs.toList()))
                .putString("search_terms_sel", json.encodeToString(selectedTerms.toList()))
                .putBoolean("search_fc", fullColorOnly)
                .apply()
        }
    }

    /** 新搜索（重置到第 1 页）；线程安全的后台执行，切页不中断 */
    fun search(q: String) {
        val v = q.trim()
        if (v.isEmpty()) return
        query = v
        scope.launch { runSearch(v, 1) }
    }

    /** 跳到指定页（重新搜索该页） */
    fun goToPage(p: Int) {
        if (query.isBlank()) return
        scope.launch { runSearch(query, max(p, 1)) }
    }

    private suspend fun runSearch(q: String, p: Int) {
        loading = true
        error = null
        try {
            // 硬性上限：无论域名重试如何，40 秒内必须有结果或报错
            val (t, list) = withTimeout(40_000) { JmApi.search(q, p) }
            total = t
            results = list
            appContext?.let { ctx ->
                TagStore.enrich(ctx, list, if (com.mobai.jm.data.BlockTagsStore.list.isNotEmpty()) 40 else 12)
                ImagePrefetch.prefetch(
                    ctx,
                    list.take(12).map { it.coverUrl.ifBlank { JmApi.albumThumbUrl(it.id) } },
                )
            }
            page = p
            searched = true
            if (p == 1 && list.isNotEmpty()) pageSize = max(list.size, 1)
            runCatching { gridState.scrollToItem(0) }
        } catch (e: TimeoutCancellationException) {
            error = "搜索超时（网络或隧道繁忙），请重试"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = if (MasqueManager.isRunning) {
                "${e.message ?: "搜索失败"} · WARP 隧道繁忙，请重试"
            } else {
                e.message ?: "搜索失败"
            }
        } finally {
            loading = false
        }
    }

    fun pageCount(): Int {
        if (total <= 0) return 1
        return max(1, (total + pageSize - 1) / pageSize)
    }

    // ── 筛选操作 ──

    fun toggleLang(code: String) {
        selectedLangs = if (selectedLangs.contains(code)) selectedLangs - code else selectedLangs + code
        persist()
    }

    fun toggleTerm(t: String) {
        selectedTerms = if (selectedTerms.contains(t)) selectedTerms - t else selectedTerms + t
        persist()
    }

    fun toggleFullColor() {
        fullColorOnly = !fullColorOnly
        persist()
    }

    fun clearFilter() {
        selectedLangs = emptySet()
        selectedTerms = emptySet()
        fullColorOnly = false
        persist()
    }

    val filterActive: Boolean
        get() = selectedLangs.isNotEmpty() || selectedTerms.isNotEmpty() || fullColorOnly

    /** 添加自定义筛选词（≤16 字符，按 Unicode 码点计数）；返回错误信息或 null */
    fun addTerm(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return "不能为空"
        val cp = t.codePointCount(0, t.length)
        if (cp > 16) return "最多 16 个字符（当前 $cp）"
        if (customTerms.contains(t)) return "已存在"
        customTerms = customTerms + t
        persist()
        return null
    }

    fun removeTerm(t: String) {
        customTerms = customTerms - t
        selectedTerms = selectedTerms - t
        persist()
    }

    /** 应用筛选（多选语言=或；自定义词=或；两组之间与） */
    fun applyFilter(list: List<Comic>): List<Comic> {
        if (!filterActive) return list
        return list.filter { c ->
            val extras = listOfNotNull(
                c.author.takeIf { it.isNotBlank() }, c.category, c.categorySub,
            )
            val hay = (c.title + " " + extras.joinToString(" ")).lowercase()
            val langOk = if (selectedLangs.isEmpty()) {
                true
            } else {
                val guess = LangDetect.guess(c.title, extras)
                when {
                    guess == null -> selectedLangs.contains("unknown")
                    selectedLangs.contains(guess.code) -> true
                    else -> false
                }
            }
            val termOk = if (selectedTerms.isEmpty()) {
                true
            } else {
                selectedTerms.any { it.lowercase() in hay }
            }
            val colorOk = !fullColorOnly || TagStore.colorFor(c)
            langOk && termOk && colorOk
        }
    }

    /** 内置语言选项（不含 emoji 展示） */
    val builtinLangs: List<Pair<String, String>> = listOf(
        "zh" to "中文",
        "ja" to "日本語",
        "en" to "English",
        "ko" to "한국어",
        "unknown" to "未识别",
    )
}
