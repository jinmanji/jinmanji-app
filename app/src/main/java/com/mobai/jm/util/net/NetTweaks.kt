package com.mobai.jm.util.net

import android.content.Context
import android.net.SSLCertificateSocketFactory
import com.mobai.jm.util.AppPrefs
import com.mobai.jm.util.DiagLog
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 网络实验特性统一装配：
 *  - SNI 切片（ClientHello 分段）
 *  - SNI 绕过（置空）
 *  - DoH（*.gl.doh.tw 等 RFC8484 端点）
 *  - ECH（实验：需 DoH + 目标服务器发布 ECH 配置 + 系统 TLS 栈支持）
 *
 * 全部特性仅在开发者模式下可见、可调；异常一律降级为"不启用"，不影响正常使用。
 */
object NetTweaks {

    /** 把当前配置应用到 OkHttp Builder（JmApi 与 Coil 共用） */
    fun apply(builder: OkHttpClient.Builder, context: Context) {
        val prefs = AppPrefs(context)

        when (prefs.dohMode) {
            "enhanced" -> runCatching {
                DohDns.defaultTemplate = prefs.dohUrl
                builder.dns(FallbackDns(DohDns(prefs.dohUrl)))
                DiagLog.d("net: DoH(增强) 已启用 ${DohDns.endpoint(prefs.dohUrl)}")
            }.onFailure { DiagLog.w("net: DoH(增强) 初始化失败", it) }

            "strict" -> runCatching {
                DohDns.defaultTemplate = prefs.dohUrl
                builder.dns(StrictDns(DohDns(prefs.dohUrl)))
                DiagLog.d("net: DoH(完全) 已启用 ${DohDns.endpoint(prefs.dohUrl)}")
            }.onFailure { DiagLog.w("net: DoH(完全) 初始化失败", it) }

            "system" -> DiagLog.d("net: DNS 遵循系统设置")
        }

        // 动态代理：运行时全流量走本地 SOCKS5（WARP 或 Tor 插件，开关即时生效）
        runCatching {
            builder.proxySelector(MasqueProxySelector(context.applicationContext))
        }.onFailure { DiagLog.w("net: 代理挂载失败", it) }

        // 隧道流量统计：按实际读出字节计数（仅隧道运行时计数）
        runCatching {
            builder.addInterceptor(TunnelCountingInterceptor())
        }.onFailure { DiagLog.w("net: 流量统计挂载失败", it) }

        // 隧道开启时切片无意义（内层包不被审查者看到），反而拖慢握手，自动跳过
        if (prefs.sniSplit && !tunnelActive(prefs)) {
            runCatching {
                builder.socketFactory(
                    FragmentedSocketFactory(
                        SocketFactory.getDefault(),
                        prefs.sniSplitPos.coerceIn(1, 4096),
                        prefs.sniSplitDelay.toLong().coerceIn(0, 500),
                    )
                )
                DiagLog.d("net: SNI 切片已启用 (pos=${prefs.sniSplitPos}, delay=${prefs.sniSplitDelay}ms)")
            }.onFailure { DiagLog.w("net: SNI 切片初始化失败", it) }
        }

        val bypassActive = prefs.sniBypass && !tunnelActive(prefs)
        if (bypassActive || prefs.echEnabled) {
            runCatching {
                val tmf = TrustManagerFactory
                    .getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    .apply { init(null as KeyStore?) }
                val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
                val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
                val wrapped = JmSslSocketFactory(ctx.socketFactory, context, bypassActive)
                builder.sslSocketFactory(wrapped, tm)
                DiagLog.d("net: TLS 包装已启用 (sniBypass=$bypassActive, ech=${prefs.echEnabled})")
            }.onFailure { DiagLog.w("net: TLS 包装初始化失败", it) }
        }
    }

    /** 任一隧道激活（WARP 或 Tor 插件） */
    private fun tunnelActive(p: AppPrefs): Boolean =
        MasqueManager.isRunning || (p.tunnelMode == "tor" && TunnelPluginClient.socksPort > 0)

