package com.mobai.jm.plugin.tor

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle

/**
 * 隧道插件 IPC 主通道（ContentProvider.call）。
 * 比 Service 绑定更少受系统/ROM“关联启动”限制；进程若未存活，本调用会自动拉起插件进程。
 */
class TorPluginProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return Bundle().apply {
            putString("state", "error")
            putString("message", "no context")
        }
        return when (method) {
            "ping" -> Bundle().apply { putBoolean("ok", true) }
            "status" -> TorState.toBundle()
            "start" -> {
                val prefs = ctx.getSharedPreferences("tor", Context.MODE_PRIVATE)
                val bridges = extras?.getString("bridges") ?: prefs.getString("bridges", "") ?: ""
                prefs.edit().putString("bridges", bridges).apply()
                // 插件进程内拉起自己的前台服务（自身进程，通常允许；失败也不致命）
                runCatching {
                    val it = Intent(ctx, TorPluginService::class.java)
                        .putExtra("bridges", bridges)
                    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(it)
                    else ctx.startService(it)
                }
                TorState.toBundle()
            }
            "stop" -> {
                runCatching { ctx.stopService(Intent(ctx, TorPluginService::class.java)) }
                TorState.state = "stopped"
                TorState.message = "已停止"
                TorState.toBundle()
            }
            else -> Bundle().apply {
                putString("state", "error")
                putString("message", "未知方法 $method")
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
