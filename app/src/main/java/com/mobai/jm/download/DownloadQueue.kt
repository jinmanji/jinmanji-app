package com.mobai.jm.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateListOf
import com.mobai.jm.data.JmAlbum
import com.mobai.jm.data.JmApi
import com.mobai.jm.data.JmImageDecoder
import com.mobai.jm.util.AppPrefs
import com.mobai.jm.util.ArchiveWriter
import com.mobai.jm.util.DiagLog
import com.mobai.jm.util.PageLoader
import com.mobai.jm.util.PdfWriter
import com.mobai.jm.util.StorageUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 下载队列（PDF 下载，与「阅读缓存」完全独立）：
 *  - 线性队列：可添加多个本子，逐个下载；任务记录持久化（重启不丢）
 *  - 单个本子内部：2 个并发协程异步下载图像
 *  - 完成后按页码顺序合并为 PDF，保存到 Download 目录（路径可配置）
 *  - 错误重试（网络 3 次 / 单图 3 次换线）+ 失败兜底（缺失页生成占位页）
 *  - 支持：删除记录（可选同时删除文件）、强制终止进行中任务、分享、打开位置
 */
object DownloadQueue {

    @Serializable
    enum class State { QUEUED, RUNNING, DONE, FAILED, CANCELED }

    @Serializable
    data class Task(
        val id: Long,
        val albumId: String,
        val title: String,
        val state: State = State.QUEUED,
        val done: Int = 0,
        val total: Int = 0,
        val failedPages: Int = 0,
        val savedTo: String? = null,
        val savedUri: String? = null,
        val cover: String? = null,
        val createdAt: Long? = null,
        val error: String? = null,
        val format: String = "pdf",
        val level: Int = 3,
        val pwdMode: String = "none",
        val fixedPwd: String = "",
        val pwdStrategy: String = "append",
        val exporting: Boolean = false,
        val exportDone: Int = 0,
        val exportTotal: Int = 0,
    )

    private val _tasks = mutableStateListOf<Task>()
    val tasks: List<Task> get() = _tasks

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private val workerLock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    private var appContext: Context? = null
    private var storeFile: File? = null
    private var saveJob: Job? = null

    fun init(context: Context) {
        if (storeFile != null) return
        appContext = context.applicationContext
        storeFile = File(StorageUtil.metaDir(context), "downloads.json")
        runCatching {
            storeFile?.takeIf { it.exists() }?.readText()?.let { text ->
                json.decodeFromString<List<Task>>(text).forEach { t ->
                    _tasks.add(
                        if (t.state == State.RUNNING || t.state == State.QUEUED) {
                            t.copy(state = State.FAILED, error = "已中断（应用重启）")
                        } else {
                            t
                        }
                    )
                }
            }
        }
    }

    fun enqueue(context: Context, album: JmAlbum) {
        appContext = context.applicationContext
        val p = AppPrefs(context)
        _tasks.add(
            Task(
                id = System.currentTimeMillis(),
                albumId = album.id,
                title = album.name,
                cover = JmApi.albumCoverUrl(album.id),
                createdAt = System.currentTimeMillis(),
                format = p.downloadFormat,
                level = p.archiveLevel,
                pwdMode = p.archivePwdMode,
                fixedPwd = p.archiveFixedPwd,
                pwdStrategy = p.archivePwdStrategy,
            )
        )
        DownloadService.start(context.applicationContext)
        ensureWorker(context.applicationContext)
        scheduleSave()
    }

    fun cancel(id: Long) {
        val index = _tasks.indexOfFirst { it.id == id }
        if (index >= 0 && _tasks[index].state != State.DONE && _tasks[index].state != State.FAILED) {
            _tasks[index] = _tasks[index].copy(state = State.CANCELED)
        }
    }

    fun cancelCurrent() {
        _tasks.firstOrNull { it.state == State.RUNNING || it.state == State.QUEUED }?.let { cancel(it.id) }
    }

