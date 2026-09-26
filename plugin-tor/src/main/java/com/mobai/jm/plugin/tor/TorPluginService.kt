package com.mobai.jm.plugin.tor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import java.io.File
import java.util.concurrent.Executors

/**
 * Tor 隧道插件服务（手工 Binder 协议，不依赖 AIDL 工具链）：
 *  - 资产三件套（tor / lyrebird / snowflake-client）解包到私有目录并启动
 *  - SOCKS5 开在 127.0.0.1:18081，供主体（同签名）跨进程控制
 *  - 引导进度解析（Bootstrapped x%）、前台通知、日志落盘
 */
class TorPluginService : Service() {

    companion object {
        const val SOCKS_PORT = 18081
        const val DESCRIPTOR = "com.mobai.jm.plugin.ITunnelPlugin"

        const val TX_VERSION = 1
        const val TX_TUNNEL_ID = 2
        const val TX_NAME = 3
        const val TX_START = 4
        const val TX_STOP = 5
        const val TX_STATUS = 6

        private const val CHANNEL_ID = "mobai_tor_plugin"
        private const val NOTIF_ID = 2001
        private const val ACTION = "com.mobai.jm.TUNNEL_PLUGIN"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var process: Process? = null
    private var readerThread: Thread? = null

    // 状态委托到 TorState（Provider / Activity 跨组件读取）
    private var state: String
        get() = TorState.state
        set(v) { TorState.state = v }
    private var bootstrap: Int
        get() = TorState.bootstrap
        set(v) { TorState.bootstrap = v }
    private var message: String
        get() = TorState.message
        set(v) { TorState.message = v }
    private var torVersion: String
        get() = TorState.torVersion
        set(v) { TorState.torVersion = v }
    @Volatile private var lastWarn = ""
    private val logTail = ArrayDeque<String>()
    private var fgStarted = false
    @Volatile private var lastNotifyAt = 0L

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return try {
                data.enforceInterface(DESCRIPTOR)
                when (code) {
                    TX_VERSION -> {
                        reply?.writeNoException()
                        reply?.writeInt(1)
                    }
                    TX_TUNNEL_ID -> {
                        reply?.writeNoException()
                        reply?.writeString("tor")
                    }
                    TX_NAME -> {
                        reply?.writeNoException()
                        reply?.writeString("Tor 隧道")
                    }
                    TX_START -> {
                        val bridges = data.readString() ?: ""
                        executor.execute { doStart(bridges) }
                        reply?.writeNoException()
                        reply?.writeBundle(statusInternal())
                    }
                    TX_STOP -> {
                        executor.execute { doStop() }
                        reply?.writeNoException()
                    }
                    TX_STATUS -> {
                        reply?.writeNoException()
                        reply?.writeBundle(statusInternal())
                    }
                    else -> return false
                }
                true
            } catch (t: Throwable) {
                if (reply != null) runCatching {
                    reply.writeException(t as? Exception ?: RuntimeException(t))
                }
                true
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        PluginLog.init(filesDir)
        PluginLog.d("svc: onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 注意：桥行允许为空（直连 Tor）——用 hasExtra 判断“是否请求启动”，而不是内容非空
        val hasBridges = intent?.hasExtra("bridges") == true
        val isStartAction = intent?.action == "com.mobai.jm.TUNNEL_PLUGIN"
        PluginLog.d(
            "svc: onStartCommand action=${intent?.action} hasBridges=$hasBridges " +
                "bridgesLen=${intent?.getStringExtra("bridges")?.length ?: -1}"
        )
        startForegroundCompat(buildNotification(statusText()))
        fgStarted = true
        if (hasBridges || isStartAction) {
            val prefs = getSharedPreferences("tor", MODE_PRIVATE)
            val b = if (hasBridges) {
                (intent?.getStringExtra("bridges") ?: "").also {
                    prefs.edit().putString("bridges", it).apply()
                }
            } else {
                prefs.getString("bridges", "") ?: ""
            }
            executor.execute { doStart(b) }
        }
        if (intent?.action == "com.mobai.jm.plugin.tor.STOP") {
            executor.execute { doStop() }
        }
        return START_NOT_STICKY
    }

    private fun statusText(): String = when (state) {
        "running" -> "已连接"
        "starting" -> "引导中 $bootstrap%"
        "preparing" -> "准备中…"
        "error" -> "错误：$message"
        "stopped" -> "已停止"
        else -> "空闲"
    }

    override fun onDestroy() {
        doStop()
        super.onDestroy()
    }

    // ───────────── 核心逻辑 ─────────────

    private fun statusInternal(): Bundle = Bundle().apply {
        putString("state", state)
        putInt("bootstrap", bootstrap)
        putInt("socksPort", SOCKS_PORT)
        putString("message", message)
        putString("torVersion", torVersion)
    }

    @Synchronized
    private fun doStart(bridgesRaw: String) {
        if (state == "starting" || state == "preparing" || state == "running") {
            PluginLog.d("svc: doStart 跳过（state=$state）")
            return
        }
        try {
            PluginLog.d("svc: doStart bridges=${bridgesRaw.length}")
            val dir = File(filesDir, "native").apply { mkdirs() }
            state = "preparing"
            message = "解包组件…"
            notifyNow(message)

            PluginLog.d("svc: 解析内置二进制（nativeLibraryDir）")
            val torBin = nativeBin("libtor.so")
            val lyrebird = nativeBin("liblyrebird.so")
            val snowflake = nativeBin("libsnowflake.so")
            PluginLog.d("svc: 二进制就绪 tor=${torBin?.length() ?: -1} lyrebird=${lyrebird?.length() ?: -1} snowflake=${snowflake?.length() ?: -1}")
            if (torBin == null) {
                state = "error"
                message = "tor 组件缺失"
                notifyNow(message)
                return
            }

            val useWarp = getSharedPreferences("tor", MODE_PRIVATE).getBoolean("use_warp", false)
            val torrc = File(filesDir, "torrc")
            torrc.writeText(buildTorrc(bridgesRaw, dir, lyrebird, snowflake, useWarp))
            PluginLog.d("svc: torrc 写入完成 useWarp=$useWarp")

            state = "starting"
            bootstrap = 0
            message = "启动中…"
            notifyNow(message)

            val proc = ProcessBuilder(torBin.absolutePath, "-f", torrc.absolutePath)
                .redirectErrorStream(true)
                .start()
            process = proc
            PluginLog.d("svc: tor 进程已启动")
            TorControl.start(File(filesDir, "tor-data")) { pct, summary, circOk ->
                runCatching {
                    if (pct >= 100 || circOk) {
                        if (state != "running") {
                            state = "running"
                            bootstrap = 100
                            message = "已连接"
                            notifyNow(message)
                        }
                    } else if (state != "running") {
                        bootstrap = pct
                        if (state != "starting") state = "starting"
                        message = "引导中 $pct%"
                    }
                }
            }

            readerThread = Thread {
                runCatching {
                    val logFile = File(filesDir, "tor.log")
                    val re = Regex("Bootstrapped (\\d+)%")
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(logTail) {
                            logTail.addLast(line)
                            while (logTail.size > 40) logTail.removeFirst()
                        }
                        runCatching { logFile.appendText(line + "\n") }
                        if (line.contains("Tor version")) {
                            torVersion = line.substringAfter("Tor version").trim().substringBefore(" (")
                        }
                        // 全量记录（除心跳）：卡点时的 warn/err 一目了然
                        if (!line.contains("heartbeat")) PluginLog.d("tor: $line")
                        val m = re.find(line)
                        if (m != null) {
                            val pct = m.groupValues[1].toIntOrNull() ?: bootstrap
                            if (pct >= 100) {
                                if (state != "running") {
                                    state = "running"
                                    bootstrap = 100
                                    message = "已连接"
                                    notifyNow(message)
                                }
                            } else {
                                bootstrap = pct
                                if (state != "starting") state = "starting"
                                message = "引导中 $pct%"
                                notifyThrottled()
                            }
                        }
                        if (line.contains("[err]") || line.contains("[warn]")) lastWarn = line
                    }
                }
                PluginLog.d("svc: tor 进程退出 code=${runCatching { proc.exitValue() }.getOrDefault(-1)}")
                if (state != "stopped" && state != "error") {
                    state = "error"
                    message = lastWarn.takeIf { it.isNotBlank() } ?: "Tor 进程退出"
                    notifyNow(message)
                }
            }.also { it.isDaemon = true; it.start() }

        } catch (t: Throwable) {
            PluginLog.d("svc: doStart 异常 ${t.javaClass.simpleName}: ${t.message}")
            state = "error"
            message = t.message ?: "启动失败"
            notifyNow(message)
        }
    }

    @Synchronized
    private fun doStop() {
        PluginLog.d("svc: doStop")
        runCatching { TorControl.stop() }
        runCatching { process?.destroy() }
        process = null
        if (state != "idle") {
            state = "stopped"
            message = "已停止"
            runCatching { notifyNow(message) }
        }
    }

    private fun buildTorrc(
        bridgesRaw: String,
        dir: File,
        lyrebird: File?,
        snowflake: File?,
        useWarp: Boolean,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("SocksPort 127.0.0.1:$SOCKS_PORT")
        sb.appendLine("DataDirectory ${File(filesDir, "tor-data").absolutePath}")
        sb.appendLine("ClientOnly 1")
        sb.appendLine("AvoidDiskWrites 1")
        sb.appendLine("Log info stdout")
        // 经 WARP 出网：tor 的所有出站连接走主体的 WARP SOCKS（需先在主体开启 WARP）
        if (useWarp) sb.appendLine("Socks5Proxy 127.0.0.1:18080")
        // 桥多为双栈：强制 IPv4 偏好（v6 国际路由差，容易卡死连接）
        sb.appendLine("ClientPreferIPv6ORPort 0")
        // 控制端口（仅本机）：供插件读取权威引导进度
        sb.appendLine("ControlPort 127.0.0.1:18082")

        val lines = bridgesRaw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        if (lines.isNotEmpty()) {
            sb.appendLine("UseBridges 1")
            var needObfs = false
            var needWt = false
            var needSnow = false
            for (raw0 in lines) {
                // Go 系 PT 在 Android 上无系统 DNS（回落 [::1]:53）：先把域名端点预解析为 IP
                val raw = rewriteBridgeHostToIp(raw0)
                val line = if (raw.lowercase().startsWith("bridge ")) raw else "Bridge $raw"
                sb.appendLine(line)
                val l = raw.lowercase()
                if (l.contains("obfs4")) needObfs = true
                if (l.contains("webtunnel")) needWt = true
                if (l.contains("snowflake")) needSnow = true
            }
            if (needObfs && lyrebird != null) {
                sb.appendLine("ClientTransportPlugin obfs4 exec ${lyrebird.absolutePath}")
            }
            if (needWt && lyrebird != null) {
                sb.appendLine("ClientTransportPlugin webtunnel exec ${lyrebird.absolutePath}")
            }
            if (needSnow && snowflake != null) {
                sb.appendLine(
                    "ClientTransportPlugin snowflake exec ${snowflake.absolutePath}" +
                        " -log ${File(dir, "snowflake.log").absolutePath}"
                )
            }
        }
        return sb.toString()
    }

    /** 内置可执行文件（Android 10+ 唯一允许 exec 的位置：nativeLibraryDir） */
    private fun nativeBin(name: String): File? =
        File(applicationInfo.nativeLibraryDir, name).takeIf { it.exists() }

    /**
     * 桥行域名端点 → IP：Go 系 PT（lyrebird/snowflake）在 Android 上无系统 DNS，
     * 直接拨域名必然失败（lookup ... [::1]:53 connection refused）。
     * 只改“端点”token（host:port 形式）；url=https://host/... 保持不变（SNI/证书仍用域名）。
     */
    private fun rewriteBridgeHostToIp(raw: String): String {
        // ① webtunnel：追加/覆盖 addr=IP:port（lyrebird 默认拨 url 域名，只有 addr 参数能覆盖）
        val urlTok = raw.split(" ").firstOrNull { it.startsWith("url=") }
        if (urlTok != null) {
            val u = urlTok.removePrefix("url=")
            val host = runCatching { java.net.URI(u).host }.getOrNull()
            if (!host.isNullOrBlank()) {
                val port = runCatching { java.net.URI(u).port }.getOrDefault(-1)
                    .let { if (it > 0) it else 443 }
                val ip = resolveHost(host)
                if (ip != null) {
                    val addrTok = "addr=" + (if (ip.contains(":")) "[$ip]:$port" else "$ip:$port")
                    val tokens = raw.split(" ").toMutableList()
                    val idx = tokens.indexOfFirst { it.startsWith("addr=") }
                    if (idx >= 0) tokens[idx] = addrTok else tokens.add(addrTok)
                    // 端点 token 也规范成 IPv4：tor 按“配置的桥地址”偏好 v6，改 v4 避免 v6 死路拖慢
                    val wtIdx = tokens.indexOfFirst { it.equals("webtunnel", ignoreCase = true) }
                    if (wtIdx >= 0 && wtIdx + 1 < tokens.size) {
                        val ep = tokens[wtIdx + 1]
                        val isV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}:\\d+$").matches(ep)
                        if (!isV4) {
                            tokens[wtIdx + 1] =
                                if (ip.contains(":")) "[$ip]:$port" else "$ip:$port"
                        }
                    }
                    PluginLog.d("svc: 桥 addr=$host:$port → ${if (ip.contains(":")) "[$ip]" else ip}:$port")
                    return tokens.joinToString(" ")
                }
            }
        }
        // ② 其他桥（obfs4 等）：端点 token 若是域名则替换为 IP
        val tokens = raw.split(" ")
        val re = Regex("^([A-Za-z0-9][A-Za-z0-9.-]*\\.[A-Za-z]{2,}):(\\d{1,5})$")
        for ((idx, tok) in tokens.withIndex()) {
            val m = re.find(tok) ?: continue
            val host = m.groupValues[1]
            val port = m.groupValues[2]
            val ip = resolveHost(host) ?: continue
            val newTok = if (ip.contains(":")) "[$ip]:$port" else "$ip:$port"
            val copy = tokens.toMutableList()
            copy[idx] = newTok
            PluginLog.d("svc: 桥端点 $host → $ip")
            return copy.joinToString(" ")
        }
        return raw
    }

    private fun resolveHost(host: String): String? = runCatching {
        val addrs = java.net.InetAddress.getAllByName(host)
        (addrs.firstOrNull { it is java.net.Inet4Address } ?: addrs.firstOrNull())?.hostAddress
    }.getOrNull()

    private fun notifyNow(text: String) {
        runCatching {
            val n = buildNotification(text)
            if (!fgStarted) {
                startForegroundCompat(n)
                fgStarted = true
            } else {
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
            }
        }
        lastNotifyAt = System.currentTimeMillis()
    }

    private fun notifyThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 1500) return
        notifyNow(message)
    }

    private fun startForegroundCompat(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, n)
            }
            PluginLog.d("svc: startForeground ok")
        } catch (t: Throwable) {
            PluginLog.d("svc: startForeground 失败 ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("禁漫姬 · Tor 隧道")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (state == "preparing" || state == "starting") {
            b.setProgress(100, bootstrap, state == "preparing")
        }
        packageManager.getLaunchIntentForPackage("com.jinmanji.app")?.let {
            b.setContentIntent(
                PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
            )
        }
        return b.build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Tor 隧道", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
