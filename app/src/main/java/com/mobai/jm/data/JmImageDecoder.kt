package com.mobai.jm.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.security.MessageDigest

/**
 * 禁漫图片乱序还原。
 * 与用户已验证的 PHP 实现（index.php ScrambleDecoder）完全一致：
 *  - 分段数：photoId < scrambleId → 0（老图不处理）；< 268850 → 10；
 *    否则 n = ord(md5(photoId+filename)[-1]) % (10 或 8)，segments = n*2+2
 *  - 还原：把图片按 segments 水平切带，再做"垂直方向带级重排"（含 over 余数处理）
 */
object JmImageDecoder {

    private const val SCRAMBLE_268850 = 268850
    private const val SCRAMBLE_421926 = 421926

    /** 计算分段数（0 表示不需要解码） */
    fun segments(scrambleId: Int, photoId: String, filename: String): Int {
        val pid = photoId.toLongOrNull() ?: return 0
        if (pid < scrambleId) return 0
        if (pid < SCRAMBLE_268850) return 10
        val x = if (pid < SCRAMBLE_421926) 10 else 8
        // ★ 官方算法参与 md5 的文件名是【不带扩展名】的
        // （jmcomic 源码：self.img_file_name: str = img_file_name  # without suffix）
        val nameNoSuffix = filename.substringBeforeLast('.')
        val h = md5Hex("$pid$nameNoSuffix")
        val n = h.last().code % x
        return n * 2 + 2
    }

    /** 还原乱序图片（segments <= 0 时原样返回） */
    fun decode(input: Bitmap, segments: Int): Bitmap {
        if (segments <= 0) return input
        val w = input.width
        val h = input.height
        if (w <= 0 || h <= 0 || h < segments) return input

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        val over = h % segments
        for (i in 0 until segments) {
            var move = h / segments
            var ySrc = h - move * (i + 1) - over
            var yDst = move * i
            if (i == 0) {
                move += over
            } else {
                yDst += over
            }
            if (move <= 0) continue
            ySrc = ySrc.coerceAtLeast(0)
            canvas.drawBitmap(
                input,
                Rect(0, ySrc, w, ySrc + move),
                Rect(0, yDst, w, yDst + move),
                paint,
            )
        }
        return out
    }

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
