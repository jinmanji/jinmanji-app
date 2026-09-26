package com.mobai.jm.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mobai.jm.util.net.MasqueManager
import com.mobai.jm.util.net.TunnelStats
import com.mobai.jm.util.net.fmtBytes
import com.mobai.jm.util.net.fmtSpeed

/**
 * 隧道实时 HUD（页眉右上角）：
 *   延迟 · 速度 · 累计流量
 * 仅在 WARP 隧道运行时显示；点击查看详情（本次会话流量）。
 */
@Composable
fun TunnelHud(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (!MasqueManager.isRunning) return
    val prefs = androidx.compose.runtime.remember { com.mobai.jm.util.AppPrefs(context) }
    if (!prefs.hudEnabled) return
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f),
        modifier = modifier.clickable {
            Toast.makeText(
                context,
                "隧道：${MasqueManager.lastMessage}\n" +
                    "延迟：${if (TunnelStats.latencyMs >= 0) "${TunnelStats.latencyMs} ms" else "测量中…"}\n" +
                    "本次会话：${fmtBytes(TunnelStats.sessionBytes)}\n" +
                    "累计总量：${fmtBytes(TunnelStats.totalBytes)}（设置页可清零）",
                Toast.LENGTH_LONG,
            ).show()
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (TunnelStats.latencyMs >= 0) "${TunnelStats.latencyMs}ms" else "…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = "↓${fmtSpeed(TunnelStats.speedBps)}",
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = "Σ${fmtBytes(TunnelStats.totalBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
