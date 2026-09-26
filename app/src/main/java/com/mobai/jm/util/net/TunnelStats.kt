package com.mobai.jm.util.net

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mobai.jm.util.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * WARP 隧道实时统计：
 *  - 流量：OkHttp 拦截器按实际读出的字节累计（仅在隧道运行期间计数）
 *  - 速度：每秒取样差分
 *  - 延迟：每 4 秒经隧道发一次轻量 HEAD 请求测往返
 * 供页眉右上角 HUD 实时刷新显示。
 */
object TunnelStats {
    /** 累计总流量（持久化） */
    var totalBytes by mutableStateOf(0L)
        private set

    /** 本次会话流量 */
    var sessionBytes by mutableStateOf(0L)
        private set

    /** 当前速度（字节/秒） */
    var speedBps by mutableStateOf(0L)
        private set

    /** 隧道延迟（毫秒；-1 = 未知） */
    var latencyMs by mutableStateOf(-1)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sp: android.content.SharedPreferences? = null
    private var lastSample = 0L
    private var prevRunning = false
    private var persistTick = 0

    /** App 是否在前台（前台高频探测，后台慢速保活） */
    @Volatile
    var appVisible = true

    private var nextPingAt = 0L

    fun init(context: Context) {
        if (sp != null) return
        sp = context.applicationContext.getSharedPreferences("mobai_prefs", Context.MODE_PRIVATE)
        totalBytes = sp?.getLong("tunnel_total", 0L) ?: 0L
        scope.launch { sampleLoop() }
    }

    /** 拦截器读到字节时调用（经隧道） */
    fun addBytes(n: Long) {
        if (n <= 0) return
        totalBytes += n
        sessionBytes += n
    }

    fun resetTotal() {
        totalBytes = 0
        sessionBytes = 0
        lastSample = 0
        sp?.edit()?.putLong("tunnel_total", 0L)?.apply()
    }

    private suspend fun sampleLoop() {
        while (true) {
            delay(1000)
            val running = MasqueManager.isRunning
            if (running && !prevRunning) {
                // 连接建立：重置速度基线
                lastSample = sessionBytes
                latencyMs = -1
            }
            if (!running) {
                prevRunning = false
                speedBps = 0
                latencyMs = -1
                nextPingAt = 0L
                continue
            }
            prevRunning = true

            val delta = (sessionBytes - lastSample).coerceAtLeast(0)
            lastSample = sessionBytes
            speedBps = delta

            persistTick++
            if (persistTick % 10 == 0) {
                sp?.edit()?.putLong("tunnel_total", totalBytes)?.apply()
            }
            // 抖动心跳：前台 6~12s（高负载 15~30s）、后台 45~75s 慢保活；
            // 间隔随机化，避免固定节拍成为可识别的流量特征
            val now = System.currentTimeMillis()
            if (nextPingAt == 0L) nextPingAt = now + pingJitter()
            if (now >= nextPingAt) {
                nextPingAt = now + pingJitter()
                latencyMs = pingTunnel()
            }
        }
    }

    /** 心跳间隔（随机抖动；后台更慢，避免固定节拍特征） */
    private fun pingJitter(): Long = when {
        !appVisible -> 45_000L + kotlin.random.Random.nextLong(30_000L)
        speedBps > 200_000L -> 15_000L + kotlin.random.Random.nextLong(15_000L)
        else -> 6_000L + kotlin.random.Random.nextLong(6_000L)
    }

    /** 持久探测客户端：连接复用，测到的是真实请求 RTT（不再每次重握手虚高） */
    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", MasqueManager.SOCKS_PORT)))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private fun pingTunnel(): Int {
        return try {
            val client = probeClient
            val t0 = System.currentTimeMillis()
            val req = Request.Builder()
                .url("https://www.cdnhjk.net/setting?ping=${System.currentTimeMillis()}")
                .head()
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(req).execute().use { }
            (System.currentTimeMillis() - t0).toInt()
        } catch (e: Exception) {
            -1
        }
    }
}

/** OkHttp 拦截器：经过隧道的响应体按实际读取字节计数 */
class TunnelCountingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!MasqueManager.isRunning) return response
        chain.request().body?.contentLength()?.takeIf { it > 0 }?.let { TunnelStats.addBytes(it) }
        val body = response.body ?: return response
        return response.newBuilder().body(CountingBody(body)).build()
    }
}

private class CountingBody(private val delegate: ResponseBody) : ResponseBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()

    override fun source(): BufferedSource {
        val src = delegate.source()
        return object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val n = src.read(sink, byteCount)
                if (n > 0) TunnelStats.addBytes(n)
                return n
            }

            override fun timeout(): Timeout = src.timeout()
            override fun close() = src.close()
        }.buffer()
    }
}

/** 速度格式化：B/s、KB/s、MB/s */
fun fmtSpeed(bps: Long): String = when {
    bps >= 1024L * 1024 -> "%.1fMB/s".format(bps / 1048576.0)
    bps >= 1024 -> "%.0fKB/s".format(bps / 1024.0)
    else -> "${bps}B/s"
}

/** 流量格式化：B、KB、MB、GB */
fun fmtBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2fGB".format(bytes / 1073741824.0)
    bytes >= 1024L * 1024 -> "%.1fMB".format(bytes / 1048576.0)
    bytes >= 1024 -> "%.0fKB".format(bytes / 1024.0)
    else -> "${bytes}B"
}
