package com.mobai.jm.ui.comments

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobai.jm.data.JmApi
import com.mobai.jm.data.JmComment
import com.mobai.jm.ui.components.PageBar
import com.mobai.jm.ui.components.SlimTopBar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 评论区（只读）：移动端 /forum 接口，每页约 10 条。
 * 支持：翻页、剧透遮罩（点按显示）、点赞数展示。
 */
@Composable
fun CommentsScreen(albumId: String, albumTitle: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<JmComment>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val revealed = remember { mutableStateMapOf<String, Boolean>() }

    fun load(p: Int) {
        scope.launch {
            loading = true
            error = null
            try {
                val pair = JmApi.comments(albumId, p)
                total = pair.first
                list = pair.second
                page = p
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(albumId) { load(1) }

    val pageCount = if (total <= 0) 1 else (total + 9) / 10

    Column(Modifier.fillMaxSize()) {
        SlimTopBar(
            title = "评论 · " + albumTitle.take(16),
            onBack = onBack,
        )
        when {
            loading && list.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            error != null && list.isEmpty() -> {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { load(1) }) { Text("重试") }
                }
            }

            list.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "还没有评论",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            "共 $total 条 · 第 $page 页" + if (error != null) " · $error" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(list) { c ->
                        CommentCard(c, revealed)
                    }
                    item {
                        PageBar(
                            page = page,
                            pageCount = pageCount,
                            hasNext = page < pageCount,
                            onPageChange = { load(it) },
                        )
                    }
                }
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun CommentCard(c: JmComment, revealed: MutableMap<String, Boolean>) {
    val show = revealed[c.id] == true
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                c.nickname.ifBlank { "匿名" },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (c.parentId.isNotBlank() && c.parentId != "0") {
                Text(
                    "回复",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text(
                formatTime(c.time),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "♥ " + c.likes.ifBlank { "0" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        if (c.spoiler && !show) {
            Text(
                "包含剧透，点按查看",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { revealed[c.id] = true }
                    .padding(vertical = 10.dp),
            )
        } else {
            Text(
                c.content.ifBlank { "（空）" },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun formatTime(raw: String): String {
    val sec = raw.toLongOrNull() ?: return raw.take(16)
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(sec * 1000))
    }.getOrDefault(raw)
}
