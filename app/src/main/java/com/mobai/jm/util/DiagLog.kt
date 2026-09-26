package com.mobai.jm.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** 日志级别（可在设置中调整；None 为关闭，默认 Error） */
enum class LogLevel(val order: Int, val label: String) {
    NONE(0, "关闭"),
    ERROR(1, "错误"),
    WARN(2, "警告"),
    INFO(3, "信息"),
    DEBUG(4, "调试"),
}

/**
 * 轻量文件日志。
 * 写入 App 外部媒体目录：/Android/media/com.mobai.jm/mobai.log
 * （该目录其他应用/工具可读，方便在 Termux 中直接查看日志排查问题）
 */
object DiagLog {
    private const val MAX_SIZE = 300_000L
    private val executor = Executors.newSingleThreadExecutor()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var minLevel: LogLevel = LogLevel.ERROR

    val path: String
        get() = logFile?.absolutePath ?: "(不可用)"

    fun init(context: Context) {
        runCatching {
            val dir = context.getExternalMediaDirs().firstOrNull() ?: return
            dir.mkdirs()
            logFile = File(dir, "mobai.log")
        }
        minLevel = runCatching { LogLevel.valueOf(AppPrefs(context).logLevel) }
            .getOrDefault(LogLevel.ERROR)
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        d("===== 禁漫姬 v$version 启动（日志级别=${minLevel.label}）=====")
    }

    fun setLevel(level: LogLevel) {
        minLevel = level
    }

    fun d(message: String) = write(LogLevel.DEBUG, "D", message)

    fun i(message: String) = write(LogLevel.INFO, "I", message)

    fun w(message: String, e: Throwable? = null) =
        write(LogLevel.WARN, "W", message + (e?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""))

    fun e(message: String, e: Throwable? = null) =
        write(LogLevel.ERROR, "E", message + (e?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""))

    private fun write(level: LogLevel, tag: String, message: String) {
        if (minLevel == LogLevel.NONE || level.order > minLevel.order) return
        val file = logFile ?: return
        executor.execute {
            runCatching {
                if (file.exists() && file.length() > MAX_SIZE) file.delete()
                file.appendText("${timeFormat.format(Date())} [$tag] $message\n")
            }
        }
    }
}
