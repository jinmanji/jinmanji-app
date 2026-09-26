package com.mobai.jm.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.mobai.jm.util.DiagLog
import com.mobai.jm.util.StorageUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 首页数据（发现页） */
object HomeStore {
    var loading by mutableStateOf(false)
        private set
    var randomLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var random by mutableStateOf<List<Comic>>(emptyList())
        private set
    var ranking by mutableStateOf<List<Comic>>(emptyList())
        private set
    var latest by mutableStateOf<List<Comic>>(emptyList())
        private set

    private var loaded = false

    suspend fun loadIfNeeded() {
        if (!loaded) load()
    }

    suspend fun load() {
        if (loading) return
        loading = true
        error = null
        val t0 = System.currentTimeMillis()
        // 缓存读取也放进 try：任何异常都必须落回 loading=false（否则重试会永久失灵）
        try {
            val hadData = random.isNotEmpty() || ranking.isNotEmpty() || latest.isNotEmpty()
            if (!hadData) {
                // 1) 上一次的预加载缓存：先秒开
                withContext(Dispatchers.IO) { HomeCache.load() }?.let { cached ->
                    if (random.isEmpty()) random = cached.random
                    if (ranking.isEmpty()) ranking = cached.ranking
                    if (latest.isEmpty()) latest = cached.latest
                    DiagLog.d("home 来自预加载缓存: ${cached.random.size}/${cached.ranking.size}/${cached.latest.size}")
                }
            }
            // 2) 后台网络刷新（成功后替换缓存；失败则保留旧缓存不动）
            coroutineScope {
                launch { random = JmApi.random().shuffled() }
                launch { ranking = JmApi.ranking(category = "0", order = "mv") }
                launch { latest = JmApi.latest() }
            }
            loaded = true
            HomeCache.save(random, ranking, latest)
            DiagLog.d(
                "home ok(${System.currentTimeMillis() - t0}ms): " +
                    "random=${random.size} rank=${ranking.size} latest=${latest.size}"
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (random.isEmpty() && ranking.isEmpty() && latest.isEmpty()) {
                error = e.message ?: "加载失败，请检查网络"
            }
            DiagLog.w("home 加载失败（有缓存则保留）", e)
        } finally {
            loading = false
        }
    }

    var latestPage by mutableStateOf(1)
        private set
    var latestLoading by mutableStateOf(false)
        private set

    /** 最新上架：翻到指定页 */
    suspend fun loadLatestPage(p: Int) {
        if (latestLoading) return
        latestLoading = true
        error = null
        try {
            val fresh = JmApi.latest(p)
            latest = fresh
            latestPage = p
            HomeCache.save(random, ranking, latest)
            DiagLog.d("latest page $p ok: ${fresh.size} 条")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagLog.w("latest page $p 失败", e)
        } finally {
            latestLoading = false
        }
    }

    /** 换一批：立即本地洗牌给出反馈；仅当服务端池真正变化时才二次刷新（避免二次跳动） */
    suspend fun refreshRandom() {
        if (randomLoading) return
        randomLoading = true
        error = null
        try {
            // 单段刷新：只在网络结果返回后更新一次（消除“本地先跳一次 + 网络再跳一次”的双刷新）
            val fresh = JmApi.random()
            if (fresh.isNotEmpty()) random = fresh.shuffled()
            DiagLog.d("random refreshed: pool=${fresh.size}")
        } catch (e: Exception) {
            error = "换一批失败，请检查网络"
            DiagLog.w("random 刷新失败", e)
        } finally {
            randomLoading = false
        }
    }

    fun clearError() {
        error = null
    }
}

/** 分类列表数据 */
object CategoryStore {
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var categories by mutableStateOf<List<JmCategory>>(emptyList())
        private set

    /** 当前打开的分类（全局，切页/返回后保留） */
    var selected by mutableStateOf<JmCategory?>(null)

    private var loaded = false

    suspend fun loadIfNeeded() {
        if (!loaded) load()
    }

    suspend fun load() {
        loading = true
        error = null
        val t0 = System.currentTimeMillis()
        try {
            categories = JmApi.categories()
            loaded = true
            DiagLog.d("categories ok(${System.currentTimeMillis() - t0}ms): ${categories.size} 项")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "加载失败，请检查网络"
            DiagLog.w("categories 加载失败", e)
        } finally {
            loading = false
        }
    }
}

/** 分类页自定义分类/tag（≤ 16 字符，基于搜索 API 使用） */
object CustomCatStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var file: File? = null
    private val items = mutableStateListOf<String>()

    val list: List<String> get() = items

    fun init(context: Context) {
        if (file != null) return
        file = File(StorageUtil.metaDir(context), "custom_cats.json")
        runCatching {
            file?.takeIf { it.exists() }?.readText()?.let { text ->
                json.decodeFromString<List<String>>(text).forEach { items.add(it) }
            }
        }
    }

    /** 添加（≤ 16 个字符，按 Unicode 码点计数）；返回错误信息或 null */
    fun add(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return "不能为空"
        val cp = t.codePointCount(0, t.length)
        if (cp > 16) return "最多 16 个字符（当前 $cp）"
        if (items.contains(t)) return "已存在"
        items.add(t)
        persist()
        return null
    }

    fun remove(t: String) {
        items.remove(t)
        persist()
    }

    private fun persist() {
        val f = file ?: return
        val snapshot = items.toList()
        scope.launch {
            runCatching { f.writeText(json.encodeToString(snapshot)) }
        }
    }
}
