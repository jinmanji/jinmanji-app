package com.mobai.jm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import com.mobai.jm.data.CategoryStore
import com.mobai.jm.data.HomeStore
import com.mobai.jm.ui.album.AlbumDetailScreen
import com.mobai.jm.ui.components.TunnelHud
import com.mobai.jm.ui.category.CategoryScreen
import com.mobai.jm.ui.cloud.TagCloudScreen
import com.mobai.jm.ui.downloads.DownloadsScreen
import com.mobai.jm.ui.favorites.FavoritesScreen
import com.mobai.jm.ui.history.HistoryScreen
import com.mobai.jm.ui.home.HomeScreen
import com.mobai.jm.ui.random.RandomScreen
import com.mobai.jm.ui.reader.ReaderArgs
import com.mobai.jm.ui.reader.ReaderScreen
import com.mobai.jm.ui.search.SearchRequest
import com.mobai.jm.ui.search.SearchScreen
import com.mobai.jm.ui.settings.SettingsScreen
import com.mobai.jm.util.NavSignals
import com.mobai.jm.util.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun MoBaiApp(mode: ThemeMode, onModeChange: (ThemeMode) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var albumId by remember { mutableStateOf<String?>(null) }
    var readerArgs by remember { mutableStateOf<ReaderArgs?>(null) }
    var searchRequest by remember { mutableStateOf<SearchRequest?>(null) }
    var showRandom by remember { mutableStateOf(false) }
    var showCloud by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val downloadsSignal by NavSignals.openDownloads.collectAsState()
    LaunchedEffect(downloadsSignal) {
        if (downloadsSignal > 0) {
            readerArgs = null
            albumId = null
            showRandom = false
            showCloud = false
            showHistory = false
            showDownloads = true
        }
    }

    // 覆盖层兜底返回（各页面自己也有 BackHandler，这里是双保险）
    BackHandler(enabled = showRandom || showCloud || showDownloads || showHistory) {
        when {
            showDownloads -> showDownloads = false
            showHistory -> showHistory = false
            showCloud -> showCloud = false
            showRandom -> showRandom = false
        }
    }
    // 标签页返回：不在「发现」时先回到「发现」
    BackHandler(enabled = tab != 0) { tab = 0 }

    // 启动门控：配置了自动连接隧道时，先等隧道就绪/失败，再加载资源
    val gateState = com.mobai.jm.util.StartupGate.state
    if (gateState !is com.mobai.jm.util.StartupGate.State.Ready) {
        val gateCtx = androidx.compose.ui.platform.LocalContext.current
        StartupGateScreen(
            state = gateState,
            onRetry = { scope.launch { com.mobai.jm.util.net.MasqueManager.retryStartup(gateCtx) } },
            onSkip = { com.mobai.jm.util.StartupGate.ready() },
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
        // ───────── 主界面：始终保留在组合中 ─────────
        // （这样进入详情/阅读器再返回时，各列表的滚动位置、搜索结果、分类位置等都不会丢失）
        Scaffold(
            bottomBar = {
                SlimNavigationBar(
                    selected = tab,
                    onSelect = { t ->
                        if (t == tab && t == 0) {
                            // 已在首页，再点一次首页图标 = 刷新
                            scope.launch { HomeStore.load() }
                        } else {
                            tab = t
                        }
                    },
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when (tab) {
                    0 -> HomeScreen(
                        onComicClick = { albumId = it.id },
                        onOpenRandom = { showRandom = true },
                        onOpenCategory = { cat ->
                            CategoryStore.selected = cat
                            tab = 1
                        },
                        onSearchTerm = { term ->
                            searchRequest = SearchRequest(term, System.nanoTime())
                            tab = 2
                        },
                    )

                    1 -> CategoryScreen(
                        onComicClick = { albumId = it.id },
                        onSearchTerm = { term ->
                            searchRequest = SearchRequest(term, System.nanoTime())
                            tab = 2
                        },
                    )

                    2 -> SearchScreen(
                        request = searchRequest,
                        onOpenAlbum = { albumId = it },
                        onComicClick = { albumId = it.id },
                    )

                    3 -> FavoritesScreen(
                        onComicClick = { albumId = it.id },
                        onTagSearch = { tag ->
                            searchRequest = SearchRequest(tag, System.nanoTime())
                            tab = 2
                        },
                        onOpenCloud = { showCloud = true },
                    )

                    else -> SettingsScreen(
                        mode = mode,
                        onModeChange = onModeChange,
                        onOpenDownloads = { showDownloads = true },
                        onOpenHistory = { showHistory = true },
                        
                    )
                }
            }
        }

        // ───────── 覆盖层（从下往上堆叠；每层带触摸拦截板，防止点击穿透）─────────
        if (showCloud) {
            OverlayLayer {
                TagCloudScreen(
                    onBack = { showCloud = false },
                    onTagClick = { tag ->
                        showCloud = false
                        searchRequest = SearchRequest(tag, System.nanoTime())
                        tab = 2
                    },
                )
            }
        }

        if (showRandom) {
            OverlayLayer {
                RandomScreen(
                    onBack = { showRandom = false },
                    onComicClick = { albumId = it.id },
                    onSearchTerm = { term ->
                        searchRequest = SearchRequest(term, System.nanoTime())
                        tab = 2
                        showRandom = false
                    },
                )
            }
        }

        if (showHistory) {
            OverlayLayer {
                HistoryScreen(
                    onBack = { showHistory = false },
                    onComicClick = { albumId = it.id },
                )
            }
        }

        if (showDownloads) {
            OverlayLayer {
                DownloadsScreen(onBack = { showDownloads = false })
            }
        }

        val currentAlbum = albumId
        if (currentAlbum != null) {
            OverlayLayer {
                AlbumDetailScreen(
                    albumId = currentAlbum,
                    onBack = { albumId = null },
                    onOpenChapter = { album, index, page ->
                        readerArgs = ReaderArgs(
                            albumId = album.id,
                            albumTitle = album.name,
                            episodes = album.episodes,
                            startIndex = index,
                            startPage = page,
                        )
                    },
                    onTagSearch = { tag ->
                        albumId = null
                        // 从随机/历史/词云等覆盖层进入的详情：跳搜索时一并关闭覆盖层，否则搜索页会被盖住
                        showRandom = false
                        showHistory = false
                        showCloud = false
                        searchRequest = SearchRequest(tag, System.nanoTime())
                        tab = 2
                    },
                )
            }
        }

        val currentReader = readerArgs
        if (currentReader != null) {
            OverlayLayer {
                ReaderScreen(args = currentReader, onBack = { readerArgs = null })
            }
        }

        // 隧道实时 HUD（页眉右上角；阅读器打开时隐藏避免遮挡）
        if (albumId == null && readerArgs == null) {
            TunnelHud(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 10.dp, top = 2.dp),
            )
        }
    }
}

/** 覆盖层：不透明底 + 触摸拦截板（拦截板在下、内容在上；只拦截"漏网"的手势，不影响内容自身交互） */
@Composable
private fun OverlayLayer(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { ch ->
                                if (ch.positionChanged() || !ch.pressed) ch.consume()
                            }
                            if (event.changes.none { it.pressed }) break
                        }
                    }
                },
        )
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) {
            content()
        }
    }
}

