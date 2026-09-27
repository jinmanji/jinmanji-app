package com.mobai.jm.ui.reader

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mobai.jm.data.Episode
import com.mobai.jm.data.JmApi
import com.mobai.jm.data.PagesCache
import com.mobai.jm.ui.components.SlimTopBar
import com.mobai.jm.util.AppPrefs
import com.mobai.jm.util.DiagLog
import com.mobai.jm.util.PageLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 阅读器参数 */
data class ReaderArgs(
    val albumId: String,
    val albumTitle: String,
    val episodes: List<Episode>,
    val startIndex: Int,
    val startPage: Int = 0,
)

/**
 * 阅读器：懒加载 + 恒预加载后 5 页 + 双指任意缩放（1x~5x，双击缩放/还原）
 *  - 页面走 PageLoader：原图下载 → 原尺寸解密 → 缩放（避免"横线"错位）
 *  - 章节列表缓存（秒开）+ 预加载下一章目录与前 5 页
 *  - 缩放平移边界限制（不会拖出屏幕露白）
 */
@Composable
fun ReaderScreen(args: ReaderArgs, onBack: () -> Unit) {
    val context = LocalContext.current
    val autoCache = remember { AppPrefs(context).autoCache }
    val targetWidth = remember { context.resources.displayMetrics.widthPixels }
    val listState = rememberLazyListState()
    var horizontal by remember { mutableStateOf(AppPrefs(context).readerHorizontal) }
    val prefetchScope = rememberCoroutineScope()
    val prefetched = remember { mutableSetOf<String>() }

    var chapterIndex by remember {
        mutableStateOf(args.startIndex.coerceIn(0, maxOf(args.episodes.lastIndex, 0)))
    }
    var pages by remember { mutableStateOf<List<String>>(emptyList()) }
    var scrambleId by remember { mutableStateOf(220980) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableStateOf(0) }
    var currentPage by remember { mutableStateOf(0) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    val chapter: Episode? = args.episodes.getOrNull(chapterIndex)

    fun clampOffset(candidate: Offset, s: Float): Offset {
        if (s <= 1f || viewport == IntSize.Zero) return Offset.Zero
        val maxX = (s - 1f) * viewport.width / 2f
        val maxY = (s - 1f) * viewport.height / 2f
        return Offset(
            candidate.x.coerceIn(-maxX, maxX),
            candidate.y.coerceIn(-maxY, maxY),
        )
    }

    fun prefetchAhead(fromIndex: Int) {
        if (!autoCache) return
        val photoId = chapter?.id ?: return
        if (pages.isEmpty()) return
        val last = minOf(pages.lastIndex, fromIndex + 5)
        for (i in (fromIndex + 1)..last) {
            if (i !in pages.indices) continue
            val filename = pages[i]
            val key = "$photoId/$filename@$scrambleId"
            if (!prefetched.add(key)) continue
            prefetchScope.launch {
                PageLoader.load(context, photoId, filename, scrambleId, targetWidth)
            }
        }
    }

    BackHandler(onBack = onBack)

    LaunchedEffect(chapterIndex, reloadTick) {
        val ep = chapter
        if (ep == null) {
            error = "章节不存在"
            loading = false
            return@LaunchedEffect
        }
        error = null
        pages = emptyList()
        prefetched.clear()
        scrambleId = 220980

        val t0 = System.currentTimeMillis()
        // 1) 秒开：章节图片列表缓存
        val initialTarget = if (chapterIndex == args.startIndex) args.startPage else 0
        val cached = PagesCache.load(ep.id)
        if (cached != null && cached.isNotEmpty()) {
            pages = cached
            loading = false
            val target = initialTarget.coerceIn(0, (cached.size - 1).coerceAtLeast(0))
            currentPage = target
            listState.scrollToItem(target)
            prefetchAhead(target)
            DiagLog.d("reader 来自缓存: ${ep.id} pages=${cached.size}")
        } else {
            loading = true
        }

        // 2) 网络刷新（失败保留缓存；无缓存才报错）
        try {
            val fresh = JmApi.chapterImages(ep.id)
            if (fresh != cached) {
                pages = fresh
                PagesCache.save(ep.id, fresh)
                val target = initialTarget.coerceIn(0, (fresh.size - 1).coerceAtLeast(0))
                currentPage = target
                listState.scrollToItem(target)
                prefetched.clear()
                prefetchAhead(target)
            }
            loading = false
            DiagLog.d("reader pages ok(${System.currentTimeMillis() - t0}ms): chapter=${ep.id} pages=${fresh.size}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cached == null || cached.isEmpty()) {
                error = e.message ?: "加载失败"
                loading = false
                DiagLog.w("reader 加载失败", e)
                return@LaunchedEffect
            }
            loading = false
            DiagLog.w("reader 刷新失败（保留缓存）", e)
        }

        // 3) 乱序参数后台获取（默认 220980 与云端值算法等价）
        runCatching { JmApi.scrambleId(ep.id) }.onSuccess { sid ->
            if (sid != scrambleId) {
                scrambleId = sid
                prefetched.clear()
                prefetchAhead(currentPage)
                DiagLog.d("reader scramble updated: ${ep.id} -> $sid")
            }
        }

        // 4) 预加载下一章（目录 + 前 5 页），不影响当前阅读
        if (autoCache) {
            val next = args.episodes.getOrNull(chapterIndex + 1)
            if (next != null) {
                runCatching {
                    val nextPages = PagesCache.load(next.id)
                        ?: JmApi.chapterImages(next.id).also { PagesCache.save(next.id, it) }
                    val nextSid = JmApi.scrambleId(next.id)
                    nextPages.take(5).forEach { fn ->
                        PageLoader.load(context, next.id, fn, nextSid, targetWidth)
                    }
                    DiagLog.d("preload next chapter: ${next.id} (+5 页)")
                }
            }
        }
    }

    // 滚动时更新页码并预加载后 5 页
    LaunchedEffect(listState, chapterIndex) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                currentPage = index
                prefetchAhead(index)
            }
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        SlimTopBar(
            title = chapter?.title?.ifBlank { "第 ${chapterIndex + 1} 話" } ?: args.albumTitle,
            onBack = onBack,
            actions = {
                if (!horizontal && scale > 1.01f) {
                    Text(
                        text = "${"%.1f".format(scale)}x · 重置",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                scale = 1f
                                offset = Offset.Zero
                            }
                            .padding(end = 12.dp),
                    )
                }
                if (pages.isNotEmpty()) {
                    Text(
                        text = if (horizontal) "横向" else "纵向",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                horizontal = !horizontal
                                AppPrefs(context).readerHorizontal = horizontal
                            }
                            .padding(end = 12.dp),
                    )

                    Text(
                        text = "${(currentPage + 1).coerceAtMost(pages.size)}/${pages.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                }
            },
        )

        when {
            loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            error != null -> {
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

            horizontal -> {
                val pagerState = rememberPagerState { pages.size.coerceAtLeast(1) }
                LaunchedEffect(pages) {
                    if (pages.isNotEmpty()) {
                        pagerState.scrollToPage(currentPage.coerceIn(0, pages.lastIndex))
                    }
                }
                LaunchedEffect(pagerState, chapterIndex) {
                    snapshotFlow { pagerState.currentPage }
                        .distinctUntilChanged()
                        .collect { idx ->
                            currentPage = idx
                            prefetchAhead(idx)
                        }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                ) { idx ->
                    val photoId = chapter?.id.orEmpty()
                    var retryTick by remember(pages.getOrNull(idx), chapterIndex) { mutableStateOf(0) }
                    ReaderPage(
                        context = context,
                        photoId = photoId,
                        filename = pages.getOrNull(idx).orEmpty(),
                        scrambleId = scrambleId,
                        targetWidth = targetWidth,
                        index = idx,
                        retryKey = retryTick,
                        fitScreen = true,
                        onRetry = { retryTick++ },
                    )
                }
            }

            else -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .onSizeChanged { viewport = it }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var zooming = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.none { it.pressed }) break
                                    val pressedCount = event.changes.count { it.pressed }
                                    if (pressedCount >= 2) {
                                        zooming = true
                                        val zoomChange = event.calculateZoom()
                                        val panChange = event.calculatePan()
                                        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                                        var newOffset = if (newScale > 1f) offset + panChange else Offset.Zero
                                        newOffset = clampOffset(newOffset, newScale)
                                        scale = newScale
                                        offset = newOffset
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    } else if (zooming || scale > 1f) {
                                        val panChange = event.calculatePan()
                                        if (panChange != Offset.Zero) {
                                            offset = clampOffset(offset + panChange, scale)
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        }
                                    }
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { _ ->
                                if (scale > 1.01f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2f
                                }
                            })
                        },
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                    ) {
                        itemsIndexed(pages) { index, filename ->
                            val photoId = chapter?.id.orEmpty()
                            var retryTick by remember(filename) { mutableStateOf(0) }
                            ReaderPage(
                                context = context,
                                photoId = photoId,
                                filename = filename,
                                scrambleId = scrambleId,
                                targetWidth = targetWidth,
                                index = index,
                                retryKey = retryTick,
                                onRetry = { retryTick++ },
                            )
                        }
                        item {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    "—— 本章完 ——",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (chapterIndex > 0) {
                                        TextButton(onClick = { chapterIndex -= 1 }) { Text("上一章") }
                                    }
                                    if (chapterIndex < args.episodes.lastIndex) {
                                        Button(onClick = { chapterIndex += 1 }) { Text("下一章") }
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(48.dp)) }
                    }
                }
            }
        }
    }
}