    /** 删除任务：进行中/排队中 → 强制终止并清理已下载的临时文件；deleteFile → 同时删除已保存的 PDF */
    fun removeTask(id: Long, deleteFile: Boolean) {
        val t = _tasks.firstOrNull { it.id == id } ?: return
        if (t.state == State.RUNNING || t.state == State.QUEUED) {
            val idx = _tasks.indexOfFirst { it.id == id }
            if (idx >= 0) _tasks[idx] = _tasks[idx].copy(state = State.CANCELED)
            cleanupTempFiles(id)
        }
        if (deleteFile) deleteOutputFile(t)
        _tasks.removeAll { it.id == id }
        scheduleSave()
    }

    /** 系统文件管理器打开文件所在位置 */
    fun folderIntent(task: Task): Intent? {
        val rel = task.savedTo ?: return null
        val clean = rel.trim('/')
        if (!clean.startsWith("Download")) return null
        val uri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:$clean",
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun viewFileIntent(task: Task): Intent? {
        val uri = task.savedUri?.let { Uri.parse(it) } ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun shareIntent(task: Task): Intent? {
        val uri = task.savedUri?.let { Uri.parse(it) } ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(600)
            val f = storeFile ?: return@launch
            val snapshot = _tasks.toList()
            runCatching { f.writeText(json.encodeToString(snapshot)) }
        }
    }

    private fun ensureWorker(context: Context) {
        synchronized(workerLock) {
            if (worker?.isActive == true) return
            worker = scope.launch {
                while (true) {
                    val task = _tasks.firstOrNull { it.state == State.QUEUED } ?: break
                    runTask(context, task)
                }
            }
        }
    }

    /** 记录被删除或标记取消 = 终止 */
    private fun isCanceled(id: Long): Boolean {
        val t = _tasks.firstOrNull { it.id == id } ?: return true
        return t.state == State.CANCELED
    }

    private fun update(id: Long, block: (Task) -> Task) {
        val index = _tasks.indexOfFirst { it.id == id }
        if (index >= 0) {
            _tasks[index] = block(_tasks[index])
            scheduleSave()
        }
    }

    private fun cleanupTempFiles(id: Long) {
        val ctx = appContext ?: return
        runCatching { File(ctx.cacheDir, "pdf_$id").deleteRecursively() }
        runCatching { File(ctx.cacheDir, "mobai_$id.pdf").delete() }
    }

    private fun deleteOutputFile(t: Task): Boolean {
        val ctx = appContext ?: return false
        t.savedUri?.let { uriStr ->
            return runCatching {
                ctx.contentResolver.delete(Uri.parse(uriStr), null, null)
                true
            }.getOrDefault(false)
        }
        val path = t.savedTo?.takeIf { it.isNotBlank() } ?: return false
        return runCatching {
            val candidates = listOf(File(Environment.getExternalStorageDirectory(), path), File(path))
            candidates.any { it.exists() && it.delete() }
        }.getOrDefault(false)
    }

    private data class PageRef(val photoId: String, val filename: String, val scrambleId: Int)

    private data class SavedInfo(val displayPath: String, val uriString: String?)

    private suspend fun runTask(context: Context, task: Task) {
        val taskId = task.id
        update(taskId) { it.copy(state = State.RUNNING) }
        try {
            DiagLog.d("下载开始: ${task.albumId}")
            val album = retryNet(3) { JmApi.album(task.albumId) }
                ?: throw IllegalStateException("获取本子信息失败")

            // 1) 汇集所有页（含乱序参数）
            val pages = mutableListOf<PageRef>()
            for (ep in album.episodes) {
                if (isCanceled(taskId)) throw CancellationException("已取消")
                val images = retryNet(3) { JmApi.chapterImages(ep.id) } ?: continue
                val sid = JmApi.scrambleId(ep.id)
                images.forEach { fn -> pages.add(PageRef(ep.id, fn, sid)) }
                update(taskId) { it.copy(total = pages.size) }
            }
            if (pages.isEmpty()) throw IllegalStateException("没有可下载的图片")

            // 2) 2 并发下载原图到临时文件
            val tmpDir = File(context.cacheDir, "pdf_$taskId").apply { mkdirs() }
            val doneCnt = AtomicInteger()
            val failCnt = AtomicInteger()
            val cacheCnt = AtomicInteger()
            val sem = Semaphore(2)
            coroutineScope {
                pages.mapIndexed { idx, ref ->
                    async {
                        sem.withPermit {
                            if (isCanceled(taskId)) return@withPermit
                            val out = File(tmpDir, "%05d.bin".format(idx))
                            var ok = false
                            // ① 缓存优先：手动下载目录 / 页缓存里的“原始乱序字节”可直接复用
                            runCatching {
                                val cached = PageLoader.cachedRawFile(context, ref.photoId, ref.filename)
                                if (cached != null) {
                                    cached.copyTo(out, overwrite = true)
                                    ok = out.length() > 1024
                                    if (ok) cacheCnt.incrementAndGet()
                                }
                            }
                            // ② 网络（换域名重试 3 次）
                            if (!ok) for (attempt in 0..2) {
                                try {
                                    val bytes = JmApi.fetchBytes(
                                        JmApi.imageUrl(ref.photoId, ref.filename, attempt)
                                    )
                                    out.writeBytes(bytes)
                                    ok = true
                                    break
                                } catch (_: Exception) {
                                }
                            }
                            if (ok) doneCnt.incrementAndGet() else failCnt.incrementAndGet()
                            update(taskId) { it.copy(done = doneCnt.get(), failedPages = failCnt.get()) }
                        }
                    }
                }.awaitAll()
            }
            if (isCanceled(taskId)) throw CancellationException("已取消")

            // 3) 按格式生成输出文件（逐页解码 + 乱序还原）
            update(taskId) { it.copy(exporting = true, exportDone = 0, exportTotal = pages.size) }
            val fmt = task.format.ifBlank { "pdf" }
            val ext = when (fmt) {
                "zip" -> "zip"
                "7z" -> "7z"
                "targz" -> "tar.gz"
                else -> "pdf"
            }
            val outFile = File(context.cacheDir, "mobai_$taskId.$ext")
            var pwdSuffix = ""
            var pwdToast: String? = null
            val imgQ = AppPrefs(context).archiveImgQuality
            if (fmt == "pdf") {
                buildPdf(pages, tmpDir, outFile) { n ->
                    update(taskId) { it.copy(exportDone = n) }
                }
            } else {
                var pwd: String? = null
                if (fmt == "7z" && task.pwdMode != "none") {
                    pwd = if (task.pwdMode == "fixed") task.fixedPwd.ifBlank { null } else genPassword()
                }
                // 「原图直存」：不解密、不转换，直接打包下载到的原始字节（附说明文件）
                val rawMode = imgQ == "raw"
                val noteBytes: ByteArray? = if (rawMode) {
                    val missing = (0 until pages.size).count {
                        !File(tmpDir, "%05d.bin".format(it)).exists()
                    }
                    ("禁漫姬 · 原图直存说明\n" +
                        "本压缩包为站点原始文件（零转换、未解密）。\n" +
                        "注意：作品 ID ≥ 220980 的图片经过站点乱序加密，需解密还原后才能正常查看；\n" +
                        "未加密的旧作品可直接查看。\n" +
                        "缺失页数：$missing\n").toByteArray(Charsets.UTF_8)
                } else null
                val entryCount = pages.size + (if (noteBytes != null) 1 else 0)
                val provider: (Int) -> Pair<String, ByteArray> = { i ->
                    val result: Pair<String, ByteArray> = when {
                        noteBytes != null && i == pages.size -> "说明.txt" to noteBytes
                        rawMode -> {
                            val f = File(tmpDir, "%05d.bin".format(i))
                            val name = pages.getOrNull(i)?.filename?.takeIf { it.isNotBlank() }
                                ?: "%05d.webp".format(i + 1)
                            name to (if (f.exists()) f.readBytes() else ByteArray(0))
                        }
                        else -> archiveEntry(tmpDir, pages[i], i, imgQ)
                    }
                    update(taskId) { t -> t.copy(exportDone = (i + 1).coerceAtMost(pages.size)) }
                    result
                }
                if (fmt == "7z" && pwd != null && task.pwdStrategy == "twolevel") {
                    // 二级压缩包：内层加密 7z + 解压密码.txt → 外层无密码 zip
                    val inner = File(context.cacheDir, "mobai_inner_$taskId.7z")
                    ArchiveWriter.write(inner, "7z", entryCount, task.level, pwd, provider)
                    val txt = "解压密码：$pwd\n".toByteArray(Charsets.UTF_8)
                    ArchiveWriter.write(outFile, "zip", 2, 1, null) { i ->
                        if (i == 0) inner.name to inner.readBytes() else "解压密码.txt" to txt
                    }
                    inner.delete()
                    pwdToast = pwd
                } else {
                    ArchiveWriter.write(outFile, fmt, entryCount, task.level, pwd, provider)
                    if (pwd != null && task.pwdStrategy == "append") {
                        pwdSuffix = " 密码$pwd"
                        pwdToast = pwd
                    }
                }
            }

            // 4) 保存到 Download 目录（MediaStore）
            val displayName = safeFileName(task.albumId, album.name, ext) + pwdSuffix
            val dir = AppPrefs(context).downloadPath
            val saved = saveDownload(context, outFile, displayName, dir, mimeOf(fmt))
            tmpDir.deleteRecursively()
            outFile.delete()
            update(taskId) {
                it.copy(
                    state = State.DONE,
                    exporting = false,
                    savedTo = saved.displayPath,
                    savedUri = saved.uriString,
                    done = (pages.size - failCnt.get()).coerceAtLeast(0),
                )
            }
            val tip = pwdToast ?: ""
            if (tip.isNotEmpty()) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, "解压密码：$tip", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            DiagLog.d("下载完成: ${saved.displayPath}（缓存命中 ${cacheCnt.get()}/${pages.size}）")
        } catch (e: CancellationException) {
            update(taskId) { it.copy(state = State.CANCELED, exporting = false) }
            cleanupTempFiles(taskId)
            DiagLog.w("下载取消: ${task.albumId}")
        } catch (e: Exception) {
            update(taskId) { it.copy(state = State.FAILED, exporting = false, error = e.message ?: "未知错误") }
            cleanupTempFiles(taskId)
            DiagLog.w("下载失败: ${task.albumId}", e)
        }
    }

    private suspend fun <T> retryNet(times: Int, block: suspend () -> T): T? {
        var last: Exception? = null
        repeat(times) { i ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                delay(500L * (i + 1))
            }
        }
        DiagLog.w("网络重试失败: ${last?.message}")
        return null
    }

