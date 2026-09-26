package com.mobai.jm.data

import android.content.Context
import android.util.Base64
import com.mobai.jm.util.AppPrefs
import com.mobai.jm.util.DiagLog
import com.mobai.jm.util.net.MasqueManager
import com.mobai.jm.util.net.NetTweaks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 禁漫姬 · JM 移动端 API 客户端（v0.1.1）
 *
 * 加密方案（与已验证的 PHP/JS 实现一致）：
 *  - token = md5(ts + "185Hcomic3PAPP7R")，tokenparam = "{ts},{version}"
 *  - 响应 data 字段 = base64(AES-256-ECB, key = md5(ts + "185Hcomic3PAPP7R"))
 *
 * 启动流程（全部异步，不阻塞界面）：
 *  1. 读取本地上次缓存的域名 / 图片域名
 *  2. 后台刷新域名服务器列表（慢，约 3 秒，绝不阻塞）
 *  3. 后台请求 /setting：拿官方图片域名 + 最新版本号
 */
object JmApi {

    private const val TOKEN_SECRET = "185Hcomic3PAPP7R"
    private const val DOMAIN_SECRET = "diosfjckwpqpdfjkvnqQjsik"

    private const val PREFS = "mobai_prefs"
    private const val KEY_DOMAINS = "jm_domains"
    private const val KEY_LAST_DOMAIN = "jm_last_domain"
    private const val KEY_IMAGE_HOST = "jm_image_host"
    private const val KEY_APP_VERSION = "jm_app_version"
    private const val KEY_HOST_ORDER = "jm_host_order"
    private const val KEY_HOST_ORDER_TS = "jm_host_order_ts"

    private const val DOMAIN_SERVER_URL =
        "https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt"

    /** 官方 /setting 下发的图片域名（探测当日值），启动后会被 /setting 动态更新 */
    private const val DEFAULT_IMAGE_HOST = "cdn-msp.jmdanjonproxy.xyz"

    /** 备用图片域名池（与已验证的 reader.html / index.php 相同的 CDN 域名，用于分流与重试） */
    private val EXTRA_IMAGE_HOSTS = listOf(
        "cdn-msp.jmapiproxy1.cc",
        "cdn-msp.jmapiproxy2.cc",
        "cdn-msp2.jmapiproxy2.cc",
        "cdn-msp3.jmapiproxy2.cc",
        "cdn-msp.jmapinodeudzn.net",
        "cdn-msp3.jmapinodeudzn.net",
    )

    private val FALLBACK_DOMAINS = listOf(
        "www.cdnhjk.net",
        "www.cdngwc.cc",
        "www.cdngwc.net",
        "www.cdngwc.club",
        "www.cdnutc.me",
    )

    private const val UA =
        "Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.114 Safari/537.36"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private class MemCookieJar : CookieJar {
        private val store = HashMap<String, List<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(store) { store[url.host] = cookies }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            synchronized(store) { store[url.host] } ?: emptyList()

        fun size(): Int = synchronized(store) { store.values.sumOf { it.size } }
    }

