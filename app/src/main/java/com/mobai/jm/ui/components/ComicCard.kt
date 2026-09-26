package com.mobai.jm.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mobai.jm.data.Comic
import com.mobai.jm.data.JmApi
import com.mobai.jm.data.TagStore
import com.mobai.jm.util.LangDetect

/** 封面卡片：3:4 封面（右上角语言国旗 + 加载占位动画）+ 标题（两行）+ 作者（可点搜索/长按） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComicCard(
    comic: Comic,
    modifier: Modifier = Modifier,
    onClick: (Comic) -> Unit = {},
    onLongClick: ((Comic) -> Unit)? = null,
    onAuthorClick: ((String) -> Unit)? = null,
) {
    var loaded by remember(comic.id, comic.coverUrl) { mutableStateOf(false) }
    Column(
        modifier = modifier.combinedClickable(
            onClick = { onClick(comic) },
            onLongClick = onLongClick?.let { cb -> { cb(comic) } },
        ),
    ) {
        Box {
            val shape = RoundedCornerShape(10.dp)
            AsyncImage(
                model = comic.coverUrl.ifBlank { JmApi.albumThumbUrl(comic.id) },
                contentDescription = comic.title,
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                onError = { loaded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(shape),
            )
            // 加载占位动画（图出来后自动让位）
            if (!loaded) {
                ShimmerBox(
                    Modifier
                        .matchParentSize()
                        .clip(shape),
                )
            }
            val flag = TagStore.flagFor(comic)
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
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = comic.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = comic.author.ifBlank { " " },
            style = MaterialTheme.typography.labelSmall,
            color = if (onAuthorClick != null) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (onAuthorClick != null) {
                Modifier.clickable { onAuthorClick(comic.author) }
            } else {
                Modifier
            },
        )
    }
}
