package com.mobai.jm.plugin.tor

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Tor 控制端口客户端（cookie 认证）：
 * 直接查询 status/bootstrap-phase（权威进度）与 status/circuit-established，
 * 取代脆弱的日志逐行解析（info 级日志洪流会淹没 Bootstrapped 行）。
 */
object TorControl {
    private const val PORT = 18082

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start(dataDir: File, onUpdate: (Int, String, Boolean) -> Unit) {
        if (running) return
        running = true
        thread = Thread {
            while (running) {
                runCatching { session(dataDir, onUpdate) }
                if (running) runCatching { Thread.sleep(2500) }
            }
        }.also { it.isDaemon = true; it.start() }
        PluginLog.d("ctl: 控制通道已启动")
    }

    fun stop() {
        running = false
        runCatching { thread?.interrupt() }
        thread = null
        PluginLog.d("ctl: 控制通道已停止")
    }

    private fun session(dataDir: File, onUpdate: (Int, String, Boolean) -> Unit) {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", PORT), 3000)
            s.soTimeout = 3000
            val out = s.getOutputStream()
            val inp = BufferedReader(InputStreamReader(s.getInputStream()))

            var cookie: String? = null
            var tries = 0
            while (cookie == null && running && tries < 30) {
                cookie = readCookie(dataDir)
                if (cookie == null) runCatching { Thread.sleep(300) }
                tries++
            }
            val auth = cookie ?: return
            out.write("AUTHENTICATE $auth\r\n".toByteArray())
            out.flush()
            val authResp = inp.readLine() ?: return
            if (!authResp.startsWith("250")) {
                PluginLog.d("ctl: 认证失败 $authResp")
                return
            }

            while (running) {
                out.write("GETINFO status/bootstrap-phase status/circuit-established\r\n".toByteArray())
                out.flush()
                var progress = -1
                var summary = ""
                var circOk = false
                var guard = 0
                while (guard++ < 50) {
                    val line = inp.readLine() ?: return
                    val body = line
                        .removePrefix("250+")
                        .removePrefix("250-")
                        .removePrefix("250=")
                        .removePrefix("250 ")
                    Regex("PROGRESS=(\\d+)").find(body)?.let {
                        progress = it.groupValues[1].toIntOrNull() ?: progress
                    }
                    Regex("SUMMARY=\"([^\"]*)\"").find(body)?.let {
                        summary = it.groupValues[1]
                    }
                    if (body.contains("circuit-established=1")) circOk = true
                    if (line.startsWith("250 ")) break
                    if (line.startsWith("5")) {
                        PluginLog.d("ctl: $line")
                        break
                    }
                }
                if (progress >= 0) {
                    PluginLog.d("ctl: bootstrap=$progress% $summary${if (circOk) " (circuit ok)" else ""}")
                    onUpdate(progress, summary, circOk)
                }
                runCatching { Thread.sleep(2000) }
            }
        }
    }

    private fun readCookie(dataDir: File): String? = runCatching {
        val f = File(dataDir, "control_auth_cookie")
        if (!f.exists() || f.length() < 16) return null
        f.readBytes().take(16).joinToString("") { "%02X".format(it) }
    }.getOrNull()
}
