package com.mobai.jm.plugin.tor

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 插件黑匣子：内存环形日志 + 落盘（filesDir/plugin.log），界面可查看/复制 */
object PluginLog {
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buf = ArrayDeque<String>(64)

    @Volatile
    private var file: File? = null

    fun init(dir: File) {
        if (file == null) file = File(dir, "plugin.log")
    }

    fun d(msg: String) {
        val line = "${fmt.format(Date())} $msg"
        synchronized(buf) {
            buf.addLast(line)
            while (buf.size > 64) buf.removeFirst()
        }
        runCatching { file?.appendText(line + "\n") }
    }

    fun tail(n: Int): List<String> = synchronized(buf) { buf.takeLast(n) }

    fun full(): String = synchronized(buf) { buf.joinToString("\n") }
}
