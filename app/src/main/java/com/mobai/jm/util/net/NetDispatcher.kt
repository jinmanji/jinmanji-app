package com.mobai.jm.util.net

import okhttp3.Dispatcher

/**
 * 全局共享网络调度器：图片（Coil）与 API（JmApi）共用，
 * 并发数量可在设置中调节（0 = 自动），运行时即时生效。
 */
object NetDispatcher {
    val dispatcher = Dispatcher()

    /** 共享连接池：大容量 + 10 分钟保活（实测 TLS 握手 ~1s，复用后 TTFB 仅 ~0.3s） */
    val pool = okhttp3.ConnectionPool(32, 10, java.util.concurrent.TimeUnit.MINUTES)

    /** n = 0 → 自动（24/主机）；1..512 → 用户值 */
    fun apply(n: Int) {
        val perHost = if (n <= 0) 24 else n.coerceIn(1, 512)
        dispatcher.maxRequestsPerHost = perHost
        dispatcher.maxRequests = (perHost * 4).coerceAtMost(2048)
    }

    val effectivePerHost: Int
        get() = dispatcher.maxRequestsPerHost
}
