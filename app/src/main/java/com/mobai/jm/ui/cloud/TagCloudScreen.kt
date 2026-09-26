package com.mobai.jm.ui.cloud

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobai.jm.data.TagStats
import com.mobai.jm.ui.components.SlimTopBar
import kotlin.math.sqrt

/** 标签词云：统计阅读/收藏/下载过的本子标签，字号随出现次数变化 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagCloudScreen(onBack: () -> Unit, onTagClick: (String) -> Unit) {
    val tags = TagStats.topTags(80)

    BackHandler(onBack = onBack)

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        SlimTopBar(title = "标签词云", onBack = onBack)

        if (tags.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "还没有数据～\n阅读、收藏或下载本子后，这里会生成你的口味词云",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            val max = tags.first().second.coerceAtLeast(1)
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tags.forEachIndexed { index, (tag, count) ->
                        val ratio = sqrt(count.toFloat() / max)
                        val fontSize = (13f + 17f * ratio).sp
                        val colors = when (index % 3) {
                            0 -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                            1 -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = colors.first,
                            modifier = Modifier.clickable { onTagClick(tag) },
                        ) {
                            Text(
                                text = tag,
                                fontSize = fontSize,
                                color = colors.second,
                                fontWeight = if (ratio > 0.66f) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
