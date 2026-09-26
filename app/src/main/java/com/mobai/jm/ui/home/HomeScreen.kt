package com.mobai.jm.ui.home

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import com.mobai.jm.data.BlockTagsStore
import com.mobai.jm.data.Comic
import com.mobai.jm.data.CategoryStore
import com.mobai.jm.data.CustomCatStore
import com.mobai.jm.data.HomeStore
import com.mobai.jm.data.JmApi
import com.mobai.jm.data.JmCategory
import com.mobai.jm.data.TagStore
import com.mobai.jm.util.ImagePrefetch
import com.mobai.jm.ui.components.ComicCard
import com.mobai.jm.ui.components.PageBar
import com.mobai.jm.ui.components.SlimTopBar
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onComicClick: (Comic) -> Unit,
    onOpenRandom: () -> Unit,
    onOpenCategory: (JmCategory) -> Unit,
    onSearchTerm: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // 封面预取：首屏数据到位后，后台预热前几排封面（滚动更顺滑）
    LaunchedEffect(
        HomeStore.random.firstOrNull()?.id,
        HomeStore.ranking.firstOrNull()?.id,
        HomeStore.latest.firstOrNull()?.id,
    ) {
        val urls = (HomeStore.random.take(6) + HomeStore.ranking.take(9) + HomeStore.latest.take(12))
            .map { it.coverUrl.ifBlank { JmApi.albumThumbUrl(it.id) } }
            .filter { it.isNotBlank() }
        ImagePrefetch.prefetch(context, urls)
    }

    // tag 校准：后台抓列表条目的详情标签（国旗/全彩更准；有屏蔽词时全量校准保障屏蔽生效）
    LaunchedEffect(
        HomeStore.random.firstOrNull()?.id,
        HomeStore.latest.firstOrNull()?.id,
        BlockTagsStore.list.size,
    ) {
        if (BlockTagsStore.list.isNotEmpty()) {
            TagStore.enrich(
                context,
                (HomeStore.random + HomeStore.ranking + HomeStore.latest).distinctBy { it.id },
                90,
            )
        } else {
            TagStore.enrich(
                context,
                HomeStore.random.take(8) + HomeStore.ranking.take(8) + HomeStore.latest.take(12),
                30,
            )
        }
    }

    LaunchedEffect(Unit) { HomeStore.loadIfNeeded() }
    LaunchedEffect(Unit) { CategoryStore.loadIfNeeded() }

    // 刷新失败时用 Snackbar 提示（页面已有内容的情况下）
    LaunchedEffect(HomeStore.error) {
        val message = HomeStore.error ?: return@LaunchedEffect
        if (HomeStore.random.isNotEmpty() || HomeStore.ranking.isNotEmpty()) {
            snackbarHostState.showSnackbar(message)
            HomeStore.clearError()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SlimTopBar(title = "禁漫姬")

            // 加载/刷新反馈（细进度条）
            if (HomeStore.loading || HomeStore.randomLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            when {
                HomeStore.loading && HomeStore.random.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                HomeStore.error != null && HomeStore.random.isEmpty() -> {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = HomeStore.error ?: "",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { scope.launch { HomeStore.load() } }) { Text("重试") }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        item {
                            SectionHeader(
                                title = "随机推荐",
                                actionText = "换一批",
                                loading = HomeStore.randomLoading,
                                onAction = { scope.launch { HomeStore.refreshRandom() } },
                                actionText2 = "更多 ›",
                                onAction2 = onOpenRandom,
                            )
                        }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(HomeStore.random.filterNot { BlockTagsStore.isBlocked(it) }) { comic ->
                                    ComicCard(
                                        comic = comic,
                                        modifier = Modifier.width(108.dp),
                                        onClick = onComicClick,
                                    )
                                }
                            }
                        }

                        item { SectionHeader(title = "分类速览") }
                        item {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                val quickCats: List<Pair<String, () -> Unit>> =
                                    CategoryStore.categories.take(12).map { c -> c.name to { onOpenCategory(c) } } +
                                        CustomCatStore.list.take(6).map { t -> t to { onSearchTerm(t) } }
                                items(quickCats) { (label, action) ->
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        modifier = Modifier.clickable { action() },
                                    ) {
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }
                        }

                        item { SectionHeader(title = "热门排行") }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(HomeStore.ranking.filterNot { BlockTagsStore.isBlocked(it) }.take(30)) { comic ->
                                    ComicCard(
                                        comic = comic,
                                        modifier = Modifier.width(108.dp),
                                        onClick = onComicClick,
                                    )
                                }
                            }
                        }

                        item { SectionHeader(title = "最新上架") }
                        items(HomeStore.latest.filterNot { BlockTagsStore.isBlocked(it) }.chunked(3)) { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                row.forEach { comic ->
                                    ComicCard(
                                        comic = comic,
                                        modifier = Modifier.weight(1f),
                                        onClick = onComicClick,
                                    )
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }

                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (HomeStore.latestLoading) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                }
                                PageBar(
                                    page = HomeStore.latestPage,
                                    onPageChange = { p -> scope.launch { HomeStore.loadLatestPage(p) } },
                                )
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    actionText: String? = null,
    loading: Boolean = false,
    onAction: (() -> Unit)? = null,
    actionText2: String? = null,
    onAction2: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(18.dp),
                strokeWidth = 2.dp,
            )
        } else if (actionText != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionText) }
        }
        if (actionText2 != null && onAction2 != null) {
            TextButton(onClick = onAction2) { Text(actionText2) }
        }
    }
}
