package com.mobai.jm.util

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.tukaani.xz.LZMA2Options
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater

/**
 * 压缩包写出器（zip / 7z / tar.gz，丝滑适配漫画页存放）。
 *
 *  - 级别预设：0 储存 · 1 极速 · 2 快速 · 3 标准 · 4 最大 · 5 极限
 *  - 7z 支持 AES-256 密码（含加密文件名）；zip / tar.gz 不支持加密
 *  - provider 逐条按需生成字节（每次只持有一页，内存恒定）
 */
object ArchiveWriter {

    val LEVEL_NAMES = listOf("储存", "极速", "快速", "标准", "最大", "极限")

    fun write(
        out: File,
        format: String,
        count: Int,
        level: Int,
        password: String?,
        provider: (Int) -> Pair<String, ByteArray>,
    ) {
        val lv = level.coerceIn(0, 5)
        when (format) {
            "zip" -> writeZip(out, count, ZIP_LEVELS[lv], provider)
            "targz" -> writeTarGz(out, count, GZ_LEVELS[lv], provider)
            "7z" -> write7z(out, count, lv, password, provider)
            else -> throw IllegalArgumentException("未知格式：$format")
        }
    }

    private fun writeZip(
        out: File,
        count: Int,
        deflateLevel: Int,
        provider: (Int) -> Pair<String, ByteArray>,
    ) {
        ZipArchiveOutputStream(out).use { zos ->
            zos.setUseZip64(Zip64Mode.AsNeeded)
            zos.setLevel(deflateLevel)
            for (i in 0 until count) {
                val (name, bytes) = provider(i)
                val e = ZipArchiveEntry(name)
                e.size = bytes.size.toLong()
                zos.putArchiveEntry(e)
                zos.write(bytes)
                zos.closeArchiveEntry()
            }
        }
    }

    private fun writeTarGz(
        out: File,
        count: Int,
        gzLevel: Int,
        provider: (Int) -> Pair<String, ByteArray>,
    ) {
        FileOutputStream(out).use { fos ->
            val gp = GzipParameters().apply { compressionLevel = gzLevel }
            GzipCompressorOutputStream(fos, gp).use { gz ->
                TarArchiveOutputStream(gz).use { tos ->
                    tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    for (i in 0 until count) {
                        val (name, bytes) = provider(i)
                        val e = TarArchiveEntry(name).apply { size = bytes.size.toLong() }
                        tos.putArchiveEntry(e)
                        tos.write(bytes)
                        tos.closeArchiveEntry()
                    }
                }
            }
        }
    }

    private fun write7z(
        out: File,
        count: Int,
        lv: Int,
        password: String?,
        provider: (Int) -> Pair<String, ByteArray>,
    ) {
        val sz = if (password.isNullOrEmpty()) SevenZOutputFile(out)
        else SevenZOutputFile(out, password.toCharArray())
        sz.use {
            if (lv == 0) {
                it.setContentMethods(listOf(SevenZMethodConfiguration(SevenZMethod.COPY)))
            } else {
                // 必须传 LZMA2Options 对象（Integer 会被当作“字典大小”而非级别）
                it.setContentMethods(
                    listOf(
                        SevenZMethodConfiguration(
                            SevenZMethod.LZMA2,
                            LZMA2Options(LZMA_LEVELS[lv]),
                        )
                    )
                )
            }
            for (i in 0 until count) {
                val (name, bytes) = provider(i)
                val e = SevenZArchiveEntry().apply {
                    this.name = name
                    this.size = bytes.size.toLong()
                }
                it.putArchiveEntry(e)
                it.write(bytes)
                it.closeArchiveEntry()
            }
        }
    }

    private val ZIP_LEVELS = intArrayOf(Deflater.NO_COMPRESSION, 1, 3, 6, 8, 9)
    private val GZ_LEVELS = intArrayOf(0, 1, 3, 6, 8, 9)
    private val LZMA_LEVELS = intArrayOf(0, 1, 3, 5, 7, 9)
}
