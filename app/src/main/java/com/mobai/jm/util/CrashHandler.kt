package com.mobai.jm.util

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Process
import com.mobai.jm.ui.CrashActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 全局崩溃兜底：
 *  - 捕获未处理异常 → 汇总 时间/版本/设备/线程/异常/堆栈
 *  - 落盘 `meta/crash.log`（超 512KB 自动轮转一份 crash.old.log）
 *  - 拉起崩溃提示页（独立进程 :crash，自动复制到剪贴板，引导前往 jinmanji.com 反馈）
 *  - 收尾结束当前进程（崩溃页在独立进程，不受影响）
 */
object CrashHandler {

    const val FEEDBACK_SITE = "jinmanji.com"

    private const val MAX_INFO = 60_000
    private var installed = false

    /** 崩溃进程内不再安装，避免提示页自身崩溃时递归 */
    fun isCrashProcess(): Boolean = runCatching {
        File("/proc/self/cmdline").readText().contains(":crash")
    }.getOrDefault(false)

    fun install(app: Application) {
        if (installed || isCrashProcess()) return
        installed = true
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val info = buildInfo(app, thread, error)
                runCatching { saveLog(app, info) }
                runCatching {
                    app.startActivity(
                        Intent(app, CrashActivity::class.java).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
                            )
                            putExtra(CrashActivity.EXTRA_INFO, info.take(MAX_INFO))
                        },
                    )
                }
                // 给 startActivity 一点时间返回，随后结束本进程
                Thread.sleep(200)
            } catch (_: Throwable) {
                // 兜底失败也要保证进程结束
            } finally {
                runCatching { Process.killProcess(Process.myPid()) }
                exitProcess(10)
            }
        }
    }

    private fun buildInfo(
        context: android.content.Context,
        thread: Thread,
        error: Throwable,
    ): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val ver = runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            (pi.versionName ?: "?") to pi.longVersionCode
        }.getOrElse { "?" to 0L }
        return buildString {
            appendLine("==== 禁漫姬 崩溃报告 ====")
            appendLine("时间：$time")
            appendLine("版本：${ver.first} (${ver.second})")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("系统：Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("线程：${thread.name}")
            appendLine("异常：${error.javaClass.name}: ${error.message}")
            appendLine()
            appendLine("--- 堆栈 ---")
            append(error.stackTraceToString())
        }
    }

    private fun saveLog(context: android.content.Context, info: String) {
        val dir = runCatching { StorageUtil.metaDir(context) }.getOrElse { context.filesDir }
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "crash.log")
        f.appendText(info + "\n\n")
        if (f.length() > 512 * 1024) {
            val old = File(dir, "crash.old.log")
            old.delete()
            f.renameTo(old)
        }
    }
}
