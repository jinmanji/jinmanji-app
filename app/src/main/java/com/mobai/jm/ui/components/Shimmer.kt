package com.mobai.jm.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

/** 图像加载占位：呼吸式闪烁块（图出来后自动被覆盖） */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.40f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "shimmerAlpha",
    )
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)))
}
