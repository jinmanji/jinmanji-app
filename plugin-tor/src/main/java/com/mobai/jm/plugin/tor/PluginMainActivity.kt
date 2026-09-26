package com.mobai.jm.plugin.tor

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 插件自带界面 v0.1.2：
 *  - 状态直接读取同进程 TorState（不依赖任何 IPC）
 *  - 启动/停止带全过程日志（界面下部「最近日志」，长按复制全部）
 */
class PluginMainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var edit: EditText
    private val handler = Handler(Looper.getMainLooper())
    private val uri: Uri = Uri.parse("content://com.mobai.jm.plugin.tor.api")

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "禁漫姬·Tor 隧道插件"
        PluginLog.init(filesDir)
        PluginLog.d("UI: activity onCreate")

        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }
        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, dp(10))
        }
        edit = EditText(this).apply {
            hint = "网桥行（一行一条；留空=直连 Tor）"
            gravity = Gravity.TOP
            minLines = 3
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val startBtn = Button(this).apply { text = "启动"; setOnClickListener { onStartTor() } }
        val stopBtn = Button(this).apply { text = "停止"; setOnClickListener { onStopTor() } }
        val notifyBtn = Button(this).apply { text = "申请通知权限"; setOnClickListener { maybeRequestNotify() } }
        val warpCb = CheckBox(this).apply {
            text = "经 WARP 出网（先在主体开启 WARP）"
            isChecked = getSharedPreferences("tor", MODE_PRIVATE).getBoolean("use_warp", false)
            setOnCheckedChangeListener { _, v ->
                getSharedPreferences("tor", MODE_PRIVATE).edit().putBoolean("use_warp", v).apply()
            }
        }
        val logTitle = TextView(this).apply {
            text = "最近日志（长按复制全部）"
            textSize = 12f
            setPadding(0, dp(12), 0, dp(4))
        }
        logView = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val logScroll = ScrollView(this).apply { addView(logView) }
        logView.setOnLongClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("mobai-tor-log", PluginLog.full()))
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
            true
        }

        ll.addView(statusView)
        ll.addView(
            edit,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f },
        )
        ll.addView(startBtn)
        ll.addView(stopBtn)
        ll.addView(notifyBtn)
        ll.addView(warpCb)
        ll.addView(logTitle)
        ll.addView(
            logScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { weight = 2f },
        )
        setContentView(ll)

        edit.setText(getSharedPreferences("tor", MODE_PRIVATE).getString("bridges", ""))

        // Provider 自检（同一进程，应为 ok）
        val pr = runCatching { contentResolver.call(uri, "ping", null, null) }.getOrNull()
        PluginLog.d("UI: provider self-test = ${if (pr?.getBoolean("ok") == true) "ok" else "fail"}")
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun onStartTor() {
        statusView.text = "正在发出启动指令…"
        PluginLog.d("UI: 点击启动，桥行长度=${edit.text.length}")
        maybeRequestNotify()
        val bridges = edit.text.toString()
        try {
            getSharedPreferences("tor", MODE_PRIVATE).edit().putString("bridges", bridges).apply()
            val svc = Intent(this, TorPluginService::class.java).putExtra("bridges", bridges)
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
                PluginLog.d("UI: startForegroundService 已发出")
            } catch (e: Throwable) {
                PluginLog.d("UI: FGS 启动失败 ${e.javaClass.simpleName}: ${e.message}")
                runCatching { startService(svc) }
                    .onFailure {
                        PluginLog.d("UI: 普通 startService 也失败: ${it.javaClass.simpleName} ${it.message}")
                        statusView.text = "启动失败：${it.message}"
                    }
            }
        } catch (t: Throwable) {
            PluginLog.d("UI: 启动异常 ${t.javaClass.simpleName}: ${t.message}")
            statusView.text = "启动异常：${t.message}"
        }
        handler.postDelayed({ refresh() }, 500)
    }

    private fun onStopTor() {
        PluginLog.d("UI: 点击停止")
        runCatching { stopService(Intent(this, TorPluginService::class.java)) }
        refresh()
    }

    private fun maybeRequestNotify() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
        }
    }

    /** 状态直接读同进程 TorState（不走 IPC，绝对可靠） */
    private fun refresh() {
        val s = TorState.state
        val boot = TorState.bootstrap
        val msg = TorState.message
        statusView.text = when (s) {
            "idle" -> "状态：空闲（点「启动」开始）"
            "preparing" -> "状态：解包组件…"
            "starting" -> if (boot < 10) {
                "状态：引导中 $boot%\n提示：若长时间停留，检查网络，或勾选「经 WARP 出网」/更换桥行"
            } else "状态：引导中 $boot%"
            "running" -> "状态：已连接 · SOCKS 127.0.0.1:18081"
            "error" -> "状态：错误 · $msg"
            "stopped" -> "状态：已停止"
            else -> "状态：$s $msg"
        }
        logView.text = PluginLog.tail(14).joinToString("\n")
    }
}