private sealed interface PageState {
    data object Loading : PageState
    class Done(val bitmap: Bitmap) : PageState
    data object Failed : PageState
}

@Composable
private fun ReaderPage(
    context: android.content.Context,
    photoId: String,
    filename: String,
    scrambleId: Int,
    targetWidth: Int,
    index: Int,
    retryKey: Int,
    fitScreen: Boolean = false,
    onRetry: () -> Unit,
) {
    val state by produceState<PageState>(
        PageState.Loading,
        filename, scrambleId, retryKey, targetWidth,
    ) {
        value = try {
            val bmp = PageLoader.load(context, photoId, filename, scrambleId, targetWidth)
            if (bmp != null) PageState.Done(bmp) else PageState.Failed
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            PageState.Failed
        }
    }

    val boxMod = if (fitScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(0.7f)

    when (val s = state) {
        is PageState.Loading -> {
            Box(
                boxMod.background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            }
        }

        is PageState.Failed -> {
            Box(
                boxMod.background(MaterialTheme.colorScheme.errorContainer)
                    .clickable { onRetry() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "加载失败，点按重试（自动切换线路）",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        is PageState.Done -> {
            Image(
                bitmap = s.bitmap.asImageBitmap(),
                contentDescription = "第 ${index + 1} 页",
                contentScale = if (fitScreen) ContentScale.Fit else ContentScale.FillWidth,
                modifier = if (fitScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            )
        }
    }
}
