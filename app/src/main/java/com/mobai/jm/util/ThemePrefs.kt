package com.mobai.jm.util

import android.content.Context

/** 主题模式：跟随系统 / 浅色 / 深色 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

class ThemePrefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("mobai_prefs", Context.MODE_PRIVATE)

    var mode: ThemeMode
        get() = runCatching {
            ThemeMode.valueOf(sp.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM)
        set(value) {
            sp.edit().putString("theme_mode", value.name).apply()
        }
}
