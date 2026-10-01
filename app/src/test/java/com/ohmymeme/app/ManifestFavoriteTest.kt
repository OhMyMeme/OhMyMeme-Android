package com.ohmymeme.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ManifestFavoriteTest {

    private fun manifest(favorite: Any? = null): JSONObject {
        val data = JSONObject().put("version", 3)
        if (favorite != null) data.put("favorite", favorite)
        return data
    }

    @Test
    fun missingKeyReturnsEmpty() {
        assertEquals(emptyList<String>(), CloudSync.favoriteFilenamesFrom(manifest()))
    }

    @Test
    fun nonListValueReturnsEmpty() {
        assertEquals(emptyList<String>(), CloudSync.favoriteFilenamesFrom(manifest("a.png")))
        assertEquals(
            emptyList<String>(),
            CloudSync.favoriteFilenamesFrom(manifest(JSONObject()))
        )
    }

    @Test
    fun nonStringElementsFiltered() {
        val favorite = JSONArray().put("a.png").put(42).put(JSONObject.NULL)
        assertEquals(listOf("a.png"), CloudSync.favoriteFilenamesFrom(manifest(favorite)))
    }

    @Test
    fun unsafeFilenamesRejected() {
        val favorite = JSONArray()
            .put("../evil.png")
            .put("..\\evil.png")
            .put("a/b.png")
            .put("/abs.png")
            .put(".hidden.png")
            .put("ok.png")
        assertEquals(listOf("ok.png"), CloudSync.favoriteFilenamesFrom(manifest(favorite)))
    }

    @Test
    fun safeFilenamesKeptInOrder() {
        val favorite = JSONArray().put("b.png").put("a.png")
        assertEquals(listOf("b.png", "a.png"), CloudSync.favoriteFilenamesFrom(manifest(favorite)))
    }
}
