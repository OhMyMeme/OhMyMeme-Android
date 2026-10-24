package com.ohmymeme.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigDefaultsTest {

    @Test
    fun copyModeDefaultsToSuffixGif() {
        assertEquals(4, ConfigStore.DEFAULTS["copy_resize_mode"])
    }

    @Test
    fun manifestCollectionsIncludedByDefault() {
        assertEquals(true, ConfigStore.DEFAULTS["manifest_include_collections"])
    }

    @Test
    fun webdavCertificateCheckEnabledByDefault() {
        assertEquals(false, ConfigStore.DEFAULTS["webdav_allow_insecure"])
    }

    @Test
    fun gifSuffixKeepsStem() {
        assertEquals("a.gif", MemeCopyProcessor.gifSuffixName("a.png"))
        assertEquals("a.b.gif", MemeCopyProcessor.gifSuffixName("a.b.webp"))
        assertEquals("noext.gif", MemeCopyProcessor.gifSuffixName("noext"))
        assertEquals("image.gif", MemeCopyProcessor.gifSuffixName(".gif"))
    }
}
