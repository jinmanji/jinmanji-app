package com.mobai.jm.util.net

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mobai.jm.util.AppPrefs
import com.mobai.jm.util.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 隧道插件客户端（v0.11.2）：
 *  ① Provider.call 主通道 —— 低调、可靠，进程未存活也会被自动拉起
 *  ② 老插件/Binder 兜底 —— bindService 通道保留
 * 全链路错误显性化：任何失败都会把原因写进 stateText 供界面显示。
 */
object TunnelPluginClient {

    const val ACTION = "com.mobai.jm.TUNNEL_PLUGIN"
    private const val PLUGIN_PKG = "com.mobai.jm.plugin.tor"
    private const val SERVICE_CLS = "com.mobai.jm.plugin.tor.TorPluginService"
    private const val PERM = "com.mobai.jm.permission.TUNNEL_PLUGIN"
    private const val DESCRIPTOR = "com.mobai.jm.plugin.ITunnelPlugin"
    private val URI: Uri = Uri.parse("content://com.mobai.jm.plugin.tor.api")
    private const val TX_START = 4
    private const val TX_STOP = 5
    private const val TX_STATUS = 6

    var installed by mutableStateOf(false)
        private set
    var bound by mutableStateOf(false)
        private set
    var running by mutableStateOf(false)
        private set
    var bootstrap by mutableStateOf(0)
        private set
    var stateText by mutableStateOf("未安装")
        private set
    var socksPort by mutableStateOf(-1)
        private set

