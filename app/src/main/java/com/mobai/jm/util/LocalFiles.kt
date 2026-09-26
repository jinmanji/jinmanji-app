package com.mobai.jm.util

import android.content.Context
import java.io.File

/** 手动下载（整本缓存）的本地文件管理 */
object LocalFiles {

    fun pageFile(context: Context, photoId: String, filename: String): File =
        File(File(StorageUtil.downloadDir(context), photoId), filename)

    fun pageFileOrNull(context: Context, photoId: String, filename: String): File? =
        pageFile(context, photoId, filename).takeIf { it.exists() }
}
