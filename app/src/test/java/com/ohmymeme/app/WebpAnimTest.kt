package com.ohmymeme.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 动画 WebP 解析器单测。fixture anim_basic.webp：32x24、3 帧 40/60/100ms、
 * VP8X 标志 0x12（alpha+animation）、帧 1/3 noBlend(0x02)、帧 2 混合、均无 ALPH 子块。
 */
class WebpAnimTest {

    private fun fixture(): ByteArray = File("src/test/resources", "anim_basic.webp").readBytes()

    @Test
    fun parsesAnimatedFixture() {
        val anim = WebpAnim.parse(fixture())
        assertNotNull(anim)
        assertEquals(32, anim!!.width)
        assertEquals(24, anim.height)
        assertEquals(0, anim.loop)
        assertEquals(0, Color0(anim.background))
        assertEquals(3, anim.frames.size)
        assertEquals(listOf(40, 60, 100), anim.frames.map { it.durationMs })
        assertEquals(listOf(true, false, true), anim.frames.map { it.noBlend })
        assertTrue(anim.frames.none { it.disposeBg })
        for (f in anim.frames) {
            assertEquals(0, f.x)
            assertEquals(0, f.y)
            assertEquals(32, f.width)
            assertEquals(24, f.height)
            assertEquals("VP8L", f.imgType)
            assertNull(f.alpha)
            assertTrue(f.img.isNotEmpty())
        }
    }

    @Test
    fun rejectsNonAnimatedAndGarbage() {
        assertNull(WebpAnim.parse(ByteArray(0)))
        assertNull(WebpAnim.parse("hello, not a webp file".toByteArray()))
        val noAnimFlag = fixture()
        noAnimFlag[20] = (noAnimFlag[20].toInt() and 0xFD).toByte()
        assertNull("VP8X 无 Animation 标志应拒绝", WebpAnim.parse(noAnimFlag))
    }

    @Test
    fun wrapFrameProducesValidStandaloneWebp() {
        val anim = WebpAnim.parse(fixture())!!
        val frame = anim.frames[0]
        val wrapped = WebpAnim.wrapFrame(frame)
        assertEquals("RIFF", String(wrapped, 0, 4, Charsets.ISO_8859_1))
        assertEquals("WEBP", String(wrapped, 8, 4, Charsets.ISO_8859_1))
        val riffSize = (wrapped[4].toInt() and 0xFF) or
            ((wrapped[5].toInt() and 0xFF) shl 8) or
            ((wrapped[6].toInt() and 0xFF) shl 16) or
            ((wrapped[7].toInt() and 0xFF) shl 24)
        assertEquals("RIFF size 应等于文件长-8", wrapped.size - 8, riffSize)
        assertEquals("无 ALPH 子块时应为简单 RIFF/WEBP/VP8L", "VP8L", String(wrapped, 12, 4, Charsets.ISO_8859_1))
        val off = wrapped.size - frame.img.size - (frame.img.size and 1)
        assertArrayEquals("原帧位流应完整保留", frame.img, wrapped.copyOfRange(off, off + frame.img.size))
    }

    @Test
    fun wrapFrameWithAlphaAddsVp8x() {
        val anim = WebpAnim.parse(fixture())!!
        val f = anim.frames[0]
        val withAlpha = WebpAnim.Frame(f.x, f.y, f.width, f.height, f.durationMs, f.noBlend, f.disposeBg, ByteArray(4) { 0x08 }, f.imgType, f.img)
        val wrapped = WebpAnim.wrapFrame(withAlpha)
        assertEquals("VP8X", String(wrapped, 12, 4, Charsets.ISO_8859_1))
        assertTrue("应包含 ALPH 子块", String(wrapped, Charsets.ISO_8859_1).contains("ALPH"))
        val riffSize = (wrapped[4].toInt() and 0xFF) or
            ((wrapped[5].toInt() and 0xFF) shl 8) or
            ((wrapped[6].toInt() and 0xFF) shl 16) or
            ((wrapped[7].toInt() and 0xFF) shl 24)
        assertEquals(wrapped.size - 8, riffSize)
    }

    private fun Color0(argb: Int): Int = argb and 0x00FFFFFF
}
