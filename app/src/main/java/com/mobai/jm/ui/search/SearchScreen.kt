package com.mobai.jm.ui.search

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobai.jm.data.Comic
import com.mobai.jm.data.JmApi
import com.mobai.jm.data.SearchHistoryStore
import com.mobai.jm.ui.components.ComicCard
import com.mobai.jm.ui.components.PageBar
import com.mobai.jm.ui.components.SlimTopBar
import com.mobai.jm.util.net.MasqueManager

/** 外部触发搜索的请求（如点击 tag/作者） */
data class SearchRequest(val query: String, val seq: Long)

/** 车牌号/纯数字 识别：jm123456 / JM123456 / 123456 */
private val ID_PATTERN = Regex("^\\s*(?:[jJ][mM])?\\s*(\\d{1,10})\\s*$")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    request: SearchRequest?,
    onOpenAlbum: (String) -> Unit,
    onComicClick: (Comic) -> Unit,
) {
    val context = LocalContext.current

    // 输入框本地镜像（结果/页码在 SearchStore，切页返回不丢）
    var text by remember { mutableStateOf(SearchStore.query) }
    LaunchedEffect(SearchStore.query) { text = SearchStore.query }

    // 搜索历史批量选择
    var histSelectMode by remember { mutableStateOf(false) }
    var histSelected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showClearDialog by remember { mutableStateOf(false) }

    // 筛选下拉
    var showFilterMenu by remember { mutableStateOf(false) }
    var showAddTerm by remember { mutableStateOf(false) }
    var termInput by remember { mutableStateOf("") }
    var termError by remember { mutableStateOf<String?>(null) }

    fun submit(raw: String) {
        val q = raw.trim()
        if (q.isEmpty() || SearchStore.loading) return
        val idMatch = ID_PATTERN.matchEntire(q)
        if (idMatch != null) {
            onOpenAlbum(idMatch.groupValues[1])
            return
        }
        SearchHistoryStore.record(q)
        SearchStore.search(q)
    }

    fun exitHistSelect() {
        histSelectMode = false
        histSelected = emptySet()
    }

    LaunchedEffect(request?.seq) {
        if (request != null) {
            text = request.query
            submit(request.query)
        }
    }

    val filtered = SearchStore.applyFilter(SearchStore.results)
    val pageCount = SearchStore.pageCount()

    Column(Modifier.fillMaxSize()) {
        SlimTopBar(title = "搜索")

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    if (it.isNotBlank() && histSelectMode) exitHistSelect()
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("请输入……") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(text) }),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { submit(text) }, enabled = !SearchStore.loading) { Text("搜索") }
        }

        // 筛选按钮 + 结果计数
        if (!text.isBlank() && SearchStore.searched) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    OutlinedButton(onClick = { showFilterMenu = true }) {
                        Text(
                            if (SearchStore.filterActive)
                                "筛选（已选 ${SearchStore.selectedLangs.size + SearchStore.selectedTerms.size + if (SearchStore.fullColorOnly) 1 else 0}）"
                            else "筛选"
                        )
                    }
                    DropdownMenu(
                        expanded = showFilterMenu,
                        onDismissRequest = { showFilterMenu = false },
                    ) {
                        MenuLabel("语言（多选）")
                        SearchStore.builtinLangs.forEach { (code, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { SearchStore.toggleLang(code) },
                                trailingIcon = {
                                    if (SearchStore.selectedLangs.contains(code)) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                },
                            )
                        }
                        HorizontalDivider()
                        MenuLabel("属性")
                        DropdownMenuItem(
                            text = { Text("全彩") },
                            onClick = { SearchStore.toggleFullColor() },
                            trailingIcon = {
                                if (SearchStore.fullColorOnly) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            },
                        )
                        if (SearchStore.customTerms.isNotEmpty()) {
                            HorizontalDivider()
                            MenuLabel("自定义")
                            SearchStore.customTerms.forEach { term ->
                                DropdownMenuItem(
                                    text = { Text(term, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = { SearchStore.toggleTerm(term) },
                                    trailingIcon = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (SearchStore.selectedTerms.contains(term)) {
                                                Icon(Icons.Default.Check, contentDescription = null)
                                            }
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "删除",
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .clickable { SearchStore.removeTerm(term) },
                                            )
                                        }
                                    },
                                )
                            }
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("添加筛选词…") },
                            onClick = {
                                showFilterMenu = false
                                termInput = ""
                                termError = null
                                showAddTerm = true
                            },
                        )
                        if (SearchStore.filterActive) {
                            DropdownMenuItem(
                                text = { Text("清除筛选") },
                                onClick = { SearchStore.clearFilter() },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (SearchStore.filterActive)
                        "共 ${SearchStore.total} 条 · 筛后 ${filtered.size} 条"
                    else "共 ${SearchStore.total} 条结果",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (SearchStore.loading && SearchStore.results.isNotEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(2.dp))

        val showHistory = text.isBlank()
        val history = SearchHistoryStore.list

        when {
            SearchStore.loading && SearchStore.results.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (MasqueManager.isRunning) "正在通过 WARP 隧道搜索…" else "搜索中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            showHistory -> {
                if (history.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "输入 jm350234 直达本子\n搜索词会自动记录",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (histSelectMode) "已选 ${histSelected.size} 项" else "搜索历史（长按多选）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (histSelectMode) {
                                TextButton(onClick = {
                                    histSelected = if (histSelected.size == history.size) emptySet()
                                    else history.toSet()
                                }) {
                                    Text(if (histSelected.size == history.size) "取消全选" else "全选")
                                }
                                TextButton(
                                    onClick = {
                                        SearchHistoryStore.removeAll(histSelected)
                                        Toast.makeText(context, "已删除 ${histSelected.size} 条", Toast.LENGTH_SHORT).show()
                                        exitHistSelect()
                                    },
                                    enabled = histSelected.isNotEmpty(),
                                ) { Text("删除") }
                                TextButton(onClick = { exitHistSelect() }) { Text("取消") }
                            } else {
                                TextButton(onClick = { showClearDialog = true }) { Text("清空") }
                            }
                        }
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(history, key = { it }) { h ->
                                val checked = histSelected.contains(h)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                if (histSelectMode) {
                                                    histSelected = if (checked) histSelected - h
                                                    else histSelected + h
                                                } else {
                                                    text = h
                                                    submit(h)
                                                }
                                            },
                                            onLongClick = {
                                                if (!histSelectMode) {
                                                    histSelectMode = true
                                                    histSelected = setOf(h)
                                                }
                                            },
                                        )
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (histSelectMode) {
                                        Checkbox(checked = checked, onCheckedChange = null)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        h,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SearchStore.error != null && SearchStore.results.isEmpty() -> {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(SearchStore.error ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { submit(text) }) { Text("重试") }
                }
            }

            !SearchStore.searched -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "点击「搜索」开始",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    state = SearchStore.gridState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (filtered.isEmpty() && SearchStore.results.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "没有符合筛选的结果",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (SearchStore.error != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "⚠ ${SearchStore.error} · 点此重试",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.clickable { submit(text) },
                            )
                        }
                    }
                    items(filtered, key = { it.id }) { comic ->
                        ComicCard(comic = comic, onClick = onComicClick)
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PageBar(
                            page = SearchStore.page,
                            pageCount = pageCount,
                            hasNext = SearchStore.page < pageCount,
                            onPageChange = { SearchStore.goToPage(it) },
                        )
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空搜索历史") },
            text = { Text("将删除全部 ${SearchHistoryStore.list.size} 条搜索历史，确定吗？") },
            confirmButton = {
                TextButton(onClick = {
                    SearchHistoryStore.clear()
                    showClearDialog = false
                    exitHistSelect()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }

    if (showAddTerm) {
        val cp = termInput.trim().codePointCount(0, termInput.trim().length)
        AlertDialog(
            onDismissRequest = { showAddTerm = false },
            title = { Text("添加筛选词") },
            text = {
                Column {
                    OutlinedTextField(
                        value = termInput,
                        onValueChange = { termInput = it },
                        singleLine = true,
                        label = { Text("筛选词") },
                        supportingText = { Text("$cp / 16 字符") },
                    )
                    if (termError != null) {
                        Text(
                            termError ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val err = SearchStore.addTerm(termInput)
                    if (err == null) {
                        showAddTerm = false
                    } else {
                        termError = err
                    }
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAddTerm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp),
    )
}
