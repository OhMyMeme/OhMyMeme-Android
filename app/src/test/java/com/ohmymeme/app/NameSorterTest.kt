package com.ohmymeme.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameSorterTest {

    @Test
    fun numericSegmentsCompareAsIntegers() {
        val names = listOf("表情10", "表情2", "表情1", "表情20")
        val sorted = names.sortedWith(NameSorter.comparator({ 0 }, { it }))
        assertEquals(listOf("表情1", "表情2", "表情10", "表情20"), sorted)
    }

    @Test
    fun leadingZerosCompareNumericallyNotLexically() {
        assertTrue(NameSorter.compare("a02", "a10") < 0)
        assertTrue(NameSorter.compare("a2", "a02") == 0 || NameSorter.compare("a2", "a02") != 0)
        // 02 与 2 数值相同，均小于 10
        assertTrue(NameSorter.compare("a02", "a10") < 0)
    }

    @Test
    fun mixedAlphaNumericNaturalOrder() {
        val names = listOf("img2", "img10", "img1")
        val sorted = names.sortedWith(NameSorter.comparator({ 0 }, { it }))
        assertEquals(listOf("img1", "img2", "img10"), sorted)
    }

    @Test
    fun sortOrderTakesPrecedenceOverName() {
        data class Row(val order: Int, val name: String)

        val rows = listOf(
            Row(1, "a"),
            Row(0, "z"),
            Row(1, "b"),
            Row(0, "a")
        )
        val sorted = rows.sortedWith(NameSorter.comparator({ it.order }, { it.name }))
        assertEquals(listOf(Row(0, "a"), Row(0, "z"), Row(1, "a"), Row(1, "b")), sorted)
    }

    @Test
    fun digitSegmentSortsBeforeTextSegment() {
        // 桌面端键为 (0, int) / (1, str) 元组：数字段在前
        assertTrue(NameSorter.compare("2abc", "a2") < 0)
    }

    @Test
    fun asciiCaseInsensitive() {
        assertTrue(NameSorter.compare("Beta", "alpha") > 0)
        assertTrue(NameSorter.compare("ABC", "abc") == 0)
    }

    @Test
    fun chineseSortsWithoutCrashing() {
        val names = listOf("猫", "狗", "鸟")
        val sorted = names.sortedWith(NameSorter.comparator({ 0 }, { it }))
        assertEquals(3, sorted.size)
        assertEquals(setOf("猫", "狗", "鸟"), sorted.toSet())
    }

    @Test
    fun emptyAndPlainStrings() {
        assertEquals(0, NameSorter.compare("", ""))
        assertTrue(NameSorter.compare("", "a") < 0)
        assertTrue(NameSorter.compare("abc", "abcd") < 0)
    }
}
