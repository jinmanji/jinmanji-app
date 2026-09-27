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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.max

/**
 * 搜索页全局状态：切换标签页/返回后保留结果与滚动位置，不重复搜索。
 * 筛选：语言多选（标题关键词识别）+ 自定义筛选词（≤16 字符）+ 全彩 + 时间范围。
 * 排序：mr 最新 / mv 最多观看 / mp 最多图片 / tf 最多爱心（可与筛选并存）。
 * 「筛选补足」：筛选较严时自动连翻 API 页，尽量把每页填满，不出现连续空页。
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

    // ── 排序 / 时间 ──
    /** 排序：mr 最新 / mv 最多观看 / mp 最多图片 / tf 最多爱心 */
    var sortOrder by mutableStateOf("mr")
        private set
    /** 时间预设：a 全部 / t 今日 / w 本周 / m 本月 */
    var timeRange by mutableStateOf("a")
        private set
    /** 指定年份（空 = 不限） */
    var filterYear by mutableStateOf("")
        private set
    /** 指定月份（空 = 全年）；仅在 filterYear 非空时生效 */
    var filterMonth by mutableStateOf("")
        private set

    /** 筛选补足：已扫描的 API 页数（展示用） */
    var scanPages by mutableStateOf(0)
        private set
    /** 筛选补足：当前已筛出的总条数 */
    var filteredTotal by mutableStateOf(0)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var searchJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }
    private var sp: android.content.SharedPreferences? = null
    private var appContext: Context? = null
    private var pageSize = 45

    // 筛选补足扫描状态
    private var rawScanned: List<Comic> = emptyList()
    private var nextApiPage = 1
    private var scanExhausted = false
    private val MAX_SCAN_PER_ACTION = 8

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
            sortOrder = p.getString("search_sort", "mr") ?: "mr"
            timeRange = p.getString("search_time", "a") ?: "a"
            filterYear = p.getString("search_year", "") ?: ""
            filterMonth = p.getString("search_month", "") ?: ""
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
                .putString("search_sort", sortOrder)
                .putString("search_time", timeRange)
                .putString("search_year", filterYear)
                .putString("search_month", filterMonth)
                .apply()
        }
    }

    /** 新搜索（重置到第 1 页）；线程安全的后台执行，切页不中断 */
    fun search(q: String) {
        val v = q.trim()
        if (v.isEmpty()) return
        query = v
        resetScan()
        launchSearch(v, 1)
    }

    /** 跳到指定页（筛选补足模式下为虚拟页：必要时自动继续扫描 API 页） */
    fun goToPage(p: Int) {
        if (query.isBlank()) return
        launchSearch(query, max(p, 1))
    }

    private fun launchSearch(q: String, p: Int) {
        searchJob?.cancel()
        searchJob = scope.launch { runSearch(q, p) }
    }

    private fun resetScan() {
        rawScanned = emptyList()
        nextApiPage = 1
        scanExhausted = false
        scanPages = 0
        filteredTotal = 0
    }

    /** 条件变更后：若已搜索过，从第 1 页重新跑 */
    private fun restartIfSearched() {
        resetScan()
        if (searched && query.isNotBlank()) launchSearch(query, 1)
    }

    private suspend fun runSearch(q: String, viewPage: Int) {
        loading = true
        error = null
        try {
            if (!filterActive) {
                // 常规模式：直接取 API 一页
                val (t, list) = withTimeout(40_000) {
                    JmApi.search(q, viewPage, sortOrder, timeRange, filterYear, filterMonth)
                }
                total = t
                results = list
                filteredTotal = list.size
                appContext?.let { ctx ->
                    TagStore.enrich(ctx, list, if (com.mobai.jm.data.BlockTagsStore.list.isNotEmpty()) 40 else 12)
                    ImagePrefetch.prefetch(
                        ctx,
                        list.take(12).map { it.coverUrl.ifBlank { JmApi.albumThumbUrl(it.id) } },
                    )
                }
                page = viewPage
                searched = true
                if (viewPage == 1 && list.isNotEmpty()) pageSize = max(list.size, 1)
                runCatching { gridState.scrollToItem(0) }
            } else {
                // 筛选补足：从第 1 页起连续扫描，直到本虚拟页装满（或扫到没有更多）
                if (viewPage <= 1) resetScan()
                var acted = 0
                while (applyFilter(rawScanned).size < viewPage * pageSize &&
                    !scanExhausted && acted < MAX_SCAN_PER_ACTION
                ) {
                    val (t, list) = withTimeout(40_000) {
                        JmApi.search(q, nextApiPage, sortOrder, timeRange, filterYear, filterMonth)
                    }
                    total = t
                    if (list.isEmpty()) {
                        scanExhausted = true
                    } else {
                        if (nextApiPage == 1) pageSize = max(list.size, 1)
                        rawScanned = rawScanned + list
                        nextApiPage += 1
                    }
                    acted += 1
                }
                val all = applyFilter(rawScanned)
                filteredTotal = all.size
                scanPages = max(nextApiPage - 1, 0)
                results = all.drop((viewPage - 1) * pageSize).take(pageSize)
                page = viewPage
                searched = true
                appContext?.let { ctx ->
                    TagStore.enrich(ctx, results, if (com.mobai.jm.data.BlockTagsStore.list.isNotEmpty()) 40 else 12)
                    ImagePrefetch.prefetch(
                        ctx,
                        results.take(12).map { it.coverUrl.ifBlank { JmApi.albumThumbUrl(it.id) } },
                    )
                }
                if (viewPage == 1) runCatching { gridState.scrollToItem(0) }
            }
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
        if (filterActive) return max(1, (filteredTotal + pageSize - 1) / pageSize)
        if (total <= 0) return 1
        return max(1, (total + pageSize - 1) / pageSize)
    }

    /** 「还有下一页」：筛选补足模式下只要还没扫完就允许继续 */
    fun hasNextPage(): Boolean =
        if (!filterActive) page < pageCount()
        else (filteredTotal > page * pageSize) || !scanExhausted

    /** 当前排序的中文名 */
    fun sortLabel(): String = when (sortOrder) {
        "mv" -> "最多观看"
        "mp" -> "最多图片"
        "tf" -> "最多爱心"
        else -> "最新"
    }

    // ── 排序 / 时间操作 ──

    fun applySortOrder(code: String) {
        if (sortOrder == code) return
        sortOrder = code
        persist()
        restartIfSearched()
    }

    fun applyTimeRange(code: String) {
        if (timeRange == code) return
        timeRange = code
        if (code != "a") {
            // 预设时间与指定年月互斥，避免参数冲突
            filterYear = ""
            filterMonth = ""
        }
        persist()
        restartIfSearched()
    }

    fun setCustomDate(year: String, month: String) {
        filterYear = year.trim()
        filterMonth = if (filterYear.isBlank()) "" else month.trim()
        if (filterYear.isNotBlank()) timeRange = "a"
        persist()
        restartIfSearched()
    }

    // ── 筛选操作 ──

    fun toggleLang(code: String) {
        selectedLangs = if (selectedLangs.contains(code)) selectedLangs - code else selectedLangs + code
        persist()
        restartIfSearched()
    }

    fun toggleTerm(t: String) {
        selectedTerms = if (selectedTerms.contains(t)) selectedTerms - t else selectedTerms + t
        persist()
        restartIfSearched()
    }

    fun toggleFullColor() {
        fullColorOnly = !fullColorOnly
        persist()
        restartIfSearched()
    }

    fun clearFilter() {
        selectedLangs = emptySet()
        selectedTerms = emptySet()
        fullColorOnly = false
        timeRange = "a"
        filterYear = ""
        filterMonth = ""
        persist()
        restartIfSearched()
    }

    val filterActive: Boolean
        get() = selectedLangs.isNotEmpty() || selectedTerms.isNotEmpty() || fullColorOnly ||
            timeRange != "a" || filterYear.isNotBlank()

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
        restartIfSearched()
    }

    /** 应用筛选（多选语言=或；自定义词=或；两组之间与） */
    fun applyFilter(list: List<Comic>): List<Comic> {
        val clientFilter = selectedLangs.isNotEmpty() || selectedTerms.isNotEmpty() || fullColorOnly
        if (!clientFilter) return list
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
