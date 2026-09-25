package com.ohmymeme.app

import java.io.ByteArrayOutputStream

/**
 * 动画 WebP 容器解析（RIFF/VP8X/ANIM/ANMF），纯 JVM 可单测。
 * WebP 动画每帧在 ANMF 内是独立完整位流（可选 ALPH + VP8/VP8L），
 * 可逐帧重封为独立 WebP 交给 BitmapFactory 解码，再按混合/处置语义合成。
 * 对应桌面端 clipboard_util.py _animated_webp_to_gif 的 Pillow 帧迭代。
 */
object WebpAnim {

    class Frame(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val durationMs: Int,
        val noBlend: Boolean,
        val disposeBg: Boolean,
        val alpha: ByteArray?,
        val imgType: String,
        val img: ByteArray
    )

    class Animation(
        val width: Int,
        val height: Int,
        val loop: Int,
        val background: Int,
        val frames: List<Frame>
    )

    /** 解析动画 WebP；非动画/结构非法返回 null */
    fun parse(data: ByteArray): Animation? {
        if (data.size < 12) return null
        if (String(data, 0, 4, Charsets.ISO_8859_1) != "RIFF") return null
        if (String(data, 8, 4, Charsets.ISO_8859_1) != "WEBP") return null
        var canvasW = 0
        var canvasH = 0
        var loop = 0
        var background = 0
        val frames = mutableListOf<Frame>()
        var i = 12
        while (i + 8 <= data.size) {
            val fourcc = String(data, i, 4, Charsets.ISO_8859_1)
            val size = le32(data, i + 4)
            if (size < 0 || i + 8 + size > data.size) return null
            val p = i + 8
            when (fourcc) {
                "VP8X" -> {
                    if (size < 10) return null
                    if (data[p].toInt() and 0x02 == 0) return null
                    canvasW = le24(data, p + 4) + 1
                    canvasH = le24(data, p + 7) + 1
                }
                "ANIM" -> if (size >= 6) {
                    val b = data[p].toInt() and 0xFF
                    val g = data[p + 1].toInt() and 0xFF
                    val r = data[p + 2].toInt() and 0xFF
                    val a = data[p + 3].toInt() and 0xFF
                    background = (a shl 24) or (r shl 16) or (g shl 8) or b
                    loop = le16(data, p + 4)
                }
                "ANMF" -> frames.add(parseAnmf(data, p, size) ?: return null)
            }
            i = p + size + (size and 1)
        }
        if (canvasW <= 0 || canvasH <= 0 || frames.isEmpty()) return null
        return Animation(canvasW, canvasH, loop, background, frames)
    }

    private fun parseAnmf(data: ByteArray, p: Int, size: Int): Frame? {
        if (size < 16) return null
        val x = le24(data, p)
        val y = le24(data, p + 3)
        val w = le24(data, p + 6) + 1
        val h = le24(data, p + 9) + 1
        val dur = le24(data, p + 12)
        val flags = data[p + 15].toInt() and 0xFF
        var alpha: ByteArray? = null
        var imgType = ""
        var img: ByteArray? = null
        var j = p + 16
        val end = p + size
        while (j + 8 <= end) {
            val cc = String(data, j, 4, Charsets.ISO_8859_1)
            val sz = le32(data, j + 4)
            if (sz < 0 || j + 8 + sz > end) return null
            when (cc) {
                "ALPH" -> alpha = data.copyOfRange(j + 8, j + 8 + sz)
                "VP8 ", "VP8L" -> {
                    imgType = cc
                    img = data.copyOfRange(j + 8, j + 8 + sz)
                }
            }
            j += 8 + sz + (sz and 1)
        }
        if (img == null) return null
        return Frame(x, y, w, h, dur, flags and 0x02 != 0, flags and 0x01 != 0, alpha, imgType, img)
    }

    /** 把 ANMF 单帧重封为独立可解码的 WebP 字节（帧区域置于 0,0 画布） */
    fun wrapFrame(frame: Frame): ByteArray {
        val inner = ByteArrayOutputStream()
        fun chunk(fourcc: String, payload: ByteArray) {
            inner.write(fourcc.toByteArray(Charsets.ISO_8859_1))
            le32(inner, payload.size)
            inner.write(payload)
            if (payload.size and 1 == 1) inner.write(0)
        }
        frame.alpha?.let { chunk("ALPH", it) }
        chunk(frame.imgType, frame.img)
        val body = inner.toByteArray()

        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.ISO_8859_1))
        val vp8x = if (frame.alpha != null) 18 else 0
        le32(out, 4 + vp8x + body.size)
        out.write("WEBP".toByteArray(Charsets.ISO_8859_1))
        if (frame.alpha != null) {
            out.write("VP8X".toByteArray(Charsets.ISO_8859_1))
            le32(out, 10)
            val vp = ByteArray(10)
            vp[0] = 0x10 // Alpha 标志
            putLe24(vp, 4, frame.width - 1)
            putLe24(vp, 7, frame.height - 1)
            out.write(vp)
        }
        out.write(body)
        return out.toByteArray()
    }

    private fun le16(d: ByteArray, i: Int): Int =
        (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)

    private fun le24(d: ByteArray, i: Int): Int =
        (d[i].toInt() and 0xFF) or
            ((d[i + 1].toInt() and 0xFF) shl 8) or
            ((d[i + 2].toInt() and 0xFF) shl 16)

    private fun le32(d: ByteArray, i: Int): Int =
        (d[i].toInt() and 0xFF) or
            ((d[i + 1].toInt() and 0xFF) shl 8) or
            ((d[i + 2].toInt() and 0xFF) shl 16) or
            ((d[i + 3].toInt() and 0xFF) shl 24)

    private fun le32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write(v ushr 8 and 0xFF)
        out.write(v ushr 16 and 0xFF)
        out.write(v ushr 24 and 0xFF)
    }

    private fun putLe24(d: ByteArray, i: Int, v: Int) {
        d[i] = (v and 0xFF).toByte()
        d[i + 1] = (v ushr 8 and 0xFF).toByte()
        d[i + 2] = (v ushr 16 and 0xFF).toByte()
    }
}
