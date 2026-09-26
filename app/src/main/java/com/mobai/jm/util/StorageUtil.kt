package com.mobai.jm.util

import android.content.Context
import coil.ImageLoader
import java.io.File
import java.util.Locale

/**
 * 存储路径工具。
 * 隐私模式（默认开）：所有应用数据写入应用私有目录（filesDir，无 root 不可读）；
 * 关闭时退回外部应用目录（Android/data，部分环境可被文件管理器访问）。
 */
object StorageUtil {

    private fun base(context: Context): File =
        if (AppPrefs(context).privacyMode) context.filesDir
        else (context.getExternalFilesDir(null) ?: context.filesDir)

    /** Coil 图片磁盘缓存目录 */
    fun imageCacheDir(context: Context): File = File(base(context), "image_cache")

    /** 本子详情(meta)缓存目录 */
    fun metaDir(context: Context): File = File(base(context), "meta")

    /** 手动下载的图片目录 */
    fun downloadDir(context: Context): File = File(base(context), "downloads")

    /** 页缓存目录（阅读时自动缓存的页面原图） */
    fun pageCacheDir(context: Context): File = File(base(context), "page_cache")

    /** 本地收藏文件 */
    fun favoritesFile(context: Context): File = File(base(context), "favorites.json")

    /** 首次开启隐私模式时，把旧外部目录里的数据（meta/收藏/手动下载）迁移到私有目录 */
    fun migrateIfNeeded(context: Context) {
        if (!AppPrefs(context).privacyMode) return
        Thread {
            runCatching {
                val external = context.getExternalFilesDir(null) ?: return@runCatching
                val internalRoot = context.filesDir
                listOf("meta", "downloads", "page_cache").forEach { name ->
                    val src = File(external, name)
                    val dst = File(internalRoot, name)
                    if (src.exists() && !dst.exists()) {
                        src.copyRecursively(dst, overwrite = false)
                        DiagLog.d("隐私模式迁移：$name → 私有目录")
                    }
                }
                val favSrc = File(external, "favorites.json")
                val favDst = File(internalRoot, "favorites.json")
                if (favSrc.exists() && !favDst.exists()) {
                    favSrc.copyTo(favDst, overwrite = false)
                    DiagLog.d("隐私模式迁移：favorites.json → 私有目录")
                }
            }
        }.start()
    }

    /** (总字节数, 文件数) */
    fun dirStats(dir: File?): Pair<Long, Int> {
        if (dir == null || !dir.exists()) return 0L to 0
        var bytes = 0L
        var count = 0
        dir.walkTopDown().forEach { f ->
            if (f.isFile) {
                bytes += f.length()
                count++
            }
        }
        return bytes to count
    }

    fun totalCacheStats(context: Context): Pair<Long, Int> {
        val a = dirStats(metaDir(context))
        val b = dirStats(imageCacheDir(context))
        val c = dirStats(downloadDir(context))
        val d = dirStats(pageCacheDir(context))
        return (a.first + b.first + c.first + d.first) to
            (a.second + b.second + c.second + d.second)
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    /** 清除全部缓存（不动收藏） */
    fun clearCache(context: Context, imageLoader: ImageLoader) {
        runCatching { imageLoader.diskCache?.clear() }
        runCatching { imageLoader.memoryCache?.clear() }
        runCatching { metaDir(context).listFiles()?.forEach { it.deleteRecursively() } }
        runCatching { imageCacheDir(context).listFiles()?.forEach { it.deleteRecursively() } }
        runCatching { downloadDir(context).listFiles()?.forEach { it.deleteRecursively() } }
        runCatching { pageCacheDir(context).listFiles()?.forEach { it.deleteRecursively() } }
    }
}
