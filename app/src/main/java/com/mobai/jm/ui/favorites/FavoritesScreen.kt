package com.mobai.jm.ui.favorites

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mobai.jm.data.Comic
import com.mobai.jm.data.FavItem
import com.mobai.jm.data.FavStore
import com.mobai.jm.data.JmApi
import com.mobai.jm.data.TagStats
import com.mobai.jm.util.AppPrefs
import com.mobai.jm.ui.components.ComicCard
import com.mobai.jm.ui.components.SlimTopBar
import com.mobai.jm.ui.components.SortDialog
import com.mobai.jm.ui.components.SortField
import com.mobai.jm.ui.components.SortPref
import kotlinx.coroutines.launch

private val ADD_ID_PATTERN = Regex("^\\s*(?:[jJ][mM])?\\s*(\\d{1,10})\\s*$")

/**
 * 本地收藏管理：
 *  - 手动添加：jm车牌号 / 纯数字 → 收藏本子；#开头 或其它 → 收藏标签
 *  - 标签：点按搜索，长按移除
 *  - 本子：点按打开，长按移除
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun FavoritesScreen(
    onComicClick: (Comic) -> Unit,
    onTagSearch: (String) -> Unit,
    onOpenCloud: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPrefs(context) }
    val items = FavStore.list
    val tags = FavStore.favTags
    var sort by remember { mutableStateOf(SortPref.from(prefs.favSort)) }
    var showSortDialog by remember { mutableStateOf(false) }

    var input by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<FavItem?>(null) }
    var pendingRemoveTag by remember { mutableStateOf<String?>(null) }

    val sortedItems = items.sortedWith(
        when (sort.mode) {
            SortField.TIME -> compareBy<FavItem> { it.addedAt }
            SortField.TITLE -> compareBy<FavItem> { it.title }
        }.let { if (sort.desc) it.reversed() else it }
    )

    fun submitAdd() {
        val text = input.trim()
        if (text.isEmpty() || adding) return
        val idMatch = ADD_ID_PATTERN.matchEntire(text)
        if (idMatch != null && !text.startsWith("#")) {
            scope.launch {
                adding = true
                try {
                    val album = JmApi.album(idMatch.groupValues[1])
                    FavStore.add(album, JmApi.albumCoverUrl(album.id))
                    TagStats.onFavorite(album)
                    input = ""
                    Toast.makeText(context, "已添加本子 JM${album.id}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "添加失败：${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    adding = false
                }
            }
        } else {
            val tag = text.removePrefix("#").removePrefix("＃").trim()
            if (tag.isNotEmpty()) {
                FavStore.addTag(tag)
                input = ""
                Toast.makeText(context, "已添加标签：$tag", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        SlimTopBar(
            title = "收藏",
            actions = {
                TextButton(onClick = { showSortDialog = true }) { Text("排序") }
                TextButton(onClick = onOpenCloud) { Text("词云") }
            },
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("jm350234 或 #标签") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitAdd() }),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { submitAdd() }, enabled = !adding) { Text("添加") }
                }
            }

            if (tags.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(top = 6.dp)) {
                        Text(
                            "标签（点按搜索 · 长按移除）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            tags.forEach { tag ->
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.combinedClickable(
                                        onClick = { onTagSearch(tag) },
                                        onLongClick = { pendingRemoveTag = tag },
                                    ),
                                ) {
                                    Text(
                                        text = tag,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "本子（长按移除）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "还没有收藏～ 在本子详情页点右下角 ♥ 即可加入",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                }
            }

            items(sortedItems, key = { it.id }) { fav ->
                ComicCard(
                    comic = fav.toComic(),
                    onClick = onComicClick,
                    onLongClick = { pendingRemove = fav },
                )
            }
        }
    }

    if (showSortDialog) {
        SortDialog(
            current = sort,
            onDismiss = { showSortDialog = false },
            onPick = { p ->
                sort = p
                prefs.favSort = p.key
                showSortDialog = false
            },
        )
    }

    val removeTarget = pendingRemove
    if (removeTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("取消收藏") },
            text = { Text("将「${removeTarget.title}」从本地收藏中移除？") },
            confirmButton = {
                TextButton(onClick = {
                    FavStore.remove(removeTarget.id)
                    pendingRemove = null
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("取消") }
            },
        )
    }

    val removeTag = pendingRemoveTag
    if (removeTag != null) {
        AlertDialog(
            onDismissRequest = { pendingRemoveTag = null },
            title = { Text("移除标签") },
            text = { Text("将标签「$removeTag」从收藏中移除？") },
            confirmButton = {
                TextButton(onClick = {
                    FavStore.removeTag(removeTag)
                    pendingRemoveTag = null
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveTag = null }) { Text("取消") }
            },
        )
    }
}

private fun FavItem.toComic() = Comic(
    id = id,
    title = title,
    author = author,
    coverUrl = coverUrl,
)
