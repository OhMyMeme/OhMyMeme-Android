package com.ohmymeme.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QqCacheImporterTest {

    private val root = "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/QQ_Favorite"

    private val pngMagic = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    @Test
    fun parseListOutputParsesSizeAndPath() {
        val out = "1234\t$root/a.gif\n2048\t$root/chatraw/b.png\n"
        val entries = QqCacheImporter.parseListOutput(out, root)
        assertEquals(2, entries.size)
        assertEquals(1234L, entries[0].size)
        assertEquals("$root/a.gif", entries[0].path)
        assertEquals(root, entries[0].root)
        assertEquals(2048L, entries[1].size)
    }

    @Test
    fun parseListOutputSkipsMalformedLines() {
        val out = buildString {
            appendLine("")                          // 空行
            appendLine("\t$root/x.gif")             // size 为空
            appendLine("notanumber\t$root/x.gif")   // size 非数字
            appendLine("12\t")                      // 缺路径
            appendLine("12\trelative/path")         // 相对路径
            appendLine("99\t$root/ok.gif")          // 正常
        }
        val entries = QqCacheImporter.parseListOutput(out, root)
        assertEquals(1, entries.size)
        assertEquals("$root/ok.gif", entries[0].path)
    }

    @Test
    fun parseListOutputSkipsNonImageExtensions() {
        val out = buildString {
            appendLine("1\t$root/clip.mp4")
            appendLine("2\t$root/notes.txt")
            appendLine("3\t$root/Cache_1")          // 无扩展名保留
            appendLine("4\t$root/unknown.xyz")      // 未知扩展名保留
            appendLine("5\t$root/UPPER.MP4")        // 大小写不敏感
        }
        val entries = QqCacheImporter.parseListOutput(out, root)
        assertEquals(listOf("$root/Cache_1", "$root/unknown.xyz"), entries.map { it.path })
    }

    @Test
    fun isSkippedFileMatchesOnlyDenyList() {
        assertTrue(QqCacheImporter.isSkippedFile("/a/b/x.mp4"))
        assertTrue(QqCacheImporter.isSkippedFile("/a/b/X.MP4"))
        assertFalse(QqCacheImporter.isSkippedFile("/a/b/x.gif"))
        assertFalse(QqCacheImporter.isSkippedFile("/a/b/Cache_1"))
        assertFalse(QqCacheImporter.isSkippedFile("/a/b/noext"))
    }

    @Test
    fun displayLabelStripsRootPrefixAndFormatsSize() {
        val entry = QqCacheImporter.Entry("$root/sub/x.gif", 1536L, root)
        assertEquals("sub/x.gif（1 KB）", QqCacheImporter.displayLabel(entry))
    }

    @Test
    fun displayLabelFallsBackToFileNameWhenOutsideRoot() {
        val entry = QqCacheImporter.Entry("/elsewhere/y.gif", 10L, root)
        assertEquals("y.gif（10 B）", QqCacheImporter.displayLabel(entry))
    }

    @Test
    fun formatSizeUsesUnits() {
        assertEquals("0 B", QqCacheImporter.formatSize(0))
        assertEquals("512 B", QqCacheImporter.formatSize(512))
        assertEquals("2 KB", QqCacheImporter.formatSize(2048))
        assertEquals("1.5 MB", QqCacheImporter.formatSize(1536L * 1024))
    }

    @Test
    fun shellQuoteNeutralizesSpecialCharacters() {
        val quoted = QqCacheImporter.shellQuote("a'b\$(rm -rf /);`id`")
        assertEquals("'a'\\''b\$(rm -rf /);`id`'", quoted)
    }

    @Test
    fun rootsCommandQuotesEachCandidate() {
        val cmd = QqCacheImporter.rootsCommand(listOf("/storage/emulated/0"))
        for (suffix in QqCacheImporter.ROOT_SUFFIXES) {
            assertTrue(cmd.contains("'/storage/emulated/0$suffix'"))
        }
        assertTrue(cmd.contains("[ -d \"\$d\" ]"))
        assertTrue(cmd.contains("sort -u"))
    }

    @Test
    fun listCommandQuotesRoot() {
        val cmd = QqCacheImporter.listCommand("/a/b'c")
        assertTrue(cmd.contains("find '/a/b'\\''c' -type f"))
        assertTrue(cmd.contains("stat -c %s"))
    }

    @Test
    fun exportNamePrefersMagicOverStoredExtension() {
        assertEquals("x.png", QqCacheImporter.exportName("x.jpg", pngMagic))
        assertEquals("a.b.png", QqCacheImporter.exportName("a.b.jpg", pngMagic))
    }

    @Test
    fun exportNameAddsExtensionToExtensionlessFile() {
        assertEquals("Cache_1.png", QqCacheImporter.exportName("Cache_1", pngMagic))
    }

    @Test
    fun exportNameFallsBackToOriginalExtensionWhenMagicUnknown() {
        assertEquals("photo.jpg", QqCacheImporter.exportName("photo.jpg", "hello".toByteArray()))
        assertEquals("Cache_2", QqCacheImporter.exportName("Cache_2", "hello".toByteArray()))
    }

    @Test
    fun exportMimeMatchesExtension() {
        assertEquals("image/jpeg", QqCacheImporter.exportMime("a.jpg"))
        assertEquals("image/jpeg", QqCacheImporter.exportMime("a.JPEG"))
        assertEquals("image/png", QqCacheImporter.exportMime("a.png"))
        assertEquals("image/gif", QqCacheImporter.exportMime("a.gif"))
        assertEquals("image/webp", QqCacheImporter.exportMime("a.webp"))
        assertEquals("image/bmp", QqCacheImporter.exportMime("a.bmp"))
        assertEquals("application/octet-stream", QqCacheImporter.exportMime("a.xyz"))
        assertEquals("application/octet-stream", QqCacheImporter.exportMime("noext"))
    }

    @Test
    fun candidateRootsCoverDesktopAndChatCache() {
        assertTrue(QqCacheImporter.ROOT_SUFFIXES.any { it.endsWith("/Tencent/QQ_Favorite") })
        assertTrue(QqCacheImporter.ROOT_SUFFIXES.any { it.endsWith("/Tencent/chatpic") })
        assertTrue(QqCacheImporter.ROOT_SUFFIXES.any { it.endsWith("/.emotionsm") })
    }

    @Test
    fun nomediaFilesAreSkipped() {
        assertTrue(QqCacheImporter.isSkippedFile("/a/b/.nomedia"))
        assertFalse(QqCacheImporter.isSkippedFile("/a/b/.NOMEDIA"))
        val out = "1\t$root/.nomedia\n2\t$root/a.gif\n"
        val entries = QqCacheImporter.parseListOutput(out, root)
        assertEquals(listOf("$root/a.gif"), entries.map { it.path })
    }

    @Test
    fun dirLabelStripsRootPrefix() {
        val nested = QqCacheImporter.Entry("$root/chatraw/x.gif", 1L, root)
        assertEquals("chatraw", QqCacheImporter.dirLabel(nested))
        val deep = QqCacheImporter.Entry("$root/a/b/c.gif", 1L, root)
        assertEquals("a/b", QqCacheImporter.dirLabel(deep))
        val atRoot = QqCacheImporter.Entry("$root/x.gif", 1L, root)
        assertEquals("QQ_Favorite", QqCacheImporter.dirLabel(atRoot))
        val outside = QqCacheImporter.Entry("/elsewhere/y.gif", 1L, root)
        assertEquals("/elsewhere", QqCacheImporter.dirLabel(outside))
    }

    @Test
    fun fileNameLabelShowsNameAndSize() {
        val entry = QqCacheImporter.Entry("$root/sub/x.gif", 1536L, root)
        assertEquals("x.gif（1 KB）", QqCacheImporter.fileNameLabel(entry))
    }
}
