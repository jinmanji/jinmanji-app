package com.mobai.jm.ui.reader

import android.content.Context
import android.graphics.Bitmap
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import com.mobai.jm.data.JmImageDecoder

/** 图片乱序还原（Coil Transformation，cacheKey 包含参数以便正确缓存） */
class JmDescrambleTransformation(
    private val scrambleId: Int,
    private val photoId: String,
    private val filename: String,
) : Transformation {

    override val cacheKey: String = "jm-ds-$scrambleId-$photoId-$filename"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap =
        JmImageDecoder.decode(input, JmImageDecoder.segments(scrambleId, photoId, filename))
}

/**
 * 构建阅读页图片请求（含乱序还原；用于显示与预加载，保证缓存键一致）。
 *
 * @param model String(url) 或 File(本地已下载)
 * @param diskPolicy 自动缓存开 → ENABLED；关 → READ_ONLY
 */
fun pageImageRequest(
    context: Context,
    model: Any,
    photoId: String,
    filename: String,
    scrambleId: Int,
    diskPolicy: CachePolicy = CachePolicy.ENABLED,
    sizePx: Int = 0,
): ImageRequest = ImageRequest.Builder(context)
    .data(model)
    .allowHardware(false)
    .diskCachePolicy(diskPolicy)
    .apply { if (sizePx > 0) size(sizePx) }
    .transformations(JmDescrambleTransformation(scrambleId, photoId, filename))
    .build()
