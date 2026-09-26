package com.mobai.jm.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.mobai.jm.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 前台服务：常驻显示下载队列与进度通知 */
class DownloadService : Service() {

    companion object {
        const val ACTION_CANCEL = "com.mobai.jm.download.CANCEL"
        private const val CHANNEL_ID = "mobai_download"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastNotify = 0L
    private var collecting = false
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            DownloadQueue.cancelCurrent()
            return START_NOT_STICKY
        }
        startForegroundSafe(buildNotification(DownloadQueue.tasks, null, done = false))
        if (!collecting) {
            collecting = true
            scope.launch {
                snapshotFlow { DownloadQueue.tasks.toList() }.collect { list -> handle(list) }
            }
        }
        return START_NOT_STICKY
    }

    private fun handle(list: List<DownloadQueue.Task>) {
        if (list.isEmpty()) return
        val now = System.currentTimeMillis()
        val allTerminal = list.all {
            it.state != DownloadQueue.State.QUEUED && it.state != DownloadQueue.State.RUNNING
        }
        if (!allTerminal && now - lastNotify < 700L) return
        lastNotify = now
        val active = list.firstOrNull { it.state == DownloadQueue.State.RUNNING }
            ?: list.firstOrNull { it.state == DownloadQueue.State.QUEUED }
        runCatching {
            NotificationManagerCompat.from(this).notify(
                NOTIF_ID,
                buildNotification(list, active, done = allTerminal),
            )
        }
        if (allTerminal && !stopping) {
            stopping = true
            scope.launch {
                delay(3000)
                runCatching {
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                }
                stopSelf()
            }
        }
    }

    private fun buildNotification(
        list: List<DownloadQueue.Task>,
        active: DownloadQueue.Task?,
        done: Boolean,
    ): Notification {
        createChannel()
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openIntent)
            .setOngoing(!done)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)

        if (done) {
            val okCount = list.count { it.state == DownloadQueue.State.DONE }
            val failCount = list.count { it.state == DownloadQueue.State.FAILED }
            builder
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("禁漫姬 · 下载完成")
                .setContentText("成功 $okCount 个" + if (failCount > 0) "，失败 $failCount 个" else " ✔")
        } else {
            val queueIndex = list.indexOf(active)
            val finished = list.count { it.state == DownloadQueue.State.DONE }
            builder
                .setContentTitle(
                    "禁漫姬 · " + (if (active?.exporting == true) "导出中" else "下载中") +
                        "（队列 ${(queueIndex + 1).coerceAtLeast(1)}/${list.size}）"
                )
                .setContentText(
                    active?.let {
                        if (it.exporting) "「${it.title}」 导出中 ${it.exportDone}/${it.exportTotal}"
                        else "「${it.title}」 ${it.done}/${it.total}"
                    } ?: "准备中…"
                )
                .setSubText("已完成 $finished 个")
                .addAction(
                    0,
                    "取消当前",
                    PendingIntent.getService(
                        this,
                        1,
                        Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            val exporting = active?.exporting == true
            val cur = if (exporting) active?.exportDone ?: 0 else active?.done ?: 0
            val total = if (exporting) active?.exportTotal ?: 0 else active?.total ?: 0
            val pct = if (total > 0) cur * 100 / total else 0
            builder.setProgress(100, pct, total == 0)
        }
        return builder.build()
    }

    private fun startForegroundSafe(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(
                    this,
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "下载", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "本子 PDF 下载进度"
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