    private fun buildPdf(pages: List<PageRef>, tmpDir: File, out: File, onProgress: (Int) -> Unit = {}) {
        // 质量预设：visual=视觉无损(97) / max=最高(100) / compact=压缩(90 且宽≤1240)
        val preset = appContext?.let { AppPrefs(it).pdfPreset } ?: "visual"
        val quality = when (preset) {
            "max" -> 100
            "compact" -> 90
            else -> 97
        }
        val capWidth = if (preset == "compact") 1240 else 0

        val writerPages = ArrayList<PdfWriter.Page>(pages.size)
        try {
            pages.forEachIndexed { idx, ref ->
                val f = File(tmpDir, "%05d.bin".format(idx))
                var bmp: Bitmap? = null
                if (f.exists()) {
                    runCatching {
                        val raw = BitmapFactory.decodeByteArray(f.readBytes(), 0, f.length().toInt())
                        if (raw != null) {
                            // 图是乱序加密的：必须先解码还原，再重编码为 JPEG 嵌入
                            val decoded = JmImageDecoder.decode(
                                raw,
                                JmImageDecoder.segments(ref.scrambleId, ref.photoId, ref.filename),
                            )
                            if (decoded !== raw) raw.recycle()
                            bmp = decoded
                        }
                    }
                }
                var page = bmp
                if (page == null) {
                    // 缺失页占位
                    val ph = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
                    val c = android.graphics.Canvas(ph)
                    c.drawColor(Color.LTGRAY)
                    val paint = Paint().apply {
                        color = Color.DKGRAY
                        textSize = 64f
                        textAlign = Paint.Align.CENTER
                    }
                    c.drawText("第 ${idx + 1} 页缺失", 620f, 877f, paint)
                    page = ph
                } else if (capWidth > 0 && page.width > capWidth) {
                    val scaled = Bitmap.createScaledBitmap(
                        page,
                        capWidth,
                        (page.height.toLong() * capWidth / page.width).toInt().coerceAtLeast(1),
                        true,
                    )
                    if (scaled !== page) page.recycle()
                    page = scaled
                }
                writerPages.add(PdfWriter.Page.ofBitmap(page))
            }
            PdfWriter.write(out, writerPages, quality, onProgress)
        } finally {
            writerPages.forEach { runCatching { it.bitmap?.recycle() } }
        }
    }

