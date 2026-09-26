package com.mobai.jm.util

import android.content.Context
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 图像预取：错峰入队（避免同时开几十个连接），滚动到下一屏时图片已在内存/磁盘缓存里 */
object ImagePrefetch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun prefetch(context: Context, urls: List<String>, staggerMs: Long = 50) {
        if (urls.isEmpty()) return
        scope.launch {
            urls.distinct().forEach { u ->
                runCatching {
                    context.imageLoader.enqueue(
                        ImageRequest.Builder(context).data(u).build()
                    )
                }
                delay(staggerMs)
            }
        }
    }
}