    fun describe(context: Context): String {
        val p = AppPrefs(context)
        val parts = ArrayList<String>(5)
        when (p.dohMode) {
            "enhanced" -> parts.add("DoH增强")
            "strict" -> parts.add("DoH完全")
            "system" -> parts.add("DNS系统")
        }
        if (p.sniSplit) parts.add("切片")
        if (p.sniBypass) parts.add("SNI置空")
        if (p.echEnabled) parts.add("ECH")
        if (MasqueManager.isRunning) parts.add("MASQUE")
        if (p.tunnelMode == "tor" && TunnelPluginClient.socksPort > 0) parts.add("Tor")
        return if (parts.isEmpty()) "全部关闭" else parts.joinToString("+")
    }
}

/**
 * SSLSocketFactory 包装：控制 SNI（置空）并挂 ECH 注入钩子。
 * 注意：被本包装的 socket 不是 conscrypt 内部类实例，OkHttp 的
 * AndroidSocketAdapter 会自动跳过 setHostname —— 这正是"SNI 置空"生效的机制；
 * SNI 由底层 createSocket(host) 参数决定，我们把 host 换成空串即可。
 */
class JmSslSocketFactory(
    private val delegate: SSLSocketFactory,
    private val context: Context,
    private val fakeSni: Boolean,
) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(): Socket =
        wrap(delegate.createSocket(), null)

    override fun createSocket(host: String, port: Int): Socket =
        wrap(openHost(host, port), host)

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        wrap(openHost(host, port, localHost, localPort), host)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        wrap(delegate.createSocket(host, port), null)

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        wrap(delegate.createSocket(address, port, localAddress, localPort), null)

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        wrap(openLayered(s, host, port, autoClose), host)

    private fun openHost(host: String, port: Int): Socket {
        if (!fakeSni) return delegate.createSocket(host, port)
        return try {
            delegate.createSocket("", port)
        } catch (t: Throwable) {
            DiagLog.w("SNI 置空失败，回退真实 SNI: ${t.message}")
            delegate.createSocket(host, port)
        }
    }

    private fun openHost(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        if (!fakeSni) return delegate.createSocket(host, port, localHost, localPort)
        return try {
            delegate.createSocket("", port, localHost, localPort)
        } catch (t: Throwable) {
            DiagLog.w("SNI 置空失败，回退真实 SNI: ${t.message}")
            delegate.createSocket(host, port, localHost, localPort)
        }
    }

    private fun openLayered(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        if (!fakeSni) return delegate.createSocket(s, host, port, autoClose)
        return try {
            delegate.createSocket(s, "", port, autoClose)
        } catch (t: Throwable) {
            DiagLog.w("SNI 置空失败，回退真实 SNI: ${t.message}")
            delegate.createSocket(s, host, port, autoClose)
        }
    }

    private fun wrap(real: Socket, host: String?): Socket =
        JmSslSocket(real as SSLSocket, context, host)
}

