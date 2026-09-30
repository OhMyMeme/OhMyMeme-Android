package com.ohmymeme.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多帧 GIF 编码单测：结构（GIF89a/NETSCAPE/GCE 延时与透明/trailer）、
 * 首帧经 GifFrameDecoder 回环、透明像素保留。
 */
class GifEncoderAnimatedTest {

    /** fn 返回 ARGB（含 alpha 字节） */
    private fun rgba(w: Int, h: Int, fn: (Int) -> Int): ByteArray {
        val out = ByteArray(w * h * 4)
        for (i in 0 until w * h) {
            val c = fn(i)
            out[i * 4] = (c ushr 16 and 0xFF).toByte()
            out[i * 4 + 1] = (c ushr 8 and 0xFF).toByte()
            out[i * 4 + 2] = (c and 0xFF).toByte()
            out[i * 4 + 3] = ((c ushr 24) and 0xFF).toByte()
        }
        return out
    }

    /** 遍历 GIF 块结构，收集每个 GCE 的 (delayCs, packed, transparentIndex) */
    private fun walkGce(gif: ByteArray): List<Triple<Int, Int, Int>> {
        val out = mutableListOf<Triple<Int, Int, Int>>()
        var pos = 6 + 7 // header + LSD，无 GCT
        while (pos < gif.size) {
            when (gif[pos].toInt() and 0xFF) {
                0x21 -> {
                    val label = gif[pos + 1].toInt() and 0xFF
                    var p = pos + 2
                    val blockSize = gif[p].toInt() and 0xFF
                    p++
                    if (label == 0xF9) {
                        val delay = (gif[p + 1].toInt() and 0xFF) or ((gif[p + 2].toInt() and 0xFF) shl 8)
                        out.add(Triple(delay, gif[p].toInt() and 0xFF, gif[p + 3].toInt() and 0xFF))
                    }
                    p += blockSize
                    while (p < gif.size && gif[p].toInt() != 0) {
                        p += 1 + (gif[p].toInt() and 0xFF)
                    }
                    pos = p + 1
                }
                0x2C -> {
                    val imgPacked = gif[pos + 9].toInt() and 0xFF
                    var p = pos + 10
                    if (imgPacked and 0x80 != 0) p += (1 shl ((imgPacked and 0x07) + 1)) * 3
                    p++ // min code size
                    while (p < gif.size && gif[p].toInt() != 0) {
                        p += 1 + (gif[p].toInt() and 0xFF)
                    }
                    pos = p + 1
                }
                0x3B -> return out
                else -> pos++
            }
        }
        return out
    }

    @Test
    fun encodesAnimatedStructure() {
        val w = 4
        val h = 4
        val f0 = rgba(w, h) { 0xFFFF0000.toInt() }
        val f1 = rgba(w, h) { 0xFF00FF00.toInt() }
        val gif = GifEncoder.encodeAnimated(listOf(f0, f1), listOf(40, 0), w, h)
        assertEquals("GIF89a", String(gif, 0, 6, Charsets.ISO_8859_1))
        assertEquals(w, gif[6].toInt() and 0xFF or ((gif[7].toInt() and 0xFF) shl 8))
        assertTrue("应含 NETSCAPE2.0 循环块", String(gif, Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
        assertEquals("trailer", 0x3B, gif[gif.size - 1].toInt() and 0xFF)
        val gce = walkGce(gif)
        assertEquals("每帧一个 GCE", 2, gce.size)
        assertEquals("40ms → 4cs", 4, gce[0].first)
        assertEquals("延时下限 2cs", 2, gce[1].first)
        assertTrue("disposal=保留(1)", gce[0].second shr 2 and 0x07 == 1)
    }

    @Test
    fun firstFrameDecodesExactly() {
        val w = 8
        val h = 8
        val colors = intArrayOf(0xFF0000, 0x0000FF)
        val f0 = rgba(w, h) { i -> (0xFF shl 24) or colors[i % 2] }
        val f1 = rgba(w, h) { 0xFFFFFFFF.toInt() }
        val gif = GifEncoder.encodeAnimated(listOf(f0, f1), listOf(50, 50), w, h)
        val dec = GifFrameDecoder.decode(gif)
        assertNotNull(dec)
        assertEquals(w, dec!!.width)
        assertEquals(h, dec.height)
        val exp = ByteArray(w * h * 3)
        for (i in 0 until w * h) {
            val c = colors[i % 2]
            exp[i * 3] = (c ushr 16 and 0xFF).toByte()
            exp[i * 3 + 1] = (c ushr 8 and 0xFF).toByte()
            exp[i * 3 + 2] = (c and 0xFF).toByte()
        }
        assertArrayEquals("首帧应逐字节回环", exp, dec.rgb)
    }

    @Test
    fun transparentPixelsKeepGceTransparency() {
        val w = 4
        val h = 4
        val f0 = rgba(w, h) { i -> if (i == 0) 0 else 0xFFFF0000.toInt() }
        val gif = GifEncoder.encodeAnimated(listOf(f0), listOf(40), w, h)
        val gce = walkGce(gif)
        assertEquals(1, gce.size)
        val (delay, packed, _) = gce[0]
        assertEquals(4, delay)
        assertTrue("透明位应置 1", packed and 0x01 == 1)
        val dec = GifFrameDecoder.decode(gif)!!
        // 透明索引直映射调色板 RGB：未写入的透明色槽位为 0,0,0
        assertEquals(0, dec.rgb[0].toInt())
        assertEquals(0, dec.rgb[1].toInt())
        assertEquals(0, dec.rgb[2].toInt())
        assertEquals(0xFF.toByte(), dec.rgb[3])
        assertEquals(0x00.toByte(), dec.rgb[4])
        assertEquals(0x00.toByte(), dec.rgb[5])
    }

    @Test
    fun allOpaqueFramesHaveNoTransparencyBit() {
        val f0 = rgba(2, 2) { (0xFF shl 24) or 0x123456 }
        val gif = GifEncoder.encodeAnimated(listOf(f0), listOf(100), 2, 2)
        val gce = walkGce(gif)
        assertEquals(1, gce.size)
        assertTrue("不透明帧透明位应为 0", gce[0].second and 0x01 == 0)
    }
}
