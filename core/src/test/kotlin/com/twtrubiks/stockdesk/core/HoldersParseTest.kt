package com.twtrubiks.stockdesk.core

import com.twtrubiks.stockdesk.core.holders.HoldersException
import com.twtrubiks.stockdesk.core.holders.HoldersSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 集保頁面的解析。`formPageHasTokenAndDates`、`weekPageHasFifteenLevels` 吃錄好的真實回應（沒錄就 skip）；
 * 其他用 [Samples] 造的頁面，測 15 級怎麼合併成 6 組、差異數調整、token 失效。
 */
class HoldersParseTest {
    private val weeks = listOf("20261106", "20261030")

    @Test
    fun noSuchStock() {
        assertNull(HoldersSource.parseWeek("<html>查無此資料</html>", "20261031"))
        assertNull(HoldersSource.parseWeek(Samples.tdccNotFound("t", weeks), "20261106"))
    }

    @Test
    fun fifteenLevelsMergeIntoSixGroups() {
        val levels = Samples.tdccLevels(bigPct = 71.25)
        val week = HoldersSource.parseWeek(Samples.tdccWeek("t", weeks, "20261106", levels), "20261106")!!
        assertEquals("2026-11-06", week.date)
        assertEquals((1..15).toList(), week.levels.map { it.level })
        assertEquals(Samples.LEVEL_LABELS, week.levels.map { it.label })
        assertEquals(levels.map { it.shares }, week.levels.map { it.shares })

        assertEquals(listOf("≤10 張", "10–50 張", "50–100 張", "100–400 張", "400–1000 張", ">1000 張"), week.groups.map { it.label })
        // 1–3 級 ≤10、4–8 級 10–50、9 級 50–100、10–11 級 100–400、12–14 級 400–1000、15 級 >1000
        val ranges = listOf(0..2, 3..7, 8..8, 9..10, 11..13, 14..14)
        ranges.zip(week.groups).forEach { (range, group) ->
            assertEquals(group.label, range.sumOf { levels[it].holders }, group.holders)
            assertEquals(group.label, range.sumOf { levels[it].shares }, group.shares)
            assertEquals(group.label, range.sumOf { levels[it].pct }, group.pct, 1e-9)
        }
        assertEquals(71.25, week.bigPct, 1e-9)
        assertEquals(100.0, week.groups.sumOf { it.pct }, 0.01)
        // 合計列
        assertEquals(levels.sumOf { it.holders }, week.holders)
        assertEquals(levels.sumOf { it.shares }, week.shares)
    }

    @Test
    fun adjustmentRowIsSkipped() {
        val levels = Samples.tdccLevels()
        val week = HoldersSource.parseWeek(Samples.tdccWeek("t", weeks, "20261106", levels, adjustment = true), "20261106")!!
        assertEquals(15, week.levels.size)
        assertEquals(levels.sumOf { it.holders }, week.holders)
    }

    @Test
    fun emptyTableMeansTokenExpired() {
        val e = assertThrows(HoldersException::class.java) { HoldersSource.parseWeek(Samples.tdccExpired("t", weeks), "20261106") }
        assertTrue(e.message!!, "token" in e.message!!)
    }

    @Test
    fun missingLevelsMeansThePageChanged() {
        val page = Samples.tdccWeek("t", weeks, "20261106").replace(Regex("<tr><td>7</td>.*?</tr>"), "")
        val e = assertThrows(HoldersException::class.java) { HoldersSource.parseWeek(page, "20261106") }
        assertTrue(e.message!!, "14 級" in e.message!!)
    }

    @Test
    fun formPageDatesAndToken() {
        val page = Samples.tdccForm("abc123", weeks).replace("<select name=\"scaDate\" id=\"scaDate\">", "<select name=\"scaDate\" id=\"scaDate\"><option value=\"\">請選擇</option>")
        assertEquals(weeks, HoldersSource.parseDates(page))
        assertEquals("abc123", HoldersSource.parseToken(page))
    }

    @Test
    fun tokenAttributeOrderDoesNotMatter() {
        assertEquals("x1", HoldersSource.parseToken("""<input type="hidden" name="SYNCHRONIZER_TOKEN" value="x1">"""))
        assertEquals("x2", HoldersSource.parseToken("""<input name="SYNCHRONIZER_TOKEN" id="SYNCHRONIZER_TOKEN" type="hidden" value="x2" />"""))
        assertEquals("x3", HoldersSource.parseToken("""<input value="x3" name='SYNCHRONIZER_TOKEN'>"""))
        assertNull(HoldersSource.parseToken("""<input name="SYNCHRONIZER_TOKEN" value="">"""))
        assertNull(HoldersSource.parseToken("<html></html>"))
    }

    @Test
    fun formPageHasTokenAndDates() {
        val page = Fixtures.require("tdcc-form.html")
        val dates = HoldersSource.parseDates(page)
        assertTrue("約 52 週，實得 ${dates.size}", dates.size in 40..60)
        assertTrue(dates.all { it.length == 8 })
        assertEquals("新到舊", dates.sortedDescending(), dates)
        assertNotNull("讀不到 SYNCHRONIZER_TOKEN", HoldersSource.parseToken(page))
    }

    @Test
    fun weekPageHasFifteenLevels() {
        val page = Fixtures.require("tdcc-week.html")
        val week = HoldersSource.parseWeek(page, "20261031")!!
        assertEquals(15, week.levels.size)
        assertEquals(6, week.groups.size)
        assertTrue("比例加總接近 100，實得 ${week.groups.sumOf { it.pct }}", week.groups.sumOf { it.pct } in 99.0..101.0)
        assertNotNull("結果頁要帶下一發的 token", HoldersSource.parseToken(page))
    }
}
