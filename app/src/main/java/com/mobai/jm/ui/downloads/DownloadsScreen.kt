package com.mobai.jm.ui.downloads

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mobai.jm.download.DownloadQueue
import com.mobai.jm.ui.components.SlimTopBar

/**
 * 我的下载：
 *  - 展示所有下载任务与进度（下载中带进度条）
 *  - 已完成：打开位置 / 分享 / 删除（二次确认，可选"同时删除原始文件"，默认只删记录）
 *  - 长按进入批量选择模式
 *  - 删除进行中/排队中的任务 → 强制终止并清理已下载文件
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val tasks = DownloadQueue.tasks

    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pendingDelete by remember { mutableStateOf<List<DownloadQueue.Task>?>(null) }
    var deleteFileToo by remember { mutableStateOf(false) }

    fun exitSelect() {
        selectMode = false
        selected = emptySet()
    }

    BackHandler {
        if (selectMode) exitSelect() else onBack()
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        if (selectMode) {
            SlimTopBar(
                title = "已选 ${selected.size} 项",
                onBack = { exitSelect() },
                actions = {
                    TextButton(onClick = {
                        val all = tasks.map { it.id }.toSet()
                        selected = if (selected.size == all.size) emptySet() else all
                    }) { Text(if (selected.size == tasks.size && tasks.isNotEmpty()) "取消全选" else "全选") }
                },
            )
        } else {
            SlimTopBar(title = "我的下载", onBack = onBack)
        }

        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有下载任务～\n去本子详情页点「下载」生成 PDF 吧",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(tasks, key = { it.id }) { task ->
                    DownloadItem(
                        task = task,
                        selectMode = selectMode,
                        selected = selected.contains(task.id),
                        onToggle = {
                            if (selectMode) {
                                selected = if (selected.contains(task.id)) selected - task.id else selected + task.id
                            }
                        },
                        onLongPress = {
                            if (!selectMode) {
                                selectMode = true
                                selected = setOf(task.id)
                            }
                        },
                        onOpenFolder = {
                            val folder = DownloadQueue.folderIntent(task)
                            val opened = folder != null &&
                                runCatching { context.startActivity(folder) }.isSuccess
                            if (!opened) {
                                val view = DownloadQueue.viewFileIntent(task)
                                val opened2 = view != null &&
                                    runCatching { context.startActivity(view) }.isSuccess
                                if (!opened2) {
                                    Toast.makeText(
                                        context,
                                        "打不开，请到文件管理器查看：${task.savedTo ?: "(未知)"}",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        },
                        onShare = {
                            val share = DownloadQueue.shareIntent(task)
                            if (share != null) {
                                runCatching {
                                    context.startActivity(Intent.createChooser(share, "分享 PDF"))
                                }.onFailure {
                                    Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "暂不支持分享此文件", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDelete = {
                            deleteFileToo = false
                            pendingDelete = listOf(task)
                        },
                    )
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                }
            }

            if (selectMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            deleteFileToo = false
                            pendingDelete = tasks.filter { selected.contains(it.id) }
                        },
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("删除所选") }
                    TextButton(
                        onClick = { exitSelect() },
                        modifier = Modifier.weight(1f),
                    ) { Text("完成") }
                }
            }
        }
    }

    val targets = pendingDelete
    if (targets != null && targets.isNotEmpty()) {
        val hasActive = targets.any {
            it.state == DownloadQueue.State.RUNNING || it.state == DownloadQueue.State.QUEUED
        }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除下载任务") },
            text = {
                Column {
                    Text(
                        "将删除 ${targets.size} 个任务记录" +
                            if (hasActive) "（进行中的任务会被强制终止，其已下载的临时文件将一并删除）" else "。"
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { deleteFileToo = !deleteFileToo },
                    ) {
                        Checkbox(checked = deleteFileToo, onCheckedChange = { deleteFileToo = it })
                        Text("同时删除原始文件（不可恢复）", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    targets.forEach { DownloadQueue.removeTask(it.id, deleteFileToo) }
                    pendingDelete = null
                    if (selectMode) exitSelect()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadItem(
    task: DownloadQueue.Task,
    selectMode: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    onOpenFolder: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selectMode) onToggle() },
                onLongClick = { onLongPress() },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectMode) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() })
                Spacer(Modifier.width(4.dp))
            }
            if (task.cover != null) {
                AsyncImage(
                    model = task.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 44.dp, height = 58.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "JM${task.albumId} · ${task.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                val status = when (task.state) {
                    DownloadQueue.State.QUEUED -> "排队中…"
                    DownloadQueue.State.RUNNING -> if (task.exporting) {
                        "导出中 ${task.exportDone}/${task.exportTotal}（生成文件）"
                    } else {
                        "下载中 ${task.done}/${task.total}（失败 ${task.failedPages} 页）"
                    }
                    DownloadQueue.State.DONE -> "已完成 · ${task.savedTo ?: ""}"
                    DownloadQueue.State.FAILED -> "失败：${task.error ?: "未知错误"}"
                    DownloadQueue.State.CANCELED -> "已取消"
                }
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val timeText = task.createdAt?.let {
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(it))
                }
                if (timeText != null) {
                    Text(
                        "下载于 $timeText",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (task.state == DownloadQueue.State.RUNNING || task.state == DownloadQueue.State.QUEUED) {
            Spacer(Modifier.height(6.dp))
            val frac = when {
                task.state != DownloadQueue.State.RUNNING -> -1f
                task.exporting && task.exportTotal > 0 -> task.exportDone.toFloat() / task.exportTotal
                !task.exporting && task.total > 0 -> task.done.toFloat() / task.total
                else -> -1f
            }
            if (frac >= 0f) {
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        if (!selectMode) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (task.state == DownloadQueue.State.DONE) {
                    TextButton(onClick = onOpenFolder) { Text("打开位置") }
                    TextButton(onClick = onShare) { Text("分享") }
                }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}