/** 精简页脚：52dp（5 个标签） */
/** 启动门控页：隧道连接中 / 失败（失败即停止加载资源，可重试或跳过直连） */
@Composable
private fun StartupGateScreen(
    state: com.mobai.jm.util.StartupGate.State,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                when (state) {
                    is com.mobai.jm.util.StartupGate.State.Connecting -> {
                        androidx.compose.material3.CircularProgressIndicator()
                        Text("正在连接隧道…", style = MaterialTheme.typography.bodyMedium)
                    }

                    is com.mobai.jm.util.StartupGate.State.Failed -> {
                        Text("隧道连接失败", style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        androidx.compose.foundation.layout.Row {
                            androidx.compose.material3.TextButton(onClick = onRetry) { Text("重试") }
                            androidx.compose.material3.TextButton(onClick = onSkip) { Text("跳过（直连使用）") }
                        }
                    }

                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun SlimNavigationBar(selected: Int, onSelect: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(0, "发现", Icons.Default.Home, selected, onSelect)
            NavItem(1, "分类", Icons.Default.List, selected, onSelect)
            NavItem(2, "搜索", Icons.Default.Search, selected, onSelect)
            NavItem(3, "收藏", Icons.Default.Favorite, selected, onSelect)
            NavItem(4, "设置", Icons.Default.Settings, selected, onSelect)
        }
    }
}

@Composable
private fun RowScope.NavItem(
    index: Int,
    label: String,
    icon: ImageVector,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val active = selected == index
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable { onSelect(index) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
