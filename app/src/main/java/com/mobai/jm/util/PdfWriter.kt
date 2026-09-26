package com.mobai.jm.util

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * 极简 PDF 写入器（自研，零依赖，流式写出）：
 *  - 位图页：JPEG 编码后以 DCTDecode 嵌入（质量可调，默认 97 ≈ 视觉无损）
 *  - JPEG 直嵌页：若能拿到未加密的 jpg 原图字节，零重编码嵌入（体积最小）
 *
 * 对比：Android PdfDocument 会把每页无损重编码为 Flate（实测体积常为原图 5~10 倍），
 * 本写入器改用 JPEG DCTDecode 后，通常可缩小到原来的 1/5 ~ 1/10。
 *
 * 内存策略：逐页编码→写出→调用方可立即 recycle 位图，峰值内存 ≈ 单页。
 */
object PdfWriter {

    class Page(
        /** 位图页（将被 JPEG 编码） */
        val bitmap: Bitmap?,
        /** 或 JPEG 原图直嵌 */
        val jpegBytes: ByteArray?,
        val jpegWidth: Int,
        val jpegHeight: Int,
        val jpegComponents: Int,
    ) {
        companion object {
            fun ofBitmap(bmp: Bitmap): Page = Page(bmp, null, 0, 0, 0)

            /** 若是可直嵌的 JPEG（8bit，1~3 分量）返回 Page，否则 null */
            fun ofJpegBytes(bytes: ByteArray): Page? {
                val info = jpegInfo(bytes) ?: return null
                if (info[0] != 8 || info[3] !in 1..3) return null
                return Page(null, bytes, info[1], info[2], info[3])
            }

            /** 解析 JPEG SOF：返回 [精度, 宽, 高, 分量数] */
            private fun jpegInfo(b: ByteArray): IntArray? {
                if (b.size < 4 || (b[0].toInt() and 0xFF) != 0xFF || (b[1].toInt() and 0xFF) != 0xD8) return null
                var i = 2
                while (i + 3 < b.size) {
                    if ((b[i].toInt() and 0xFF) != 0xFF) {
                        i++
                        continue
                    }
                    var marker = b[i + 1].toInt() and 0xFF
                    while (marker == 0xFF && i + 2 < b.size) {
                        i++
                        marker = b[i + 1].toInt() and 0xFF
                    }
                    if (marker == 0xD8 || marker == 0x01 || (marker in 0xD0..0xD7)) {
                        i += 2
                        continue
                    }
                    val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
                    val isSof = marker in 0xC0..0xC3 || marker in 0xC5..0xC7 ||
                        marker in 0xC9..0xCB || marker in 0xCD..0xCF
                    if (isSof) {
                        if (i + 9 >= b.size) return null
                        val precision = b[i + 4].toInt() and 0xFF
                        val h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                        val w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                        val comps = b[i + 9].toInt() and 0xFF
                        return intArrayOf(precision, w, h, comps)
                    }
                    if (marker == 0xDA) return null
                    i += 2 + len
                }
                return null
            }
        }
    }

    /** 同步写 PDF；返回总字节数。quality 仅作用于位图页（97 ≈ 视觉无损） */
    fun write(
        out: File,
        pages: List<Page>,
        quality: Int = 97,
        onPageDone: ((Int) -> Unit)? = null,
    ): Long {
        val q = quality.coerceIn(60, 100)
        FileOutputStream(out).use { fos ->
            val w = CountingWriter(fos)

            val offsets = HashMap<Int, Long>()
            var nextObj = 1
            fun alloc(): Int = nextObj++

            val catalogId = alloc()
            val pagesId = alloc()
            class PageObjs(val page: Int, val content: Int, val image: Int)
            val metas = ArrayList<PageObjs>(pages.size)
            for (p in pages) {
                metas.add(PageObjs(alloc(), alloc(), alloc()))
            }

            fun beginObj(id: Int, body: String) {
                offsets[id] = w.pos
                w.str("$id 0 obj\n$body")
            }

            fun streamObj(id: Int, dict: String, data: ByteArray) {
                offsets[id] = w.pos
                w.str("$id 0 obj\n<< $dict /Length ${data.size} >>\nstream\n")
                w.bytes(data)
                w.str("\nendstream\nendobj\n")
            }

            w.str("%PDF-1.4\n")
            w.bytes(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))

            beginObj(catalogId, "<< /Type /Catalog /Pages $pagesId 0 R >>\nendobj\n")

            val kids = metas.joinToString(" ") { "${it.page} 0 R" }
            beginObj(pagesId, "<< /Type /Pages /Count ${pages.size} /Kids [ $kids ] >>\nendobj\n")

            for ((idx, p) in pages.withIndex()) {
                val m = metas[idx]
                val jpeg: ByteArray?
                val imgW: Int
                val imgH: Int
                val comps: Int
                if (p.jpegBytes != null) {
                    jpeg = p.jpegBytes
                    imgW = p.jpegWidth
                    imgH = p.jpegHeight
                    comps = p.jpegComponents
                } else {
                    val bmp = p.bitmap ?: continue
                    val bos = ByteArrayOutputStream(1 shl 18)
                    bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
                    jpeg = bos.toByteArray()
                    imgW = bmp.width
                    imgH = bmp.height
                    comps = 3
                }

                beginObj(
                    m.page,
                    "<< /Type /Page /Parent $pagesId 0 R /MediaBox [0 0 $imgW $imgH] " +
                        "/Resources << /XObject << /Im0 ${m.image} 0 R >> >> /Contents ${m.content} 0 R >>\nendobj\n"
                )

                val content = "q $imgW 0 0 $imgH 0 0 cm /Im0 Do Q\n".toByteArray(Charsets.US_ASCII)
                streamObj(m.content, "/Filter /FlateDecode", deflate(content))

                val cs = if (comps == 1) "/DeviceGray" else "/DeviceRGB"
                streamObj(
                    m.image,
                    "/Type /XObject /Subtype /Image /Width $imgW /Height $imgH " +
                        "/ColorSpace $cs /BitsPerComponent 8 /Filter /DCTDecode",
                    jpeg,
                )

                // 编码完即释放位图（调用方无需再管）
                if (p.jpegBytes == null) runCatching { p.bitmap?.recycle() }
                onPageDone?.invoke(idx + 1)
            }

            val xrefPos = w.pos
            w.str("xref\n0 $nextObj\n")
            w.str("0000000000 65535 f \n")
            for (id in 1 until nextObj) {
                val off = offsets[id] ?: 0L
                w.str(String.format(Locale.US, "%010d 00000 n \n", off))
            }
            w.str("trailer\n<< /Size $nextObj /Root $catalogId 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")

            return w.pos
        }
    }

    private class CountingWriter(private val out: FileOutputStream) {
        var pos: Long = 0L
            private set

        fun bytes(b: ByteArray) {
            out.write(b)
            pos += b.size
        }

        fun str(s: String) {
            bytes(s.toByteArray(Charsets.US_ASCII))
        }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }
}
