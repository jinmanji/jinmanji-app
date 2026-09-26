package com.mobai.jm.ui.album

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mobai.jm.R
import com.mobai.jm.data.AlbumCache
import com.mobai.jm.data.FavStore
import com.mobai.jm.data.HistoryStore
import com.mobai.jm.data.JmAlbum
import com.mobai.jm.data.JmApi
import com.mobai.jm.data.PagesCache
import com.mobai.jm.data.TagStats
import com.mobai.jm.data.TagStore
import com.mobai.jm.download.DownloadQueue
import com.mobai.jm.ui.components.SlimTopBar
import com.mobai.jm.util.LangDetect
import com.mobai.jm.util.PageLoader
import com.mobai.jm.util.DiagLog
import kotlin.math.min

/**
 * 本子详情：
 *  - 封面右上角语言国旗；标题两行；车牌号/JM/复制链接(官方图标)
 *  - 右下角收藏 FAB；「开始阅读」「下载」两个按钮
 *  - 点击标签跳搜索；长按标签收藏/取消收藏
 *  - 下方内容预览（3 列平铺，懒加载）
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun AlbumDetailScreen(
    albumId: String,
    onBack: () -> Unit,
    onOpenChapter: (JmAlbum, Int, Int) -> Unit,
    onTagSearch: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var album by remember { mutableStateOf<JmAlbum?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableStateOf(0) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var dlFmt by remember { mutableStateOf("pdf") }
    var dlPdfQ by remember { mutableStateOf("visual") }
    var dlLevel by remember { mutableStateOf(3) }
    var dlPwdMode by remember { mutableStateOf("none") }
    var dlFixedPwd by remember { mutableStateOf("") }
    var dlStrategy by remember { mutableStateOf("append") }
    var dlRemember by remember { mutableStateOf(false) }
    var dlPick by remember { mutableStateOf<String?>(null) }
    var dlImgQ by remember { mutableStateOf("visual") }

    fun startDownload(a: JmAlbum) {
        DownloadQueue.enqueue(context, a)
        TagStats.onDownload(a)
        Toast.makeText(context, "已加入下载队列", Toast.LENGTH_SHORT).show()
    }
    var previewPages by remember { mutableStateOf<List<String>>(emptyList()) }
    var previewSid by remember { mutableStateOf(220980) }

    val isFav = album?.let { FavStore.contains(it.id) } ?: false
    val dlTask = album?.let { a -> DownloadQueue.tasks.firstOrNull { it.albumId == a.id } }

    fun copyText(text: String, label: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "已复制$label", Toast.LENGTH_SHORT).show()
    }

    BackHandler(onBack = onBack)

    LaunchedEffect(albumId, reloadTick) {
        if (album == null) {
            AlbumCache.load(albumId)?.let {
                album = it
                loading = false
                DiagLog.d("album 来自缓存: $albumId")
            }
        }
        try {
            val fresh = JmApi.album(albumId)
            album = fresh
            error = null
            AlbumCache.save(fresh)
            DiagLog.d("album ok: ${fresh.name} chapters=${fresh.episodes.size}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (album == null) {
                error = e.message ?: "加载失败"
                DiagLog.w("album 加载失败", e)
            } else {
                DiagLog.w("album 刷新失败（已保留缓存）", e)
            }
        } finally {
            loading = false
        }

        // 记录浏览历史（可在设置中关闭）
        album?.let { HistoryStore.record(it, JmApi.albumThumbUrl(it.id)) }
        // 用真实标签校准国旗/全彩
        album?.let { TagStore.remember(it) }
    }

    // 内容预览：第一话前 60 页（列表优先走缓存）
    LaunchedEffect(album?.id) {
        val a = album ?: return@LaunchedEffect
        val first = a.episodes.firstOrNull() ?: return@LaunchedEffect
        val cached = PagesCache.load(first.id)
        if (cached != null && cached.isNotEmpty()) {
            previewPages = cached
        } else {
            runCatching { JmApi.chapterImages(first.id) }.getOrNull()?.let {
                PagesCache.save(first.id, it)
                previewPages = it
            }
        }
        previewSid = runCatching { JmApi.scrambleId(first.id) }.getOrDefault(220980)

        // 缩略图预热：前 15 页顺序预取（间隔让路给可见项，滚动到后面不空白）
        runCatching {
            for (fn in previewPages.take(15)) {
                PageLoader.load(context, first.id, fn, previewSid, targetWidth = 360)
                kotlinx.coroutines.delay(80)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            SlimTopBar(title = album?.name ?: "详情", onBack = onBack)

            val current = album
            when {
                loading && current == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                error != null && current == null -> {
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

                current != null -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            AlbumHeader(
                                album = current,
                                onCopy = ::copyText,
                                onTagSearch = onTagSearch,
                                onTagLongPress = { tag ->
                                    val added = FavStore.toggleTag(tag)
                                    Toast.makeText(
                                        context,
                                        if (added) "已收藏标签：$tag（可在收藏页查看）" else "已取消收藏标签",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                        }
                        item {
                            ActionRow(
                                dlTask = dlTask,
                                onRead = {
                                    TagStats.onRead(current)
                                    onOpenChapter(current, 0, 0)
                                },
                                onDownload = {
                                    val p0 = com.mobai.jm.util.AppPrefs(context)
                                    if (p0.downloadRemember) {
                                        album?.let { startDownload(it) }
                                    } else {
                                        dlFmt = p0.downloadFormat
                                        dlPdfQ = p0.pdfPreset
                                        dlLevel = p0.archiveLevel
                                        dlPwdMode = p0.archivePwdMode
                                        dlFixedPwd = p0.archiveFixedPwd
                                        dlStrategy = p0.archivePwdStrategy
                                        dlImgQ = p0.archiveImgQuality
                                        dlRemember = false
                                        showDownloadDialog = true
                                    }
                                },
                            )
                        }
                        item {
                            Text(
                                "章节 (${current.episodes.size})",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                            )
                        }
                        itemsIndexed(current.episodes) { index, episode ->
                            val episodeName = episode.title.ifBlank { "第 ${index + 1} 話" }
                            ListItem(
                                headlineContent = {
                                    Text(episodeName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                leadingContent = {
                                    Text(
                                        "${index + 1}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                trailingContent = {
                                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
                                },
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        TagStats.onRead(current)
                                        onOpenChapter(current, index, 0)
                                    },
                                    onLongClick = { copyText(episodeName, "章节名") },
                                ),
                            )
                            HorizontalDivider(Modifier.padding(start = 16.dp))
                        }

                        if (previewPages.isNotEmpty()) {
                            val firstChapterId = current.episodes.first().id
                            val shown = previewPages.take(60)
                            item {
                                Text(
                                    "内容预览（第 1 話 · 前 ${shown.size} 页）",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
                                )
                            }
                            itemsIndexed(shown.chunked(3)) { rowIndex, rowItems ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 3.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    rowItems.forEachIndexed { colIndex, fn ->
                                        val pageIndex = rowIndex * 3 + colIndex
                                        val bitmap by produceState<Bitmap?>(null, fn, previewSid) {
                                            value = PageLoader.load(
                                                context,
                                                firstChapterId,
                                                fn,
                                                previewSid,
                                                targetWidth = 360,
                                            )
                                        }
                                        Box(
                                            Modifier
                                                .weight(1f)
                                                .aspectRatio(0.7f)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                                .clickable {
                                                    onOpenChapter(current, 0, pageIndex)
                                                },
                                        ) {
                                            val bmp = bitmap
                                            if (bmp != null) {
                                                Image(
                                                    bitmap = bmp.asImageBitmap(),
                                                    contentDescription = "第 ${pageIndex + 1} 页",
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            }
                                        }
                                    }
                                    repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }

        if (album != null) {
            FloatingActionButton(
                onClick = {
                    val a = album ?: return@FloatingActionButton
                    val added = FavStore.toggle(a, JmApi.albumCoverUrl(a.id))
                    if (added) TagStats.onFavorite(a)
                    Toast.makeText(
                        context,
                        if (added) "已加入收藏" else "已取消收藏",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(20.dp),
                containerColor = if (isFav) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (isFav) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Icon(
                    imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "收藏",
                )
            }
        }
    }

    if (showDownloadDialog && album != null) {
        val a = album!!
        val radioRow: @Composable (Boolean, String, () -> Unit) -> Unit = { sel, label, onSel ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSel),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.RadioButton(selected = sel, onClick = onSel)
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        val sectionTitle: @Composable (String) -> Unit = { t ->
            Text(
                t,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = { Text("下载设置") },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(max = 440.dp),
                ) {
                    Text(
                        "格式：" + when (dlFmt) {
                            "zip" -> "ZIP"
                            "7z" -> "7z"
                            "targz" -> "tar.gz"
                            else -> "PDF"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val detail = when {
                        dlFmt == "pdf" -> "图像质量：" + when (dlPdfQ) {
                            "max" -> "最高（原始分辨率）"
                            "compact" -> "压缩（宽 ≤1240）"
                            else -> "视觉无损（原始分辨率）"
                        }
                        dlFmt == "7z" -> "压缩级别：" +
                            com.mobai.jm.util.ArchiveWriter.LEVEL_NAMES[dlLevel.coerceIn(0, 5)] +
                            " · 图像：" + when (dlImgQ) {
                                "raw" -> "原样直存"
                                "lossless" -> "像素无损"
                                "compact" -> "压缩优先"
                                else -> "视觉无损"
                            } +
                            " · 密码：" + when (dlPwdMode) {
                                "fixed" -> "固定密码"
                                "random" -> if (dlStrategy == "twolevel") "随机（二级压缩包）" else "随机（写入文件名）"
                                else -> "不使用"
                            }
                        else -> "压缩级别：" +
                            com.mobai.jm.util.ArchiveWriter.LEVEL_NAMES[dlLevel.coerceIn(0, 5)] +
                            " · 图像：" + when (dlImgQ) {
                                "raw" -> "原样直存"
                                "lossless" -> "像素无损"
                                "compact" -> "压缩优先"
                                else -> "视觉无损"
                            }
                    }
                    Text(detail, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "可在「设置 → 下载」中修改",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { dlRemember = !dlRemember },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = dlRemember,
                            onCheckedChange = { dlRemember = it },
                        )
                        Text("以后不再弹出", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = com.mobai.jm.util.AppPrefs(context)
                    p.downloadFormat = dlFmt
                    p.pdfPreset = dlPdfQ
                    p.archiveLevel = dlLevel
                    p.archivePwdMode = dlPwdMode
                    p.archiveFixedPwd = dlFixedPwd.trim()
                    p.archivePwdStrategy = dlStrategy
                    if (dlRemember) p.downloadRemember = true
                    showDownloadDialog = false
                    DownloadQueue.enqueue(context, a)
                    TagStats.onDownload(a)
                    val fmtLabel = when (dlFmt) {
                        "zip" -> "ZIP"
                        "7z" -> "7z"
                        "targz" -> "tar.gz"
                        else -> "PDF"
                    }
                    Toast.makeText(
                        context,
                        "已加入下载队列（$fmtLabel，当前队列 ${DownloadQueue.tasks.count { it.state == DownloadQueue.State.QUEUED || it.state == DownloadQueue.State.RUNNING }} 个）",
                        Toast.LENGTH_SHORT,
                    ).show()
                }) { Text("开始下载") }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadDialog = false }) { Text("取消") }
            },
        )
    }

    // 候选项选择器（下拉式）
    when (dlPick) {
        "fmt" -> DownloadPickDialog(
            "格式",
            listOf("pdf" to "PDF", "zip" to "ZIP", "7z" to "7z", "targz" to "tar.gz"),
            dlFmt,
            { dlFmt = it },
            { dlPick = null },
        )
        "pdfq" -> DownloadPickDialog(
            "图像质量",
            listOf(
                "visual" to "视觉无损（原始分辨率）",
                "max" to "最高（原始分辨率）",
                "compact" to "压缩（宽 ≤1240）",
            ),
            dlPdfQ,
            { dlPdfQ = it },
            { dlPick = null },
        )
        "level" -> DownloadPickDialog(
            "压缩级别",
            com.mobai.jm.util.ArchiveWriter.LEVEL_NAMES.mapIndexed { i, n -> i.toString() to n },
            dlLevel.toString(),
            { dlLevel = it.toIntOrNull() ?: 3 },
            { dlPick = null },
        )
        "pwd" -> DownloadPickDialog(
            "密码",
            listOf("none" to "不使用", "fixed" to "固定密码", "random" to "随机密码"),
            dlPwdMode,
            { dlPwdMode = it },
            { dlPick = null },
        )
        "strat" -> DownloadPickDialog(
            "随机密码方式",
            listOf("append" to "密码写入文件名", "twolevel" to "二级压缩包（内附解压密码.txt）"),
            dlStrategy,
            { dlStrategy = it },
            { dlPick = null },
        )
        else -> {}
    }
}

@Composable
private fun DownloadPickDialog(
    title: String,
    options: List<Pair<String, String>>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (v, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPick(v)
                                onDismiss()
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = current == v,
                            onClick = {
                                onPick(v)
                                onDismiss()
                            },
                        )
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun AlbumHeader(
    album: JmAlbum,
    onCopy: (String, String) -> Unit,
    onTagSearch: (String) -> Unit,
    onTagLongPress: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Row {
            Box {
                AsyncImage(
                    model = JmApi.albumCoverUrl(album.id),
                    contentDescription = album.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(110.dp)
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(10.dp)),
                )
                val flag = LangDetect.emoji(album.name, album.tags)
                if (flag != null) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color.Black.copy(alpha = 0.55f),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp),
                    ) {
                        Text(
                            text = flag,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { onCopy(album.name, "标题") },
                            ),
                    )
                    IconButton(onClick = {
                        onCopy("https://18comic.vip/photo/${album.id}", "链接")
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_link),
                            contentDescription = "复制链接",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (album.author.isNotEmpty()) {
                    Text(
                        text = album.author.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.combinedClickable(
                            onClick = { album.author.firstOrNull()?.let { onTagSearch(it) } },
                            onLongClick = { onCopy(album.author.joinToString(", "), "作者") },
                        ),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.combinedClickable(
                            onClick = { onCopy(album.id, "车牌号") },
                            onLongClick = { onCopy(album.id, "车牌号") },
                        ),
                    ) {
                        Text(
                            text = "JM${album.id}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "❤️ ${album.likes} · 👀 ${album.views}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (album.tags.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                album.tags.take(14).forEach { tag ->
                    val tagFav = FavStore.favTags.contains(tag)
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (tagFav) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.combinedClickable(
                            onClick = { onTagSearch(tag) },
                            onLongClick = { onTagLongPress(tag) },
                        ),
                    ) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (tagFav) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    dlTask: DownloadQueue.Task?,
    onRead: () -> Unit,
    onDownload: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onRead, modifier = Modifier.weight(1f)) {
                Text("开始阅读")
            }
            Button(
                onClick = onDownload,
                enabled = dlTask?.state != DownloadQueue.State.RUNNING &&
                    dlTask?.state != DownloadQueue.State.QUEUED,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (dlTask?.state == DownloadQueue.State.RUNNING ||
                        dlTask?.state == DownloadQueue.State.QUEUED
                    ) "已加入队列" else "下载"
                )
            }
        }
        val status = when (dlTask?.state) {
            DownloadQueue.State.QUEUED -> "排队中…（下载队列会逐个处理）"
            DownloadQueue.State.RUNNING -> if (dlTask?.exporting == true) {
                "导出中 ${dlTask?.exportDone}/${dlTask?.exportTotal}"
            } else {
                "下载中 ${dlTask?.done}/${dlTask?.total}（失败 ${dlTask?.failedPages} 页）"
            }
            DownloadQueue.State.DONE -> "已保存：${dlTask.savedTo}"
            DownloadQueue.State.FAILED -> "下载失败：${dlTask.error}"
            DownloadQueue.State.CANCELED -> "已取消"
            null -> null
        }
        if (status != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 根据标题/标签猜测语言 → emoji 国旗 */
private fun guessLanguageEmoji(name: String, tags: List<String>): String? {
    val hay = (name + " " + tags.joinToString(" ")).lowercase()
    return when {
        listOf("中文", "漢化", "汉化", "中字", "中國翻譯", "中文翻译", "chinese", "china").any { it in hay } -> "🇨🇳"
        listOf("日本語", "日語", "日文", "japanese", "生肉", "raw").any { it in hay } -> "🇯🇵"
        listOf("english", "英文", "英譯", "英译").any { it in hay } -> "🇺🇸"
        listOf("한국", "한글", "korean", "韓文", "韩文").any { it in hay } -> "🇰🇷"
        else -> null
    }
}
