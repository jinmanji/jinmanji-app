package com.mobai.jm.plugin.tor

import android.os.Bundle

/** 插件进程内的共享状态（Service 写入，Provider / Activity 读取） */
object TorState {
    @Volatile var state = "idle"
    @Volatile var bootstrap = 0
    @Volatile var message = ""
    @Volatile var torVersion = ""

    fun toBundle(): Bundle = Bundle().apply {
        putString("state", state)
        putInt("bootstrap", bootstrap)
        putInt("socksPort", TorPluginService.SOCKS_PORT)
        putString("message", message)
        putString("torVersion", torVersion)
    }
}
