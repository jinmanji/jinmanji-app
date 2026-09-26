package com.mobai.jm.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 启动门控：配置了「启动时自动连接隧道」时，
 * 先完成隧道连接（成功/失败）才开始加载资源。
 */
object StartupGate {
    sealed class State {
        object Ready : State()
        data class Connecting(val msg: String) : State()
        data class Failed(val msg: String) : State()
    }

    var state by mutableStateOf<State>(State.Ready)

    fun ready() {
        state = State.Ready
    }

    fun connecting(msg: String) {
        state = State.Connecting(msg)
    }

    fun failed(msg: String) {
        state = State.Failed(msg)
    }
}