    private var appCtx: Context? = null
    private var remote: IBinder? = null
    private var bindAttempts = 0
    private var transactFails = 0
    private var providerFails = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = service
            transactFails = 0
            DiagLog.d("tor插件: Binder 已绑定 $name")
            poll()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            if (providerFails > 0) bound = false
        }
    }

    fun init(context: Context) {
        if (appCtx == null) appCtx = context.applicationContext
    }

    fun scan(context: Context) {
        init(context)
        val pm = context.packageManager
        val found = runCatching {
            pm.getPackageInfo(PLUGIN_PKG, 0)
            true
        }.getOrDefault(false) || runCatching {
            pm.queryIntentServices(Intent(ACTION).setPackage(PLUGIN_PKG), 0).isNotEmpty()
        }.getOrDefault(false)
        installed = found
        if (found) {
            refreshNow()
            poll()
        } else {
            stateText = "未安装"
            running = false
            socksPort = -1
        }
    }

    /** 立即拉取一次状态（Provider 优先，Binder 兜底） */
    fun refreshNow() {
        val st = providerCall("status")
        if (st != null) {
            providerFails = 0
            bound = true
            applyStatus(st)
            return
        }
        providerFails++
        remote?.let { r ->
            if (r.isBinderAlive) {
                transactStatus()?.let {
                    transactFails = 0
                    bound = true
                    applyStatus(it)
                    return
                }
            }
        }
        bound = false
        if (installed) {
            val ctx = appCtx
            if (ctx != null && remote == null && bindAttempts < 3) bind(ctx)
            if (remote == null) {
                stateText = "等待插件响应（可先打开插件 App 点一次启动）"
            }
        }
    }

    fun start(context: Context, bridges: String) {
        init(context)
        if (!installed) scan(context)
        val viaProvider = providerCall("start", bridges)
        if (viaProvider != null) {
            applyStatus(viaProvider)
        } else {
            remote?.let { if (!transactStart(bridges)) stateText = "启动指令发送失败" }
                ?: run { stateText = "插件无响应：请先打开插件 App 并点一次启动"; return }
        }
        // 跨应用拉起前台服务（前台时通常允许；失败无碍——Provider 已让插件进程内启动）
        runCatching {
            val fg = Intent(ACTION).setPackage(PLUGIN_PKG)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(fg) else context.startService(fg)
        }.onFailure { DiagLog.w("tor插件: 前台服务拉起失败", it) }
        poll()
    }

    fun stop() {
        val st = providerCall("stop")
        if (st != null) applyStatus(st) else transactNoReply(TX_STOP)
        poll()
    }

    private fun bind(context: Context) {
        bindAttempts++
        val flags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
        val candidates = listOf(
            Intent(ACTION).setPackage(PLUGIN_PKG),
            Intent().setComponent(ComponentName(PLUGIN_PKG, SERVICE_CLS)),
        )
        var ok = false
        val results = mutableListOf<String>()
        for (intent in candidates) {
            try {
                val r = context.bindService(intent, conn, flags)
                results.add(if (r) "ok" else "false")
                if (r) {
                    ok = true
                    break
                }
            } catch (t: Throwable) {
                results.add("${t.javaClass.simpleName}:${t.message?.take(60)}")
            }
        }
        if (!ok) {
            DiagLog.w("tor插件: bindService 失败 attempts=$bindAttempts results=$results（Provider 通道仍在尝试）")
        }
    }

    private fun poll() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                val st = providerCall("status")
                if (st != null) {
                    providerFails = 0
                    bound = true
                    applyStatus(st)
                } else {
                    providerFails++
                    var ok = false
                    remote?.let { r ->
                        if (r.isBinderAlive) {
                            transactStatus()?.let {
                                ok = true
                                bound = true
                                applyStatus(it)
                            }
                        }
                    }
                    if (!ok) {
                        bound = false
                        val ctx = appCtx
                        if (installed && ctx != null) {
                            if (remote == null && bindAttempts < 3) bind(ctx)
                            if (providerFails >= 8 && remote == null) {
                                stateText = "插件无响应：请先打开插件 App（禁漫姬·Tor 隧道插件）点一次启动"
                                if (providerFails >= 24) {
                                    stateText = "通道不可用：请确认已安装插件并允许自启动；或直接在插件 App 内使用"
                                }
                            }
                        }
                    }
                }
                delay(1500)
            }
        }
    }

    private fun applyStatus(st: Bundle) {
        val s = st.getString("state") ?: "idle"
        bootstrap = st.getInt("bootstrap", 0)
        running = s == "running"
        socksPort = if (running) st.getInt("socksPort", -1) else -1
        stateText = when (s) {
            "idle" -> "空闲（未启动）"
            "preparing" -> "解包组件…"
            "starting" -> "引导中 $bootstrap%"
            "running" -> "已连接"
            "error" -> "错误：" + (st.getString("message")?.take(80) ?: "")
            "stopped" -> "已停止"
            else -> s
        }
    }

    // ───────────── Provider 通道 ─────────────

    private fun providerCall(method: String, bridges: String? = null): Bundle? {
        val ctx = appCtx ?: return null
        return try {
            val extras = bridges?.let { Bundle().apply { putString("bridges", it) } }
            ctx.contentResolver.call(URI, method, null, extras)
        } catch (t: Throwable) {
            DiagLog.w("tor插件: Provider.$method 异常 ${t.javaClass.simpleName}: ${t.message?.take(80)}")
            null
        }
    }

    // ───────────── Binder 兜底 ─────────────

    private fun transactStatus(): Bundle? {
        val r = remote ?: return null
        val d = Parcel.obtain()
        val rep = Parcel.obtain()
        return try {
            d.writeInterfaceToken(DESCRIPTOR)
            if (!r.transact(TX_STATUS, d, rep, 0)) return null
            rep.readException()
            rep.readBundle(Bundle::class.java.classLoader)
        } catch (t: Throwable) {
            null
        } finally {
            d.recycle()
            rep.recycle()
        }
    }

    private fun transactStart(bridges: String): Boolean {
        val r = remote ?: return false
        val d = Parcel.obtain()
        val rep = Parcel.obtain()
        return try {
            d.writeInterfaceToken(DESCRIPTOR)
            d.writeString(bridges)
            if (!r.transact(TX_START, d, rep, 0)) return false
            rep.readException()
            rep.readBundle(Bundle::class.java.classLoader)?.let { applyStatus(it) }
            true
        } catch (t: Throwable) {
            false
        } finally {
            d.recycle()
            rep.recycle()
        }
    }

    private fun transactNoReply(code: Int) {
        val r = remote ?: return
        val d = Parcel.obtain()
        val rep = Parcel.obtain()
        try {
            d.writeInterfaceToken(DESCRIPTOR)
            r.transact(code, d, rep, 0)
        } catch (_: Throwable) {
        } finally {
            d.recycle()
            rep.recycle()
        }
    }
}

/** 统一隧道出口：WARP（18080）或 Tor 插件（插件上报端口） */
object TunnelRouter {
    fun activeSocksPort(context: Context): Int? {
        val prefs = AppPrefs(context)
        return if (prefs.tunnelMode == "tor") {
            TunnelPluginClient.socksPort.takeIf { it > 0 }
        } else {
            if (MasqueManager.isRunning) MasqueManager.SOCKS_PORT else null
        }
    }
}
