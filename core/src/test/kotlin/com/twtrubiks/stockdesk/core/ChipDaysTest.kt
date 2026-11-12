package com.twtrubiks.stockdesk.core

import com.twtrubiks.stockdesk.core.chips.ChipDays
import com.twtrubiks.stockdesk.core.govbank.GovBankDay
import com.twtrubiks.stockdesk.core.margin.MarginDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** goodinfo 卡片的「融資融券・官股」：兩邊照日期併成一張表。 */
class ChipDaysTest {
    private fun margin(date: String) = MarginDay(date, 0, 0, 0, 90, 100, 1_000, 10.0, 0, 0, 0, 5, 6, 1_000, 0)
    private fun gov(date: String, lots: Long = 1) = GovBankDay.net(date, lots * 1_000, lots * 1e6)

    @Test
    fun sameDatesLineUp() {
        val rows = ChipDays.merge(listOf(margin("2026-11-10"), margin("2026-11-09")), listOf(gov("2026-11-10", 5), gov("2026-11-09", -3), gov("2026-11-06")), days = 2)
        assertEquals(listOf("2026-11-10", "2026-11-09"), rows.map { it.date })
        assertEquals(listOf(5L, -3L), rows.map { it.gov!!.netLots })
        assertTrue(rows.all { it.margin != null })
    }

    @Test
    fun govAheadOfMarginGoesOnTop() {
        // 傍晚：官股有今天了，融資融券還停在昨天
        val rows = ChipDays.merge(
            listOf(margin("2026-11-09"), margin("2026-11-06")),
            listOf(gov("2026-11-10", 7), gov("2026-11-09"), gov("2026-11-06", 2), gov("2026-11-05")),
            days = 2,
        )
        assertEquals(listOf("2026-11-10", "2026-11-09", "2026-11-06"), rows.map { it.date })
        assertNull(rows[0].margin)
        assertEquals(7L, rows[0].gov!!.netLots)
        assertEquals("最舊的那天也對得到", 2L, rows[2].gov!!.netLots)
    }

    @Test
    fun dayWithoutGovKeepsTheMarginRow() {
        val rows = ChipDays.merge(listOf(margin("2026-11-10"), margin("2026-11-09")), listOf(gov("2026-11-09")), days = 2)
        assertNull(rows[0].gov)
        assertEquals("2026-11-09", rows[1].gov!!.date)
        assertEquals(2, ChipDays.merge(listOf(margin("2026-11-10"), margin("2026-11-09")), emptyList(), days = 2).size)
    }

    @Test
    fun withoutMarginShowsTheLatestGovDays() {
        val rows = ChipDays.merge(emptyList(), listOf(gov("2026-11-10"), gov("2026-11-09"), gov("2026-11-06")), days = 2)
        assertEquals(listOf("2026-11-10", "2026-11-09"), rows.map { it.date })
        assertTrue(rows.all { it.margin == null })
        assertTrue(ChipDays.merge(emptyList(), emptyList(), days = 3).isEmpty())
    }
}
