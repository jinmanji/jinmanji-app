package com.mobai.jm.util.net

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mobai.jm.data.JmApi
import com.mobai.jm.util.AppPrefs
import com.mobai.jm.util.DiagLog
import com.mobai.jm.util.StartupGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * MASQUE（Cloudflare WARP）实验客户端管理器。
 *
 * 架构：内置 usque 二进制（jniLibs/libusque.so，支持 QUIC 与 TCP/HTTP2 双transport）
 *  注册：Kotlin 走 CF API（经我们的 OkHttp，开启 DoH 时全程 DoH）→ 写入 usque config.json
 *  连接：按「自动换线梯」依次尝试：
 *      ① QUIC 主端点 → ② TCP+友好SNI 主端点 → ③ QUIC 备用 IP → ④ TCP+友好SNI 备用 IP
 *      （上次成功的线路会优先重试；可在设置中固定 仅 QUIC / 仅 TCP）
 *  成功后 `libusque.so socks` 起本地 SOCKS5，经 [MasqueProxySelector] 接管全 App 流量。
 */
object MasqueManager {

    const val SOCKS_PORT = 18080

    private val ALT_ENDPOINTS = listOf("162.159.192.1", "188.114.97.1", "162.159.195.1", "162.159.193.1")

    /** 默认 SNI（隧道对 SNI 不敏感，任意域名可用） */
    private const val DEFAULT_SNI = "speed.cloudflare.com"

    enum class MasqueState(val label: String) {
        NO_CONFIG("未配置"),
        STOPPED("未连接"),
        STARTING("连接中…"),
        RUNNING("已连接"),
        ERROR("异常"),
    }

    var state by mutableStateOf(MasqueState.NO_CONFIG)
        private set
    var lastMessage by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set

    /** ProxySelector 在非 UI 线程读取的快速标志 */
    @Volatile
    var isRunning: Boolean = false
        private set

    private var process: Process? = null
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json { ignoreUnknownKeys = true }

    // ───────────── 生命周期 ─────────────

    fun init(context: Context) {
        appContext = context.applicationContext
        refreshIdleState()
    }

    private fun dir(): File = File(appContext!!.filesDir, "usque").apply { mkdirs() }

    fun configFile(): File = File(dir(), "config.json")

    fun logFile(): File = File(dir(), "usque.log")

    private fun refreshIdleState() {
        state = if (configFile().exists()) MasqueState.STOPPED else MasqueState.NO_CONFIG
        if (state == MasqueState.STOPPED) lastMessage = configSummary() ?: ""
    }