    private fun safeFileName(albumId: String, title: String, ext: String): String {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|\n\r]"), "_").trim().take(80)
        return "JM$albumId $clean.$ext".trim()
    }

    private fun mimeOf(fmt: String): String = when (fmt) {
        "zip" -> "application/zip"
        "7z" -> "application/x-7z-compressed"
        "targz" -> "application/gzip"
        else -> "application/pdf"
    }

    private fun genPassword(): String =
        (1..12).map { "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789".random() }
            .joinToString("")

    /** 压缩包条目：解码 + 乱序还原 + 无损编码（API30+ WebP 无损，否则 PNG） */
    private fun archiveEntry(tmpDir: File, ref: PageRef, idx: Int, imgQ: String): Pair<String, ByteArray> {
        val f = File(tmpDir, "%05d.bin".format(idx))
        var bmp: Bitmap? = null
        if (f.exists()) {
            runCatching {
                val raw = BitmapFactory.decodeByteArray(f.readBytes(), 0, f.length().toInt())
                if (raw != null) {
                    val decoded = JmImageDecoder.decode(
                        raw,
                        JmImageDecoder.segments(ref.scrambleId, ref.photoId, ref.filename),
                    )
                    if (decoded !== raw) raw.recycle()
                    bmp = decoded
                }
            }
        }
        val page = bmp ?: run {
            val ph = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(ph)
            c.drawColor(Color.LTGRAY)
            val paint = Paint().apply {
                color = Color.DKGRAY
                textSize = 64f
                textAlign = Paint.Align.CENTER
            }
            c.drawText("第 ${idx + 1} 页缺失", 620f, 877f, paint)
            ph
        }
        // 解密后按【原始文件格式】存回：源是 webp 则输出 webp，源是 jpg 则输出 jpg
        val srcExt = ref.filename.substringAfterLast('.', "").lowercase()
        val wantJpeg = srcExt == "jpg" || srcExt == "jpeg"
        val base = ref.filename.substringBeforeLast('.').ifBlank { "%05d".format(idx + 1) }
        return try {
            val bos = java.io.ByteArrayOutputStream(1 shl 19)
            @Suppress("DEPRECATION")
            when {
                imgQ == "lossless" -> {
                    // 像素无损：注意源本身是有损压缩，无损重编码体积必然偏大
                    if (wantJpeg) {
                        page.compress(Bitmap.CompressFormat.JPEG, 100, bos)
                        "$base.jpg" to bos.toByteArray()
                    } else if (android.os.Build.VERSION.SDK_INT >= 30) {
                        page.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, bos)
                        "$base.webp" to bos.toByteArray()
                    } else {
                        page.compress(Bitmap.CompressFormat.PNG, 100, bos)
                        "$base.png" to bos.toByteArray()
                    }
                }
                wantJpeg -> {
                    val q = if (imgQ == "compact") 85 else 95
                    page.compress(Bitmap.CompressFormat.JPEG, q, bos)
                    "$base.jpg" to bos.toByteArray()
                }
                else -> {
                    // 视觉无损 / 压缩优先：WebP 有损重编码，体积≈原始甚至更小
                    val q = if (imgQ == "compact") 80 else 95
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        page.compress(Bitmap.CompressFormat.WEBP_LOSSY, q, bos)
                    } else {
                        page.compress(Bitmap.CompressFormat.WEBP, q, bos)
                    }
                    "$base.webp" to bos.toByteArray()
                }
            }
        } finally {
            page.recycle()
        }
    }

    private fun saveDownload(context: Context, src: File, displayName: String, relativeDir: String, mime: String): SavedInfo {
        val safeDir = relativeDir.trim().trim('/').ifBlank { "Download" }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, safeDir)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建下载文件")
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("写入下载文件失败")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return SavedInfo("$safeDir/$displayName", uri.toString())
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStorageDirectory(), safeDir).apply { mkdirs() }
            val dst = File(dir, displayName)
            src.copyTo(dst, overwrite = true)
            return SavedInfo(dst.absolutePath, null)
        }
    }
}
