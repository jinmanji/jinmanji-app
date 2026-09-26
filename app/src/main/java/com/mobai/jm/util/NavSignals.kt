package com.mobai.jm.util

import kotlinx.coroutines.flow.MutableStateFlow

/** 跨组件导航信号（如：点击下载完成通知 → 打开「我的下载」页面） */
object NavSignals {
    val openDownloads = MutableStateFlow(0)
}