    fun configSummary(): String? = runCatching {
        val obj = json.parseToJsonElement(configFile().readText()).jsonObject
        val id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val v4 = obj["ipv4"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val ep = obj["endpoint_v4"]?.jsonPrimitive?.contentOrNull.orEmpty()
        "id=${id.take(8)}… | IPv4=$v4 | 端点=$ep"
    }.getOrNull()

    fun importConfig(text: String): String {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw IllegalArgumentException("不是有效的 JSON")
        for (k in listOf("private_key", "endpoint_v4", "id", "ipv4")) {
            if (obj[k]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                throw IllegalArgumentException("缺少字段：$k（不是有效的 MASQUE/usque 配置）")
            }
        }
        configFile().writeText(normalizeConfig(text))
        refreshIdleState()
        return configSummary() ?: "已导入"
    }

    fun exportText(): String? =
        runCatching { configFile().takeIf { it.exists() }?.readText() }.getOrNull()

    fun tailLog(lines: Int = 12): String =
        runCatching {
            val f = logFile()
            if (!f.exists()) return ""
            f.readLines().takeLast(lines).joinToString("\n")
        }.getOrDefault("")

    /** 规整连接信息：修正 endpoint 字段的括号/端口残留（兼容历史坏配置） */
    private fun normalizeConfig(text: String): String = runCatching {
        val obj = json.parseToJsonElement(text).jsonObject
        val m = obj.toMutableMap()
        listOf("endpoint_v4", "endpoint_v6", "endpoint_h2_v4", "endpoint_h2_v6").forEach { k ->
            val v = obj[k]?.jsonPrimitive?.contentOrNull ?: return@forEach
            m[k] = JsonPrimitive(parseHostClean(v))
        }
        JsonObject(m).toString()
    }.getOrDefault(text)

    private fun parseHostClean(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end > 1) s = s.substring(1, end)
        } else if (s.count { it == ':' } == 1) {
            s = s.substringBefore(':')
        }
        return s.removePrefix("[").removeSuffix("]")
    }

    // ───────────── 注册 ─────────────

    suspend fun register(context: Context, deviceName: String): String = withContext(Dispatchers.IO) {
        busy = true
        try {
            val r = WarpRegistrar.register(context, deviceName)
            configFile().writeText(r.configJson)
            state = MasqueState.STOPPED
            lastMessage = r.summary
            DiagLog.d("warp: 配置已保存（${r.summary}）")
            r.summary
        } catch (e: Exception) {
            state = MasqueState.ERROR
            lastMessage = e.message ?: "注册失败"
            DiagLog.w("warp: 注册失败", e)
            throw e
        } finally {
            busy = false
        }
    }

    // ───────────── 自动换线梯 ─────────────

    private data class Attempt(
        val key: String,
        val label: String,
        val tcp: Boolean,
        val config: File,
        val sni: String?,
    )

    /** 准备本次启动的候选线路（主端点/备用 IP × QUIC/TCP） */
    private fun buildAttempts(context: Context): List<Attempt> {
        val src = configFile()
        val srcRaw = runCatching { src.readText() }.getOrDefault("")
        val srcText = normalizeConfig(srcRaw)
        if (srcText != srcRaw && srcText.isNotBlank()) {
            runCatching { src.writeText(srcText) }
            DiagLog.d("warp: 连接信息已自动修正（括号/端口残留）")
        }
        val altIp = ALT_ENDPOINTS.random()
        // 随机 SNI：从用户配置列表抽取（空列表 = 默认 speed.cloudflare.com）
        val sniPool = AppPrefs(context).masqueSnis.ifEmpty { listOf(DEFAULT_SNI) }
        fun pickSni() = sniPool.random()
        val mainCfg = File(dir(), "attempt_main.json").apply { runCatching { writeText(srcText) } }
        val altCfg = File(dir(), "attempt_alt.json").apply {
            runCatching {
                val obj = json.parseToJsonElement(srcText).jsonObject
                val swapped = JsonObject(
                    obj.toMutableMap().apply {
                        put("endpoint_v4", JsonPrimitive(altIp))
                        put("endpoint_v6", JsonPrimitive(""))
                    }
                )
                writeText(swapped.toString())
            }
        }
        val all = listOf(
            Attempt("quic_main", "QUIC", false, mainCfg, pickSni()),
            Attempt("tcp_main", "TCP 回退", true, mainCfg, pickSni()),
            Attempt("quic_alt", "QUIC 备用线路", false, altCfg, pickSni()),
            Attempt("tcp_alt", "TCP 备用线路", true, altCfg, pickSni()),
        )
        val mode = AppPrefs(context).masqueTransferMode
        val lastGood = AppPrefs(context).masqueLastGood
        return when (mode) {
            "quic" -> all.filter { !it.tcp }
            "tcp" -> all.filter { it.tcp }
            else -> {
                // QUIC 优先：QUIC 全部试完才轮到 TCP；同通道内上次成功线路优先
                val quic = all.filter { !it.tcp }.sortedByDescending { it.key == lastGood }
                val tcp = all.filter { it.tcp }.sortedByDescending { it.key == lastGood }
                quic + tcp
            }
        }
    }

    suspend fun start(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext true
        appContext = context.applicationContext
        val binary = File(context.applicationInfo.nativeLibraryDir, "libusque.so")
        if (!binary.exists()) {
            state = MasqueState.ERROR
            lastMessage = "内置 MASQUE 内核缺失（libusque.so）"
            return@withContext false
        }
        if (!configFile().exists()) {
            state = MasqueState.NO_CONFIG
            lastMessage = "尚未注册或导入配置"
            return@withContext false
        }
        state = MasqueState.STARTING
        lastMessage = "准备连接…"
        val attempts = buildAttempts(context)
        for ((i, a) in attempts.withIndex()) {
            lastMessage = "连接中…（${i + 1}/${attempts.size} · ${a.label}）"
            DiagLog.d("warp: 尝试 ${a.key}")
            if (runAttempt(context, binary, a)) {
                isRunning = true
                state = MasqueState.RUNNING
                lastMessage = "线路：${a.label}"
                AppPrefs(context).masqueLastGood = a.key
                DiagLog.d("warp: 连接成功 via ${a.key}")
                return@withContext true
            }
        }
        isRunning = false
        state = MasqueState.ERROR
        lastMessage = "全部线路暂不可用（网络可能正封锁 WARP，稍后再试）"
        DiagLog.w("warp: 全部线路失败")
        false
    }

    /** 启动一次尝试：起进程 → 等端口 → 预热请求验证 */
    private fun runAttempt(context: Context, binary: File, a: Attempt): Boolean {
        runCatching {
            process?.destroy()
            process?.waitFor(1, TimeUnit.SECONDS)
        }
        process = null
        return try {
            logFile().delete()
            val args = mutableListOf(
                binary.absolutePath,
                "-c", a.config.absolutePath,
                "socks",
                "-b", "127.0.0.1",
                "-p", "$SOCKS_PORT",
                "-d", "1.1.1.1",
                "-d", "1.0.0.1",
                "-d", "8.8.8.8",
                "--dns-timeout", "10s",
            )
            if (a.tcp) args += "--http2"
            a.sni?.takeIf { it.isNotBlank() }?.let {
                args += "-s"
                args += it
            }
            val pb = ProcessBuilder(args)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile()))
            val proc = pb.start()
            process = proc

            // 等待端口就绪（最多 12 秒）
            val deadline = System.currentTimeMillis() + 12_000
            var up = false
            while (System.currentTimeMillis() < deadline) {
                if (!proc.isAlive) break
                up = runCatching {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", SOCKS_PORT), 400) }
                    true
                }.getOrDefault(false)
                if (up) break
                Thread.sleep(400)
            }
            if (!up) {
                runCatching { proc.destroy() }
                return false
            }

            // 预热校验（能真正过隧道的请求才算成功；单次失败先重试一次再降级）
            var warmed = runCatching { warmUp() }.getOrDefault(false)
            if (!warmed) {
                Thread.sleep(1500)
                warmed = runCatching { warmUp() }.getOrDefault(false)
            }
            if (!warmed) {
                runCatching { proc.destroy() }
                return false
            }
            true
        } catch (e: Exception) {
            DiagLog.w("warp: 尝试异常", e)
            runCatching { process?.destroy() }
            false
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        val p = process
        process = null
        isRunning = false
        if (p != null) {
            runCatching {
                p.destroy()
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
            }
        }
        state = if (configFile().exists()) MasqueState.STOPPED else MasqueState.NO_CONFIG
        lastMessage = if (state == MasqueState.STOPPED) configSummary() ?: "" else ""
        DiagLog.d("warp: 已断开")
    }

    /** 预热隧道：走一次轻量请求，让 DNS/连接就绪 */
    private fun warmUp(): Boolean {
        return try {
            val client = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", SOCKS_PORT)))
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder().url("https://www.cdnhjk.net/setting").build()
            client.newCall(req).execute().use { }
            // 预热图片主机（隧道内 DNS 偶发超时，先把 DNS/握手热好，避免首个封面卡 10s+）
            runCatching {
                client.newCall(
                    Request.Builder()
                        .url(com.mobai.jm.data.JmApi.albumThumbUrl("1475774"))
                        .head()
                        .build()
                ).execute().use { }
            }
            true
        } catch (e: Exception) {
            DiagLog.w("warp: 预热请求失败（${e.message}）")
            false
        }
    }

    /** App 启动时按偏好自动连接；失败则门控报错（停止资源加载） */
    fun autoStartIfNeeded(context: Context) {
        val ctx = context.applicationContext
        if (appContext == null) appContext = ctx
        if (!AppPrefs(ctx).masqueAutoStart || isRunning || !configFile().exists()) {
            StartupGate.ready()
            return
        }
        StartupGate.connecting("正在连接隧道…")
        scope.launch {
            val ok = runCatching { start(ctx) }.getOrDefault(false)
            if (ok) {
                JmApi.refreshNetwork(ctx)
                StartupGate.ready()
            } else {
                DiagLog.w("warp: 自动连接失败：$lastMessage")
                StartupGate.failed(lastMessage.ifBlank { "隧道连接失败" })
            }
        }
    }

    /** 启动门控「重试」：重新走换线梯 */
    suspend fun retryStartup(context: Context): Boolean {
        StartupGate.connecting("正在连接隧道…")
        val ok = runCatching { start(context.applicationContext) }.getOrDefault(false)
        if (ok) {
            JmApi.refreshNetwork(context)
            StartupGate.ready()
        } else {
            StartupGate.failed(lastMessage.ifBlank { "隧道连接失败" })
        }
        return ok
    }

    /** 通道测试：强制经 SOCKS5 访问 Cloudflare trace，返回结果文本 */
    suspend fun test(context: Context): String = withContext(Dispatchers.IO) {
        if (!isRunning) return@withContext "❌ 未连接（先开启连接开关）"
        try {
            val client = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", SOCKS_PORT)))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url("https://cloudflare.com/cdn-cgi/trace")
                .header("User-Agent", "mobai/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val warp = text.lines().firstOrNull { it.startsWith("warp=") } ?: "warp=?"
                val colo = text.lines().firstOrNull { it.startsWith("colo=") } ?: ""
                "✅ 通道正常：$warp ${colo}".trim()
            }
        } catch (e: Exception) {
            "❌ 通道测试失败：${e.message}"
        }
    }
}

/** 动态代理选择器：MASQUE 运行时所有流量走本地 SOCKS5，否则维持系统默认 */
class MasqueProxySelector(private val appContext: android.content.Context? = null) : ProxySelector() {
    private val delegate = ProxySelector.getDefault()

    override fun select(uri: URI): MutableList<Proxy> {
        // 统一出口：按「隧道模式」选择 WARP(18080) 或 Tor 插件上报端口；未运行则直连
        val port = if (appContext != null) {
            TunnelRouter.activeSocksPort(appContext)
        } else {
            if (MasqueManager.isRunning) MasqueManager.SOCKS_PORT else null
        }
        if (port != null) {
            return mutableListOf(
                Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            )
        }
        return delegate?.select(uri) ?: mutableListOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) {
        delegate?.connectFailed(uri, sa, ioe)
    }
}
