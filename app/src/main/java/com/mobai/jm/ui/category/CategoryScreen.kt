package com.mobai.jm.ui.category

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mobai.jm.data.CategoryStore
import com.mobai.jm.data.Comic
import com.mobai.jm.data.TagStore
import com.mobai.jm.util.ImagePrefetch
import com.mobai.jm.data.CustomCatStore
import com.mobai.jm.data.JmApi
import com.mobai.jm.data.JmCategory
import com.mobai.jm.ui.components.ComicCard
import com.mobai.jm.ui.components.PageBar
import com.mobai.jm.ui.components.SlimTopBar
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CategoryScreen(
    onComicClick: (Comic) -> Unit,
    onSearchTerm: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showAdd by remember { mutableStateOf(false) }
    var addInput by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var pendingRemoveCustom by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { CategoryStore.loadIfNeeded() }

    val current = CategoryStore.selected
    if (current == null) {
        Column(Modifier.fillMaxSize()) {
            SlimTopBar(title = "分类")
            when {
                CategoryStore.loading && CategoryStore.categories.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                CategoryStore.error != null && CategoryStore.categories.isEmpty() -> {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = CategoryStore.error ?: "",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { scope.launch { CategoryStore.load() } }) { Text("重试") }
                    }
                }

                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(CategoryStore.categories) { cat ->
                            val showTotal = cat.total.isNotBlank() && cat.total != "0"
                            ListItem(
                                headlineContent = { Text(cat.name) },
                                supportingContent = {
                                    if (showTotal) Text("${cat.total} 部作品")
                                },
                                trailingContent = {
                                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
                                },
                                modifier = Modifier.clickable { CategoryStore.selected = cat },
                            )
                            HorizontalDivider(Modifier.padding(start = 16.dp))
                        }

                        if (CustomCatStore.list.isNotEmpty()) {
                            item {
                                Text(
                                    "自定义",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
                                )
                            }
                            items(CustomCatStore.list, key = { "c_$it" }) { term ->
                                ListItem(
                                    headlineContent = { Text(term) },
                                    supportingContent = { Text("点击搜索 · 长按删除") },
                                    trailingContent = {
                                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
                                    },
                                    modifier = Modifier.combinedClickable(
                                        onClick = { onSearchTerm(term) },
                                        onLongClick = { pendingRemoveCustom = term },
                                    ),
                                )
                                HorizontalDivider(Modifier.padding(start = 16.dp))
                            }
                        }

                        item {
                            ListItem(
                                headlineContent = { Text("＋ 添加自定义分类 / tag") },
                                supportingContent = { Text("≤16 字符，点击后进入搜索") },
                                modifier = Modifier.clickable {
                                    addInput = ""
                                    addError = null
                                    showAdd = true
                                },
                            )
                        }
                    }
                }
            }
        }
    } else {
        CategoryDetail(
            category = current,
            onBack = { CategoryStore.selected = null },
            onComicClick = onComicClick,
        )
    }

    if (showAdd) {
        val cp = addInput.trim().codePointCount(0, addInput.trim().length)
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("添加自定义分类 / tag") },
            text = {
                Column {
                    OutlinedTextField(
                        value = addInput,
                        onValueChange = { addInput = it },
                        singleLine = true,
                        label = { Text("名称 / 关键词") },
                        supportingText = { Text("$cp / 16 字符") },
                    )
                    if (addError != null) {
                        Text(
                            addError ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val err = CustomCatStore.add(addInput)
                    if (err == null) {
                        showAdd = false
                    } else {
                        addError = err
                    }
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("取消") }
            },
        )
    }

    val removeTarget = pendingRemoveCustom
    if (removeTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingRemoveCustom = null },
            title = { Text("删除") },
            text = { Text("删除自定义分类「$removeTarget」？") },
            confirmButton = {
                TextButton(onClick = {
                    CustomCatStore.remove(removeTarget)
                    pendingRemoveCustom = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveCustom = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryDetail(
    category: JmCategory,
    onBack: () -> Unit,
    onComicClick: (Comic) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // "最新A漫" 等无 slug 的分类：查询全部(c=0)
    val queryCategory = category.slug.ifBlank { "0" }
    var order by remember(category.id) {
        mutableStateOf(if (category.slug.isBlank()) "mr" else "mv")
    }
    var reloadTick by remember { mutableStateOf(0) }
    var comics by remember { mutableStateOf<List<Comic>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(1) }

    BackHandler(onBack = onBack)

    LaunchedEffect(order, reloadTick) {
        loading = true
        error = null
        page = 1
        try {
            comics = JmApi.ranking(queryCategory, order, 1)
            TagStore.enrich(context, comics, if (com.mobai.jm.data.BlockTagsStore.list.isNotEmpty()) 30 else 12)
            ImagePrefetch.prefetch(
                context,
                comics.take(12).map { it.coverUrl.ifBlank { JmApi.albumThumbUrl(it.id) } },
            )
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    fun goPage(p: Int) {
        if (p < 1 || loading) return
        scope.launch {
            loading = true
            error = null
            try {
                val fresh = JmApi.ranking(queryCategory, order, p)
                if (fresh.isEmpty() && p > 1) {
                    Toast.makeText(context, "没有更多了", Toast.LENGTH_SHORT).show()
                } else {
                    comics = fresh
                    page = p
                    TagStore.enrich(context, fresh, 12)
                }
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally {
                loading = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        SlimTopBar(title = category.name, onBack = onBack)

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val orders = listOf(
                "mv_t" to "日榜",
                "mv_w" to "周榜",
                "mv_m" to "月榜",
                "mv" to "总榜",
                "mr" to "最新",
            )
            orders.forEach { (value, label) ->
                FilterChip(
                    selected = order == value,
                    onClick = { if (order != value) order = value },
                    label = { Text(label) },
                )
            }
        }

        when {
            loading && comics.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            error != null && comics.isEmpty() -> {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { reloadTick++ }) { Text("重试") }
                }
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    gridItems(comics, key = { it.id }) { comic ->
                        ComicCard(comic = comic, onClick = onComicClick)
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (loading) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                            PageBar(
                                page = page,
                                onPageChange = { goPage(it) },
                            )
                        }
                    }
                }
            }
        }
    }
}
