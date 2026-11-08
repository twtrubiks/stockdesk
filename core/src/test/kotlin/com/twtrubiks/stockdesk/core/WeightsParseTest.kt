package com.twtrubiks.stockdesk.core

import com.twtrubiks.stockdesk.core.weights.WeightsException
import com.twtrubiks.stockdesk.core.weights.WeightsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 期交所權重頁的解析。`realPage` 吃錄好的真實回應（沒錄就 skip）；其他用 [Samples] 造的頁面，
 * 測左右兩欄、千分位排行、帶符號的名稱、健全檢查。
 */
class WeightsParseTest {
    private val date = LocalDate.of(2026, 10, 31)
    private val rows = Samples.constituents()

    private fun parse(rows: List<Samples.Constituent>, leftRows: Int = 535) = WeightsSource.parse(Samples.taifexWeights(date, rows, leftRows))

    private fun rejected(rows: List<Samples.Constituent>): String =
        assertThrows(WeightsException::class.java) { parse(rows) }.message!!

    @Test
    fun twoColumnLayoutComesOutInRankOrder() {
        val (d, parsed) = parse(rows)
        assertEquals("2026-10-31", d)
        assertEquals(rows.size, parsed.size)
        assertEquals((1..rows.size).toList(), parsed.map { it.rank })
        assertEquals(rows.map { it.code }, parsed.map { it.code })
        assertEquals(rows.map { it.weight }, parsed.map { it.weight })
        // 右欄的第一筆（536）接在左欄最後一筆（535）後面
        assertEquals(rows[535].code, parsed[535].code)
    }

    @Test
    fun ranksOverAThousandAndNamesWithSymbols() {
        val (_, parsed) = parse(rows)
        assertEquals(1_068, parsed[1_067].rank) // 頁面上是「1,068」
        assertEquals("國巨*", parsed.first { it.code == "2327" }.name)
        assertEquals("正瀚-創", parsed.first { it.code == "6829" }.name)
    }

    @Test
    fun singleColumnAlsoWorks() {
        assertEquals(rows.size, parse(rows, leftRows = rows.size).second.size)
    }

    @Test
    fun brokenPageIsRejected() {
        assertThrows(WeightsException::class.java) { WeightsSource.parse("<html>資料日期：2026/10/31<table></table></html>") }
        val noDate = Samples.taifexWeights(date, rows).replace("資料日期", "日期")
        assertTrue(assertThrows(WeightsException::class.java) { WeightsSource.parse(noDate) }.message!!.contains("資料日期"))
    }

    @Test
    fun tooFewRowsIsRejected() {
        assertTrue(rejected(Samples.constituents(400)).contains("只解析出 400 筆"))
    }

    @Test
    fun gapInRanksIsRejected() {
        assertTrue(rejected(rows.filter { it.rank != 600 }).contains("排行不連續"))
    }

    @Test
    fun weightsNotSummingToAHundredAreRejected() {
        assertTrue(rejected(rows.map { it.copy(weight = it.weight * 1.05) }).contains("比重加總"))
    }

    @Test
    fun realPage() {
        val (date, rows) = WeightsSource.parse(Fixtures.require("taifex-weights.html"))
        assertTrue(date.startsWith("20"))
        assertEquals("2330", rows.first().code)
        assertTrue(rows.size >= WeightsSource.MIN_ROWS)
    }
}