/** 委托式 SSLSocket：仅拦截 startHandshake 注入 ECH，其余全部透传 */
class JmSslSocket(
    private val real: SSLSocket,
    private val context: Context,
    private val host: String?,
) : SSLSocket() {

    override fun startHandshake() {
        EchSupport.maybeApply(real, host, context)
        real.startHandshake()
    }

    // ── Socket 基础方法透传 ──
    override fun connect(endpoint: java.net.SocketAddress?) = real.connect(endpoint)
    override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) = real.connect(endpoint, timeout)
    override fun getInetAddress(): InetAddress? = real.inetAddress
    override fun getLocalAddress(): InetAddress = real.localAddress
    override fun getLocalPort(): Int = real.localPort
    override fun getPort(): Int = real.port
    override fun getRemoteSocketAddress(): java.net.SocketAddress? = real.remoteSocketAddress
    override fun getLocalSocketAddress(): java.net.SocketAddress? = real.localSocketAddress
    override fun getChannel() = real.channel
    override fun getInputStream() = real.getInputStream()
    override fun getOutputStream() = real.getOutputStream()
    override fun setTcpNoDelay(on: Boolean) { real.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = real.tcpNoDelay
    override fun setSoLinger(on: Boolean, linger: Int) { real.setSoLinger(on, linger) }
    override fun getSoLinger(): Int = real.soLinger
    override fun sendUrgentData(data: Int) = real.sendUrgentData(data)
    override fun setOOBInline(on: Boolean) { real.oobInline = on }
    override fun getOOBInline(): Boolean = real.oobInline
    override fun setSoTimeout(timeout: Int) { real.soTimeout = timeout }
    override fun getSoTimeout(): Int = real.soTimeout
    override fun setSendBufferSize(size: Int) { real.sendBufferSize = size }
    override fun getSendBufferSize(): Int = real.sendBufferSize
    override fun setReceiveBufferSize(size: Int) { real.receiveBufferSize = size }
    override fun getReceiveBufferSize(): Int = real.receiveBufferSize
    override fun setKeepAlive(on: Boolean) { real.keepAlive = on }
    override fun getKeepAlive(): Boolean = real.keepAlive
    override fun setReuseAddress(on: Boolean) { real.reuseAddress = on }
    override fun getReuseAddress(): Boolean = real.reuseAddress
    override fun close() = real.close()
    override fun shutdownInput() = real.shutdownInput()
    override fun shutdownOutput() = real.shutdownOutput()

    // ── SSLSocket 方法透传 ──
    override fun getSupportedCipherSuites(): Array<String> = real.supportedCipherSuites
    override fun getEnabledCipherSuites(): Array<String> = real.enabledCipherSuites
    override fun setEnabledCipherSuites(suites: Array<String>) { real.enabledCipherSuites = suites }
    override fun getSupportedProtocols(): Array<String> = real.supportedProtocols
    override fun getEnabledProtocols(): Array<String> = real.enabledProtocols
    override fun setEnabledProtocols(protocols: Array<String>) { real.enabledProtocols = protocols }
    override fun getSession() = real.session
    override fun addHandshakeCompletedListener(listener: javax.net.ssl.HandshakeCompletedListener) {
        real.addHandshakeCompletedListener(listener)
    }

    override fun removeHandshakeCompletedListener(listener: javax.net.ssl.HandshakeCompletedListener) {
        real.removeHandshakeCompletedListener(listener)
    }

    override fun setUseClientMode(mode: Boolean) { real.useClientMode = mode }
    override fun getUseClientMode(): Boolean = real.useClientMode
    override fun setNeedClientAuth(need: Boolean) { real.needClientAuth = need }
    override fun getNeedClientAuth(): Boolean = real.needClientAuth
    override fun setWantClientAuth(want: Boolean) { real.wantClientAuth = want }
    override fun getWantClientAuth(): Boolean = real.wantClientAuth
    override fun setEnableSessionCreation(flag: Boolean) { real.enableSessionCreation = flag }
    override fun getEnableSessionCreation(): Boolean = real.enableSessionCreation
    override fun getHandshakeSession() = real.handshakeSession
    override fun getApplicationProtocol(): String? = real.applicationProtocol
    override fun getHandshakeApplicationProtocol(): String? = real.handshakeApplicationProtocol

    override fun getSSLParameters(): javax.net.ssl.SSLParameters = real.sslParameters
    override fun setSSLParameters(p: javax.net.ssl.SSLParameters) { real.sslParameters = p }
}

/** ECH 注入（实验）：从 DoH 的 HTTPS RR 拿 ECHConfigList，反射注入 conscrypt */
object EchSupport {
    private val logged = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun logOnce(key: String, msg: String) {
        if (logged.putIfAbsent(key, true) == null) DiagLog.d("net(ech): $msg")
    }

    fun maybeApply(real: SSLSocket, host: String?, context: Context) {
        val prefs = AppPrefs(context)
        if (!prefs.echEnabled || host.isNullOrBlank()) return
        if (prefs.dohMode != "enhanced" && prefs.dohMode != "strict") {
            logOnce(host, "ECH 需要 DoH（增强/完全模式，用于查询 HTTPS 记录）")
            return
        }
        runCatching {
            val config = DohDns.queryEchConfig(host)
            if (config == null) {
                logOnce(host, "$host 未发布 ECH 配置，跳过（不影响使用）")
                return
            }
            val m = real.javaClass.getMethod("setEchConfigList", ByteArray::class.java)
            m.invoke(real, config)
            DiagLog.d("net(ech): ECH 已注入 $host (${config.size}B)")
        }.onFailure {
            logOnce(host, "ECH 注入失败（${it.message}），系统 TLS 栈可能不支持")
        }
    }
}
