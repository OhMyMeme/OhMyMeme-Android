package com.ohmymeme.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudDirectTest {

    private fun local(id: Long, filename: String): Meme = Meme(
        id = id,
        filename = filename,
        fileHash = "hash",
        originalName = filename.substringBeforeLast('.'),
        width = 10,
        height = 10,
        fileSize = 100,
        mimeType = "image/png",
        sortOrder = 0,
        stegoOfHash = null,
        fromStego = 0,
        createdAt = "",
        updatedAt = ""
    )

    private fun sha(c: Char = 'a') = c.toString().repeat(64)

    private fun entry(
        filename: String,
        sha: String = sha(),
        name: String? = null,
        tags: JSONArray? = null,
        collections: JSONArray? = null,
        favorite: Boolean = false
    ): JSONObject {
        val e = JSONObject().put("filename", filename).put("sha256", sha)
        if (name != null) e.put("name", name)
        if (tags != null) e.put("tags", tags)
        val manifest = JSONObject()
        val memes = JSONArray().put(e)
        if (collections != null) manifest.put("collections", collections)
        if (favorite) manifest.put("favorite", JSONArray().put(filename))
        manifest.put("memes", memes)
        return manifest
    }

    @Test
    fun mergePlacesNonManifestLocalsFirstThenInterleavesByManifestOrder() {
        val localRows = listOf(local(3, "c.png"), local(1, "a.png"))
        val cloudRows = listOf(
            CloudSync.cloudMeme(CloudSync.CloudEntry(1, "b.png", "b", sha('b'), emptyList(), false, emptyList()))
        )
        val order = listOf("a.png", "b.png")
        val merged = CloudSync.mergeCloudOrder(localRows, cloudRows, order)
        assertEquals(listOf("c.png", "a.png", "b.png"), merged.map { it.filename })
    }

    @Test
    fun mergeKeepsCloudRowsWithoutManifestAtTail() {
        val cloud = CloudSync.cloudMeme(CloudSync.CloudEntry(9, "x.png", "x", sha('c'), emptyList(), false, emptyList()))
        val merged = CloudSync.mergeCloudOrder(listOf(local(1, "a.png")), listOf(cloud), listOf("a.png"))
        assertEquals(listOf("a.png", "x.png"), merged.map { it.filename })
    }

    @Test
    fun cloudMissingSkipsLocalUnsafeAndMissingSha() {
        val manifest = JSONObject()
        manifest.put(
            "memes",
            JSONArray()
                .put(JSONObject().put("filename", "a.png").put("sha256", sha()))
                .put(JSONObject().put("filename", "b.png").put("sha256", sha('b')))
                .put(JSONObject().put("filename", "../evil.png").put("sha256", sha('e')))
                .put(JSONObject().put("filename", "c.png").put("sha256", "short"))
                .put(JSONObject().put("filename", ".hidden.png").put("sha256", sha('d')))
        )
        val missing = CloudSync.cloudMissing(manifest, setOf("a.png"))
        assertEquals(listOf("b.png"), missing.map { it.filename })
        assertEquals(1, missing[0].position)
    }

    @Test
    fun cloudMissingNameFallbackAndFavoriteAndCollections() {
        val collections = JSONArray().put(
            JSONObject()
                .put("name", "表情")
                .put("filenames", JSONArray().put("b.png"))
                .put(
                    "children",
                    JSONArray().put(
                        JSONObject().put("name", "子组").put("filenames", JSONArray().put("b.png"))
                    )
                )
        )
        val manifest = entry(
            filename = "b.png",
            sha = sha('b'),
            tags = JSONArray().put("cat"),
            collections = collections,
            favorite = true
        )
        val missing = CloudSync.cloudMissing(manifest, emptySet())
        assertEquals(1, missing.size)
        assertEquals("b", missing[0].name)
        assertEquals(listOf("cat"), missing[0].tags)
        assertTrue(missing[0].favorited)
        assertEquals(listOf("表情", "表情/子组"), missing[0].collections)
    }

    @Test
    fun cloudMemeUsesNegativePositionIdAndCloudFlag() {
        val meme = CloudSync.cloudMeme(CloudSync.CloudEntry(4, "e.png", "e", sha('e'), emptyList(), false, emptyList()))
        assertEquals(-5L, meme.id)
        assertTrue(meme.cloud)
        assertEquals("e.png", meme.filename)
        assertEquals(sha('e'), meme.fileHash)
    }

    @Test
    fun manifestOrderFollowsManifestSequence() {
        val manifest = JSONObject().put(
            "memes",
            JSONArray()
                .put(JSONObject().put("filename", "z.png"))
                .put(JSONObject().put("filename", "a.png"))
        )
        assertEquals(listOf("z.png", "a.png"), CloudSync.manifestOrder(manifest))
        assertEquals(emptyList<String>(), CloudSync.manifestOrder(null))
    }

    @Test
    fun safeShaRequiresLowercaseHex64() {
        assertTrue(CloudSync.isSafeSha(sha()))
        assertFalse(CloudSync.isSafeSha(sha().uppercase()))
        assertFalse(CloudSync.isSafeSha("abc"))
        assertFalse(CloudSync.isSafeSha(null))
        assertFalse(CloudSync.isSafeSha(sha('g')))
    }

    @Test
    fun configDefaultsEnableCloudDirectAndThumbAutoPush() {
        assertEquals(true, ConfigStore.DEFAULTS["cloud_direct"])
        assertEquals(true, ConfigStore.DEFAULTS["cloud_thumb_auto_push"])
    }
}
