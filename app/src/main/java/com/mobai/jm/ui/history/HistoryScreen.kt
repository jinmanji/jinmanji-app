package com.mobai.jm.ui.history

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mobai.jm.data.Comic
import com.mobai.jm.data.FavStore
import com.mobai.jm.data.HistoryItem
import com.mobai.jm.data.HistoryStore
import com.mobai.jm.data.JmAlbum
import com.mobai.jm.data.TagStats
import com.mobai.jm.ui.components.ComicCard
import com.mobai.jm.ui.components.SlimTopBar
import com.mobai.jm.ui.components.SortDialog
import com.mobai.jm.ui.components.SortField
import com.mobai.jm.ui.components.SortPref
import com.mobai.jm.util.AppPrefs

/**
 * 历史记录页面：
 *  - 支持排序（时间/标题 · 正序/倒序，默认时间倒序）
 *  - 长按多选：批量删除 / 批量收藏 / 批量取消收藏
 */
@Composable
fun HistoryScreen(onBack: () -> Unit, onComicClick: (Comic) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }
    val items = HistoryStore.list

    var sort by remember { mutableStateOf(SortPref.from(prefs.historySort)) }
    var showSortDialog by remember { mutableStateOf(false) }
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showClearDialog by remember { mutableStateOf(false) }

    fun exitSelect() {
        selectMode = false
        selected = emptySet()
    }

    fun toggle(id: String) {
        selected = if (selected.contains(id)) selected - id else selected + id
    }

    BackHandler {
        if (selectMode) exitSelect() else onBack()
    }

    val sorted = items.sortedWith(
        when (sort.mode) {
            SortField.TIME -> compareBy<HistoryItem> { it.viewedAt }
            SortField.TITLE -> compareBy<HistoryItem> { it.title }
        }.let { if (sort.desc) it.reversed() else it }
    )

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
                        selected = if (selected.size == items.size && items.isNotEmpty()) emptySet()
                        else items.map { it.id }.toSet()
                    }) {
                        Text(if (selected.size == items.size && items.isNotEmpty()) "取消全选" else "全选")
                    }
                },
            )
        } else {
            SlimTopBar(
                title = "历史记录",
                onBack = onBack,
                actions = {
                    TextButton(onClick = { showSortDialog = true }) { Text("排序") }
                    TextButton(onClick = { showClearDialog = true }) { Text("清空") }
                },
            )
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有浏览历史～\n看过的本子会出现在这里\n（可在设置中关闭记录）",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(sorted, key = { it.id }) { h ->
                    Box {
                        ComicCard(
                            comic = h.toComic(),
                            onClick = {
                                if (selectMode) toggle(h.id) else onComicClick(h.toComic())
                            },
                            onLongClick = {
                                if (!selectMode) {
                                    selectMode = true
                                    selected = setOf(h.id)
                                }
                            },
                        )
                        if (selectMode) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp),
                            ) {
                                Checkbox(
                                    checked = selected.contains(h.id),
                                    onCheckedChange = { toggle(h.id) },
                                )
                            }
                        }
                    }
                }
            }

            if (selectMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { batchFavorite(items, selected, add = true); exitSelect() },
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("收藏") }
                    FilledTonalButton(
                        onClick = { batchFavorite(items, selected, add = false); exitSelect() },
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("取消收藏") }
                    Button(
                        onClick = {
                            HistoryStore.removeAll(selected)
                            Toast.makeText(context, "已删除 ${selected.size} 条", Toast.LENGTH_SHORT).show()
                            exitSelect()
                        },
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("删除") }
                }
            }
        }
    }

    if (showSortDialog) {
        SortDialog(
            current = sort,
            onDismiss = { showSortDialog = false },
            onPick = { p ->
                sort = p
                prefs.historySort = p.key
                showSortDialog = false
            },
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空历史记录") },
            text = { Text("将删除全部 ${HistoryStore.list.size} 条浏览历史，确定吗？") },
            confirmButton = {
                TextButton(onClick = {
                    HistoryStore.clear()
                    showClearDialog = false
                    exitSelect()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }
}

private fun batchFavorite(items: List<HistoryItem>, selected: Set<String>, add: Boolean) {
    var changed = 0
    selected.forEach { id ->
        val h = items.firstOrNull { it.id == id } ?: return@forEach
        if (add) {
            if (!FavStore.contains(id)) {
                FavStore.add(h.toAlbum(), h.coverUrl)
                TagStats.onFavorite(h.toAlbum())
                changed++
            }
        } else {
            if (FavStore.contains(id)) {
                FavStore.remove(id)
                changed++
            }
        }
    }
}

private fun HistoryItem.toComic() = Comic(
    id = id,
    title = title,
    author = author,
    coverUrl = coverUrl,
)

private fun HistoryItem.toAlbum() = JmAlbum(
    id = id,
    name = title,
    author = if (author.isBlank()) emptyList() else listOf(author),
    tags = emptyList(),
    likes = "",
    views = "",
    episodes = emptyList(),
)
