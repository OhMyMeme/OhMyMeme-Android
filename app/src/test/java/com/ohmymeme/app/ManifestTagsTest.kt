package com.ohmymeme.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ManifestTagsTest {

    private fun entry(filename: String = "a.png", tags: Any? = null): JSONObject {
        val e = JSONObject().put("filename", filename)
        if (tags != null) e.put("tags", tags)
        return e
    }

    @Test
    fun entryTagsUsedWhenPresent() {
        val tags = JSONArray().put("cat").put("dog")
        assertEquals(listOf("cat", "dog"), CloudSync.tagsFromEntry(entry(tags = tags), null))
    }

    @Test
    fun emptyEntryTagsDoNotFallBackToLegacyTagMap() {
        val legacy = JSONObject().put("a.png", JSONArray().put("old"))
        assertEquals(emptyList<String>(), CloudSync.tagsFromEntry(entry(tags = JSONArray()), legacy))
    }

    @Test
    fun fallsBackToLegacyTagMapWhenEntryHasNoTags() {
        val legacy = JSONObject().put("a.png", JSONArray().put("old"))
        assertEquals(listOf("old"), CloudSync.tagsFromEntry(entry(), legacy))
    }

    @Test
    fun missingBothReturnsEmpty() {
        assertEquals(emptyList<String>(), CloudSync.tagsFromEntry(entry(), null))
    }

    @Test
    fun nonStringAndBlankFilteredAndTrimmed() {
        val tags = JSONArray().put(" ok ").put("").put(42).put(JSONObject.NULL)
        assertEquals(listOf("ok"), CloudSync.tagsFromEntry(entry(tags = tags), null))
    }

    @Test
    fun safeFilenameRejectsTraversal() {
        assertEquals(false, CloudSync.isSafeRemoteFname("../evil.png"))
        assertEquals(false, CloudSync.isSafeRemoteFname("a/b.png"))
        assertEquals(true, CloudSync.isSafeRemoteFname("a.png"))
    }
}
