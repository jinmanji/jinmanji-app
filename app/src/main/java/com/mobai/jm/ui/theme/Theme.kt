package com.mobai.jm.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.mobai.jm.util.ThemeMode

// ── 禁漫姬 · 品牌色（浅色：纸与石墨） ──
private val LightColors = lightColorScheme(
    primary = Color(0xFF3C4043),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE1E3E6),
    onPrimaryContainer = Color(0xFF191C1E),
    inversePrimary = Color(0xFFC6C6CA),
    secondary = Color(0xFF5D6166),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E3E7),
    onSecondaryContainer = Color(0xFF1A1C1E),
    tertiary = Color(0xFF6A5F5B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF1DFD9),
    onTertiaryContainer = Color(0xFF251A17),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAF9F7),
    onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFFAF9F7),
    onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFE3E2DE),
    onSurfaceVariant = Color(0xFF47464A),
    outline = Color(0xFF77787B),
    outlineVariant = Color(0xFFC7C6C9),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF303030),
    inverseOnSurface = Color(0xFFF2F0ED),
    surfaceDim = Color(0xFFDBDAD7),
    surfaceBright = Color(0xFFFAF9F7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F4F1),
    surfaceContainer = Color(0xFFEFEEEB),
    surfaceContainerHigh = Color(0xFFE9E8E5),
    surfaceContainerHighest = Color(0xFFE3E2DF),
)

// ── 禁漫姬 · 品牌色（深色：夜与浅墨） ──
private val DarkColors = darkColorScheme(
    primary = Color(0xFFC7C7CB),
    onPrimary = Color(0xFF303033),
    primaryContainer = Color(0xFF43464A),
    onPrimaryContainer = Color(0xFFE3E3E7),
    inversePrimary = Color(0xFF5D6064),
    secondary = Color(0xFFC4C6CA),
    onSecondary = Color(0xFF2E3134),
    secondaryContainer = Color(0xFF44474A),
    onSecondaryContainer = Color(0xFFE0E2E6),
    tertiary = Color(0xFFD5C2BC),
    onTertiary = Color(0xFF3A2F2B),
    tertiaryContainer = Color(0xFF524540),
    onTertiaryContainer = Color(0xFFF2DFD9),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101214),
    onBackground = Color(0xFFE4E2DF),
    surface = Color(0xFF101214),
    onSurface = Color(0xFFE4E2DF),
    surfaceVariant = Color(0xFF46474A),
    onSurfaceVariant = Color(0xFFC7C6C9),
    outline = Color(0xFF909093),
    outlineVariant = Color(0xFF46474A),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE4E2DF),
    inverseOnSurface = Color(0xFF303030),
    surfaceDim = Color(0xFF101214),
    surfaceBright = Color(0xFF36383A),
    surfaceContainerLowest = Color(0xFF0B0D0F),
    surfaceContainerLow = Color(0xFF181A1C),
    surfaceContainer = Color(0xFF1C1E20),
    surfaceContainerHigh = Color(0xFF26282A),
    surfaceContainerHighest = Color(0xFF313335),
)

@Composable
fun MoBaiTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }

    // 状态栏 / 导航栏图标颜色跟随应用主题（而非系统）
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}

private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
