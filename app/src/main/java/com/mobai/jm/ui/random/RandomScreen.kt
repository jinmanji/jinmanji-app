package com.mobai.jm.ui.random

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.mobai.jm.data.BlockTagsStore
import com.mobai.jm.data.Comic
import com.mobai.jm.data.JmApi
import com.mobai.jm.data.HomeStore
import com.mobai.jm.data.TagStore
import com.mobai.jm.ui.components.ComicCard
import com.mobai.jm.ui.components.PageBar
import com.mobai.jm.ui.components.SlimTopBar
import com.mobai.jm.util.AppPrefs
import com.mobai.jm.util.ImagePrefetch
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 随机推荐独立页：多页分页（每页重新洗牌采样，翻多少页都有新排序）。
 * 每页数量可在设置中调整（默认 24）。
 */
@Composable
fun RandomScreen(
    onBack: () -> Unit,
    onComicClick: (Comic) -> Unit,
    onSearchTerm: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pageSize = remember { AppPrefs(context).randomPageSize.coerceIn(6, 60) }
    var baseSeed by remember { mutableStateOf(0L) }
    var page by remember { mutableStateOf(0) }
    // 大池：随机池 + 最新 + 排行 去重合并（各页抽取不同子集，不再“同一批只换顺序”）
    val pool = remember(HomeStore.random, HomeStore.latest, HomeStore.ranking, BlockTagsStore.list.toList()) {
        (HomeStore.random + HomeStore.latest + HomeStore.ranking)
            .distinctBy { it.id }
            .filterNot { BlockTagsStore.isBlocked(it) }
    }

    LaunchedEffect(Unit) { HomeStore.loadIfNeeded() }

    // 单次洗牌 + 顺序分页：同一批内不同页零重复（换一批才换新顺序）
    val ordered = remember(pool, baseSeed) {
        if (pool.isEmpty()) emptyList()
        else pool.shuffled(Random(baseSeed)) + pool.shuffled(Random(baseSeed + 7919L))
    }
    val pageCount = if (ordered.isEmpty()) 1 else (ordered.size + pageSize - 1) / pageSize
    val pageItems = remember(ordered, page, pageSize) {
        ordered.drop(page * pageSize).take(pageSize)
    }

    LaunchedEffect(pageItems) {
        TagStore.enrich(context, pageItems, if (BlockTagsStore.list.isNotEmpty()) 30 else 12)
        ImagePrefetch.prefetch(
            context,
            pageItems.take(12).map { it.coverUrl.ifBlank { JmApi.albumThumbUrl(it.id) } },
        )
    }
    val canPrev = page > 0
    val canNext = (page + 1) < pageCount

    BackHandler(onBack = onBack)

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        SlimTopBar(
            title = "随机推荐",
            onBack = onBack,
            actions = {
                IconButton(onClick = {
                    scope.launch {
                        HomeStore.refreshRandom()
                        baseSeed = System.nanoTime()
                        page = 0
                    }
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "换一批")
                }
            },
        )

        when {
            pool.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            else -> {
                Box(Modifier.weight(1f)) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(104.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(pageItems) { comic ->
                            ComicCard(
                                comic = comic,
                                onClick = onComicClick,
                                onAuthorClick = { onSearchTerm(it) },
                            )
                        }
                    }
                }
                PageBar(
                    page = page + 1,
                    pageCount = pageCount,
                    onPageChange = { page = (it - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0)) },
                )
            }
        }
    }
}