    private val cookieJar = MemCookieJar()

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            // API 默认 H1.1（CN 网络下 h2 偶发“假死连接”→请求无限等待）；可在设置里开启实验 H2
            .protocols(
                if (appContext?.let { AppPrefs(it).apiHttp2 } == true) {
                    listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
                } else {
                    listOf(Protocol.HTTP_1_1)
                }
            )
            .dispatcher(com.mobai.jm.util.net.NetDispatcher.dispatcher)
            .connectionPool(com.mobai.jm.util.net.NetDispatcher.pool)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
        appContext?.let { ctx -> NetTweaks.apply(builder, ctx) }
        return builder.build()
    }

    private fun buildDomainClient(): OkHttpClient = client.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var client: OkHttpClient = buildClient()

    @Volatile
    private var domainClient: OkHttpClient = buildDomainClient()

    /** 网络实验设置变化后调用：重建连接（新连接立即生效） */
    fun refreshNetwork(context: Context) {
        runCatching {
            val old = client
            client = buildClient()
            domainClient = buildDomainClient()
            runCatching { old.connectionPool.evictAll() }
            DiagLog.d("net 配置刷新: ${NetTweaks.describe(context)}")
        }.onFailure { DiagLog.w("net 配置刷新失败", it) }
    }

    private val bootstrapScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val domainFailures = ConcurrentHashMap<String, Int>()
    private val quarantineUntil = ConcurrentHashMap<String, Long>()

    private const val QUARANTINE_MS = 5 * 60 * 1000L

    /** 排序域名：健康的优先、失败少的优先；反复失败的域名暂时隔离（减少无效等待） */
    private fun orderedDomains(): List<String> {
        val now = System.currentTimeMillis()
        val healthy = domains.filter { (quarantineUntil[it] ?: 0L) <= now }
        val base = healthy.ifEmpty { domains }
        return if (domainFailures.isEmpty()) base else base.sortedBy { domainFailures[it] ?: 0 }
    }

    @Volatile
    private var domains: List<String> = FALLBACK_DOMAINS

    @Volatile
    private var imageHost: String = DEFAULT_IMAGE_HOST

    @Volatile
    private var appVersion: String = "2.0.26"

    @Volatile
    private var hostOrder: List<String> = emptyList()

    @Volatile
    private var domainRefreshed = false

    @Volatile
    private var settingFetched = false

    private var appContext: Context? = null

    // ───────────────────────── 初始化 ─────────────────────────

    /** 进程启动时调用：读缓存 + 后台刷新（绝不阻塞首屏） */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val sp = appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        sp.getString(KEY_DOMAINS, null)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { cached -> domains = withLastFirst(cached) }

        sp.getString(KEY_IMAGE_HOST, null)?.takeIf { it.isNotBlank() }?.let { imageHost = it }
        sp.getString(KEY_APP_VERSION, null)?.takeIf { it.isNotBlank() }?.let { appVersion = it }
        hostOrder = sp.getString(KEY_HOST_ORDER, null)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

        DiagLog.d("init: domains=${domains.joinToString(",")} img=$imageHost ver=$appVersion hosts=${hostOrder.size}")

        // 应用实验性网络配置（DoH / 切片 / SNI / ECH），重建连接
        refreshNetwork(appContext!!)

        bootstrapScope.launch {
            refreshDomains()
            fetchSetting()
            testHostsIfStale()
        }
    }

    private fun withLastFirst(list: List<String>): List<String> {
        val last = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString(KEY_LAST_DOMAIN, null)
        val merged = (list + FALLBACK_DOMAINS).distinct()
        return if (last != null && merged.contains(last)) {
            listOf(last) + merged.filter { it != last }
        } else merged
    }

    private suspend fun refreshDomains() = withContext(Dispatchers.IO) {
        if (domainRefreshed) return@withContext
        domainRefreshed = true
        runCatching {
            val t0 = System.currentTimeMillis()
            var text = httpGet(domainClient, DOMAIN_SERVER_URL)
            while (text.isNotEmpty() && text[0].code > 127) text = text.substring(1)
            val plain = aesEcbDecrypt(text.trim(), md5Hex(DOMAIN_SECRET))
            val servers = json.parseToJsonElement(plain).jsonObject["Server"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.filter { it.isNotBlank() }
            if (!servers.isNullOrEmpty()) {
                domains = withLastFirst(servers)
                appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    ?.edit()?.putString(KEY_DOMAINS, servers.joinToString(","))?.apply()
                DiagLog.d("domains 更新(${System.currentTimeMillis() - t0}ms): $servers")
            } else {
                DiagLog.w("domains 刷新返回为空")
            }
        }.onFailure { DiagLog.w("domains 刷新失败", it) }
    }

    /** /setting：官方图片域名 + 最新接口版本号 + 基础 cookie（供部分接口使用） */
    private suspend fun fetchSetting() = withContext(Dispatchers.IO) {
        if (settingFetched) return@withContext
        settingFetched = true
        runCatching {
            val t0 = System.currentTimeMillis()
            val obj = apiGet("/setting").jsonObject
            obj["img_host"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { raw ->
                val host = raw.removePrefix("https://").removePrefix("http://")
                    .trimEnd('/').substringBefore('/')
                if (host.isNotBlank() && host != imageHost) {
                    imageHost = host
                    appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        ?.edit()?.putString(KEY_IMAGE_HOST, host)?.apply()
                }
            }
            obj["jm3_version"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { v ->
                if (isNewerVersion(v, appVersion)) {
                    appVersion = v
                    appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        ?.edit()?.putString(KEY_APP_VERSION, v)?.apply()
                }
            }
            DiagLog.d(
                "setting ok(${System.currentTimeMillis() - t0}ms): " +
                    "img=$imageHost ver=$appVersion cookie=${cookieJar.size()}"
            )
        }.onFailure { DiagLog.w("setting 获取失败", it) }
    }

    private fun isNewerVersion(candidate: String, current: String): Boolean {
        val a = candidate.split('.').mapNotNull { it.toIntOrNull() }
        val b = current.split('.').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // ───────────────────────── 加解密 ─────────────────────────

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun aesEcbDecrypt(base64Text: String, keyHex: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyHex.toByteArray(Charsets.UTF_8), "AES"))
        return String(cipher.doFinal(Base64.decode(base64Text, Base64.DEFAULT)), Charsets.UTF_8)
    }

    // ───────────────────────── HTTP ─────────────────────────

    private fun httpGet(
        httpClient: OkHttpClient,
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> builder.header(k, v) }
        httpClient.newCall(builder.build()).execute().use { resp ->
            if (resp.code != 200) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw IllegalStateException("空响应")
        }
    }

    private fun apiGet(pathAndQuery: String): JsonElement {
        var lastError: Exception? = null
        val all = orderedDomains()
        // 每个失败域名要等满超时：WARP 隧道下限 2 个、直连限 3 个
        val maxTries = if (MasqueManager.isRunning) 2 else 3
        // 总预算：18 秒内必须出结果或报错（彻底杜绝无限转圈）
        val deadline = System.currentTimeMillis() + 18_000
        for (domain in all.take(maxTries)) {
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 800) {
                lastError = lastError ?: java.io.InterruptedIOException("请求总超时")
                break
            }
            try {
                // 单次尝试不超剩余预算（newBuilder 共享连接池/调度器）
                val attemptClient = if (remain < 14_000) {
                    client.newBuilder()
                        .callTimeout(remain, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .build()
                } else {
                    client
                }
                val ts = (System.currentTimeMillis() / 1000).toString()
                val token = md5Hex(ts + TOKEN_SECRET)
                val raw = httpGet(
                    attemptClient,
                    "https://$domain$pathAndQuery",
                    mapOf("token" to token, "tokenparam" to "$ts,$appVersion"),
                )
                val root = json.parseToJsonElement(raw).jsonObject
                val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
                if (code != 200) throw IllegalStateException("code=$code")
                val data = root["data"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalStateException("data 为空")
                val result = json.parseToJsonElement(aesEcbDecrypt(data, md5Hex(ts + TOKEN_SECRET)))
                rememberGoodDomain(domain)
                domainFailures.remove(domain)
                quarantineUntil.remove(domain)
                return result
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                markDomainFailure(domain)
                DiagLog.w("api 失败 [$domain$pathAndQuery]: ${e.message}")
            }
        }
        throw lastError ?: IllegalStateException("请求失败")
    }

    private fun rememberGoodDomain(domain: String) {
        if (domains.firstOrNull() != domain) {
            domains = listOf(domain) + domains.filter { it != domain }
        }
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_LAST_DOMAIN, domain)?.apply()
    }

    // ───────────────────────── 解析 ─────────────────────────

    private fun parseComic(o: JsonObject): Comic {
        val id = o["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val title = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val author = o["author"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val image = o["image"]?.jsonPrimitive?.contentOrNull
        val coverUrl = when {
            image.isNullOrBlank() -> "https://${hostForSeed("album/$id", 0)}/media/albums/${id}_3x4.jpg"
            image.startsWith("http") -> image
            else -> "https://${hostForSeed(image, 0)}$image"
        }
        val category = (o["category"] as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull
        val categorySub = (o["category_sub"] as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull
        return Comic(
            id = id,
            title = title,
            author = author,
            coverUrl = coverUrl,
            category = category,
            categorySub = categorySub,
        )
    }

    private fun parseComicArray(element: JsonElement): List<Comic> =
        (element as? JsonArray)?.mapNotNull { item -> (item as? JsonObject)?.let(::parseComic) }.orEmpty()

    // ───────────────────────── 接口 ─────────────────────────

    /** 随机推荐（服务端按周期轮换一个固定池，每次返回 30 条；由调用方本地洗牌展示） */
    suspend fun random(): List<Comic> = withContext(Dispatchers.IO) {
        parseComicArray(apiGet("/random_recommend?time=${System.currentTimeMillis()}"))
    }

    /** 最新上架（每页 80 条） */
    suspend fun latest(page: Int = 1): List<Comic> = withContext(Dispatchers.IO) {
        parseComicArray(apiGet("/latest?page=$page"))
    }

    /** 分类 / 排行榜：order = mr(最新) / mv(总榜) / mv_m(月榜) / mv_w(周榜) / mv_t(日榜) */
    suspend fun ranking(
        category: String,
        order: String,
        page: Int = 1,
    ): List<Comic> = withContext(Dispatchers.IO) {
        val c = URLEncoder.encode(category.ifBlank { "0" }, "UTF-8")
        val o = URLEncoder.encode(order, "UTF-8")
        val element = apiGet("/categories/filter?page=$page&c=$c&o=$o")
        ((element as? JsonObject)?.get("content"))?.let(::parseComicArray).orEmpty()
    }

    /** 分类列表 */
    suspend fun categories(): List<JmCategory> = withContext(Dispatchers.IO) {
        val element = apiGet("/categories")
        val arr = (element as? JsonObject)?.get("categories") as? JsonArray
            ?: return@withContext emptyList()
        arr.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            JmCategory(
                id = o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                slug = o["slug"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                total = o["total_albums"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }

    /** 本子详情（含章节目录） */
    suspend fun album(albumId: String): JmAlbum = withContext(Dispatchers.IO) {
        val o = apiGet("/album?id=$albumId").jsonObject
        var episodes = (o["series"] as? JsonArray)?.mapNotNull { item ->
            val e = item as? JsonObject ?: return@mapNotNull null
            Episode(
                id = e["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                title = e["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                sort = e["sort"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }.orEmpty()
        episodes = episodes.sortedBy { it.sort.toIntOrNull() ?: Int.MAX_VALUE }
        if (episodes.isEmpty()) {
            episodes = listOf(
                Episode(albumId, o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(), "1")
            )
        }
        JmAlbum(
            id = albumId,
            name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            author = (o["author"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            tags = (o["tags"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            likes = o["likes"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            views = o["total_views"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            episodes = episodes,
        )
    }

    /** 章节图片文件名列表 */
    suspend fun chapterImages(photoId: String): List<String> = withContext(Dispatchers.IO) {
        val o = apiGet("/chapter?id=$photoId").jsonObject
        (o["images"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    }

    private val scrambleCache = ConcurrentHashMap<String, Int>()

    /** 章节图片乱序解密参数（特殊密钥 18comicAPPContent；失败回退 220980，算法上与真实值等价） */
    suspend fun scrambleId(photoId: String): Int = withContext(Dispatchers.IO) {
        scrambleCache[photoId]?.let { return@withContext it }
        var value: Int? = null
        for (domain in orderedDomains()) {
            try {
                val ts = (System.currentTimeMillis() / 1000).toString()
                val token = md5Hex(ts + "18comicAPPContent")
                val body = httpGet(
                    client,
                    "https://$domain/chapter_view_template?id=$photoId&mode=vertical&page=0&app_img_shunt=1&express=off&v=$ts",
                    mapOf("token" to token, "tokenparam" to "$ts,$appVersion"),
                )
                val match = Regex("var\\s+scramble_id\\s*=\\s*(\\d+)").find(body)
                if (match != null) {
                    value = match.groupValues[1].toIntOrNull()
                    if (value != null) break
                } else {
                    DiagLog.w("scramble 未匹配 [$domain] len=${body.length}")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                markDomainFailure(domain)
                DiagLog.w("scramble 失败 [$domain]: ${e.message}")
            }
        }
        val result = value ?: 220980
        scrambleCache[photoId] = result
        DiagLog.d("scramble($photoId) = $result")
        result
    }

    /** 健康图片域名（按探测延迟升序；空 = 未探测） */
    @Volatile
    private var healthyImgHosts: List<String> = emptyList()
    @Volatile
    private var imgProbeAt = 0L
    private val imgProbeScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private fun allImgHosts(): List<String> =
        if (hostOrder.isNotEmpty()) (hostOrder.take(4) + listOf(imageHost)).distinct()
        else (listOf(imageHost) + EXTRA_IMAGE_HOSTS).distinct()

    /** 图片域名池：优先使用健康探测后的结果（按延迟升序） */
    private fun imageHostPool(): List<String> {
        val all = allImgHosts()
        val healthy = healthyImgHosts.filter { it in all }
        return if (healthy.isNotEmpty()) healthy else all
    }

    /** 轮换到下一个图片域名（Coil 失败重试用） */
    fun nextImageHost(current: String): String? {
        val pool = imageHostPool()
        if (pool.size <= 1) return null
        val idx = pool.indexOf(current)
        val next = pool[(if (idx < 0) 0 else idx + 1) % pool.size]
        return next.takeIf { it != current }
    }

    /** 记录图片域名失败：从健康池降级，并触发下一轮探测 */
    fun reportImgHostFailure(host: String) {
        if (healthyImgHosts.contains(host)) {
            healthyImgHosts = healthyImgHosts.filter { it != host }
        }
        imgProbeAt = 0L
    }

    /** 后台探测图片域名：按实测延迟排序健康池（30 分钟一次，失败即重探） */
    fun refreshImageHostsAsync() {
        val now = System.currentTimeMillis()
        if (now - imgProbeAt < 30 * 60_000L && healthyImgHosts.isNotEmpty()) return
        imgProbeAt = now
        imgProbeScope.launch {
            val probePath = "/media/albums/1475774_3x4.jpg"
            val results = allImgHosts().mapNotNull { h ->
                runCatching {
                    val t0 = System.currentTimeMillis()
                    val req = okhttp3.Request.Builder().url("https://$h$probePath").head().build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    }
                    h to (System.currentTimeMillis() - t0)
                }.getOrNull()
            }.sortedBy { it.second }
            if (results.isNotEmpty()) {
                healthyImgHosts = results.map { it.first }
                DiagLog.d("图片域名探测: ${results.joinToString { "${it.first}=${it.second}ms" }}")
            } else {
                DiagLog.w("图片域名探测：全部失败，沿用全集")
            }
        }
    }

    private fun hostForSeed(seed: String, retry: Int): String {
        val pool = imageHostPool()
        var index = (seed.hashCode() and Int.MAX_VALUE) % pool.size
        if (retry != 0) index = (index + retry) % pool.size
        return pool[index]
    }

    /** 章节图片完整地址（retry 递增可轮换图片域名） */
    fun imageUrl(photoId: String, filename: String, retry: Int = 0): String =
        "https://${hostForSeed("$photoId/$filename", retry)}/media/photos/$photoId/$filename"

    /** 本子封面（大图） */
    fun albumCoverUrl(albumId: String, retry: Int = 0): String =
        "https://${hostForSeed("album/$albumId", retry)}/media/albums/$albumId.jpg"

    /** 本子封面（小图 3:4，列表/预览用：体积约为大图 1/10） */
    fun albumThumbUrl(albumId: String, retry: Int = 0): String =
        "https://${hostForSeed("album/$albumId", retry)}/media/albums/${albumId}_3x4.jpg"

    /** 记录域名失败：连续 2 次则隔离 5 分钟，避免死等挂掉的域名 */
    private fun markDomainFailure(domain: String) {
        val fails = (domainFailures[domain] ?: 0) + 1
        domainFailures[domain] = fails
        if (fails >= 2) {
            quarantineUntil[domain] = System.currentTimeMillis() + QUARANTINE_MS
        }
    }

    /** 下载原始图片字节（整本下载用；失败由调用方换域名重试） */
    suspend fun fetchBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        httpGetBytes(url)
    }

    private fun httpGetBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(request).execute().use { resp ->
            if (resp.code != 200) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body?.bytes() ?: throw IllegalStateException("空响应")
        }
    }

    /** 启动时对图片域名测速排序（12 小时缓存一次），分流时优先用最快的 */
    private suspend fun testHostsIfStale() = withContext(Dispatchers.IO) {
        val sp = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return@withContext
        if (System.currentTimeMillis() - sp.getLong(KEY_HOST_ORDER_TS, 0L) < 12 * 3600_000L) return@withContext
        runCatching {
            val candidates = (EXTRA_IMAGE_HOSTS + DEFAULT_IMAGE_HOST + imageHost).distinct()
            val results = ConcurrentHashMap<String, Long>()
            coroutineScope {
                candidates.forEach { host ->
                    launch {
                        runCatching {
                            val t0 = System.currentTimeMillis()
                            val bytes = httpGetBytes("https://$host/media/albums/1447694_3x4.jpg")
                            if (bytes.isNotEmpty()) results[host] = System.currentTimeMillis() - t0
                        }
                    }
                }
            }
            if (results.isNotEmpty()) {
                val ordered = results.entries.sortedBy { it.value }.map { it.key }
                hostOrder = ordered
                sp.edit()
                    .putString(KEY_HOST_ORDER, ordered.joinToString(","))
                    .putLong(KEY_HOST_ORDER_TS, System.currentTimeMillis())
                    .apply()
                DiagLog.d("host 测速(${results.size}/${candidates.size} 可用): $ordered")
            } else {
                DiagLog.w("host 测速全部失败，保持原顺序")
                sp.edit().putLong(KEY_HOST_ORDER_TS, System.currentTimeMillis()).apply()
            }
        }
    }

    /** 关键词搜索（page 从 1 开始），返回 (总数, 结果) */
    suspend fun search(query: String, page: Int = 1): Pair<Int, List<Comic>> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val element = apiGet("/search?search_query=$q&page=$page&o=mr")
        val obj = element as? JsonObject ?: return@withContext 0 to emptyList()
        val total = obj["total"]?.jsonPrimitive?.intOrNull ?: 0
        val list = (obj["content"] as? JsonArray)
            ?.mapNotNull { item -> (item as? JsonObject)?.let(::parseComic) }.orEmpty()
        total to list
    }

    /** 调试信息（设置页展示用） */
    fun debugInfo(): String = buildString {
        append("域名(${domains.size}): ${domains.joinToString(", ")}\n")
        append("图片域名: $imageHost\n")
        append("接口版本: $appVersion\n")
        append("Cookie: ${cookieJar.size()} 条")
    }
}
