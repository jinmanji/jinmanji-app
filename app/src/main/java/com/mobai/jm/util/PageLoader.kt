package com.mobai.jm.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.mobai.jm.data.JmApi
import com.mobai.jm.data.JmImageDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 页面图像加载器：下载原图 → 在【原始尺寸】上完成乱序还原 → 再缩放到目标宽度。
 *
 * 为什么必须"先解密再缩放"：乱序还原的分段边界依赖原始高度；
 * 若先缩放、再按新的高度重算边界，边界会因取整产生数像素错位，
 * 表现为"很多横线"（看起来像没解密成功）。
 *
 * 缓存设计 ——「一次下载，双重缓存」：
 *   预览墙加载时 → 下载原图，同时落地两份缓存：
 *     ① 原图 raw 字节 → page_cache（阅读器直接复用，免二次下载）
 *     ② 生成的缩略图 → *.t360-s{id}.jpg 小文件（重访详情页秒开，无需再解码原图）
 *   （缩略图缓存仅在 targetWidth ≤ 720 的小图场景生成/读取）
 *
 * 其它保障：内存 LRU 96MB、并发上限 3、磁盘总量 512MB 自动清理、手动下载目录优先。
 */
object PageLoader {
    /** 用户并发设置（0 = 自动；详情缩略图上限 32，避免原图解密内存峰值） */
    @Volatile
    var concurrency: Int = 0
    private var semMax = 4
    private var sem = Semaphore(4)

    private fun semFor(): Semaphore {
        val want = if (concurrency in 1..512) concurrency.coerceAtMost(32) else 4
        if (want != semMax) {
            sem = Semaphore(want)
            semMax = want
        }
        return sem
    }

    private val memory = object : LruCache<String, Bitmap>(96 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private const val DISK_CAP = 512L * 1024 * 1024
    private const val THUMB_MAX = 720

    @Volatile
    private var lastTrim = 0L

    suspend fun load(
        context: Context,
        photoId: String,
        filename: String,
        scrambleId: Int,
        targetWidth: Int,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$photoId/$filename@$scrambleId@$targetWidth"
        memory.get(key)?.let { return@withContext it }
        semFor().withPermit {
            memory.get(key)?.let { return@withPermit it }

            val isThumb = targetWidth in 1..THUMB_MAX
            val thumb = if (isThumb) {
                thumbFile(context, photoId, filename, targetWidth, scrambleId)
            } else {
                null
            }

            // ① 缩略图磁盘缓存：命中即直接返回（无需原图、无需网络）
            if (thumb != null) {
                runCatching {
                    if (thumb.exists()) {
                        val b = BitmapFactory.decodeFile(thumb.absolutePath)
                        if (b != null) {
                            memory.put(key, b)
                            return@withPermit b
                        }
                    }
                }
            }

            // ② 原图字节（手动下载目录 > 页缓存 > 网络）
            val bytes = readBytes(context, photoId, filename) ?: return@withPermit null
            runCatching {
                // 缩略图用 RGB_565 解码：内存减半、解码更快（颜色损失对小缩略图不可察）
                val opts = BitmapFactory.Options().apply {
                    if (isThumb) inPreferredConfig = Bitmap.Config.RGB_565
                }
                val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    ?: return@withPermit null
                var bmp = JmImageDecoder.decode(
                    full,
                    JmImageDecoder.segments(scrambleId, photoId, filename),
                )
                if (bmp !== full) full.recycle()
                if (targetWidth in 1 until bmp.width) {
                    val th = (bmp.height.toLong() * targetWidth / bmp.width)
                        .toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(bmp, targetWidth, th, true)
                    if (scaled !== bmp) bmp.recycle()
                    bmp = scaled
                }
                // ③ 顺手把缩略图存盘（下次秒开、免解码）
                if (thumb != null && AppPrefs(context).autoCache) {
                    val out = bmp
                    runCatching {
                        thumb.parentFile?.mkdirs()
                        thumb.outputStream().use {
                            out.compress(Bitmap.CompressFormat.JPEG, 82, it)
                        }
                    }
                }
                memory.put(key, bmp)
                bmp
            }.getOrNull()
        }
    }

    private fun thumbFile(
        context: Context,
        photoId: String,
        filename: String,
        width: Int,
        scrambleId: Int,
    ): File =
        File(File(StorageUtil.pageCacheDir(context), photoId), "$filename.t$width-s$scrambleId.jpg")

    private suspend fun readBytes(context: Context, photoId: String, filename: String): ByteArray? {
        // ① 手动下载的原始文件
        runCatching {
            val f = LocalFiles.pageFile(context, photoId, filename)
            if (f.exists()) return f.readBytes()
        }
        // ② 页缓存（原图 raw）
        val cacheFile = pageCacheFile(context, photoId, filename)
        runCatching {
            if (cacheFile.exists()) return cacheFile.readBytes()
        }
        // ③ 网络（换域名重试 3 次）
        for (attempt in 0..2) {
            try {
                val bytes = JmApi.fetchBytes(JmApi.imageUrl(photoId, filename, attempt))
                if (bytes.isNotEmpty()) {
                    if (AppPrefs(context).autoCache) {
                        runCatching {
                            cacheFile.parentFile?.mkdirs()
                            cacheFile.writeBytes(bytes)
                        }
                        trimDiskCache(context)
                    }
                    return bytes
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun pageCacheFile(context: Context, photoId: String, filename: String): File =
        File(File(StorageUtil.pageCacheDir(context), photoId), filename)

    /** 供下载队列复用：查“原始乱序字节”的已有本地缓存（手动下载目录 / 页缓存），无则 null */
    fun cachedRawFile(context: Context, photoId: String, filename: String): File? {
        runCatching {
            val f = LocalFiles.pageFile(context, photoId, filename)
            if (f.exists() && f.length() > 1024) return f
        }
        runCatching {
            val f = pageCacheFile(context, photoId, filename)
            if (f.exists() && f.length() > 1024) return f
        }
        return null
    }

    private fun trimDiskCache(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastTrim < 15_000L) return
        lastTrim = now
        runCatching {
            val dir = StorageUtil.pageCacheDir(context)
            val files = dir.walkTopDown().filter { it.isFile }.toList()
            var total = files.sumOf { it.length() }
            if (total <= DISK_CAP) return
            files.sortedBy { it.lastModified() }.forEach { f ->
                if (total > DISK_CAP * 9 / 10) {
                    val len = f.length()
                    if (f.delete()) total -= len
                }
            }
        }
    }
}
