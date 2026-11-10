package com.twtrubiks.stockdesk.core

import com.twtrubiks.stockdesk.core.govbank.GovBankDay
import com.twtrubiks.stockdesk.core.holders.HoldersSource
import com.twtrubiks.stockdesk.core.margin.MarginDay
import com.twtrubiks.stockdesk.core.model.Market
import com.twtrubiks.stockdesk.core.store.CacheStore
import com.twtrubiks.stockdesk.core.store.CacheStore.WeightRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** 快取的 SQL（JVM 上的 SQLite；Android 9 的 3.22 由 DeviceTest 在模擬器上再跑一次）。 */
class CacheStoreTest {
    private val sql = JdbcSql()
    private val cache = CacheStore(sql)
    private val day = LocalDate.of(2026, 11, 6)

    private fun margin(bal: Long) = MarginDay(
        date = day.toString(), finBuy = 1, finSell = 2, finRedeem = 3, finPrev = bal - 10, finBal = bal, finLimit = 9_000, finUse = 12.5,
        shortSell = 4, shortBuy = 5, shortRedeem = 6, shortPrev = 7, shortBal = 8, shortLimit = 9_000, offset = 9,
    )

    @Test
    fun marginDayStates() {
        assertNull("沒抓過", cache.marginDayState(Market.TWSE, day))
        cache.putMarginDay(Market.TWSE, day.minusDays(1), closed = true, rows = emptyMap())
        cache.putMarginDay(Market.TWSE, day, closed = false, rows = mapOf("2330" to margin(100)))
        assertEquals(true, cache.marginDayState(Market.TWSE, day.minusDays(1)))
        assertEquals(false, cache.marginDayState(Market.TWSE, day))
        assertNull("另一個市場分開記", cache.marginDayState(Market.TPEX, day))
    }

    @Test
    fun marginRowsRoundTripAndMerge() {
        cache.putMarginDay(Market.TWSE, day, closed = false, rows = mapOf("2330" to margin(100)))
        assertEquals(margin(100), cache.marginRow(Market.TWSE, day, "2330"))
        assertNull(cache.marginRow(Market.TWSE, day, "2317"))
        assertNull(cache.marginRow(Market.TPEX, day, "2330"))

        // 後來加的自選股重抓同一天：新的列加進去、原本的列換成新的
        cache.putMarginDay(Market.TWSE, day, closed = false, rows = mapOf("2330" to margin(200), "2317" to margin(50)))
        assertEquals(200L, cache.marginRow(Market.TWSE, day, "2330")!!.finBal)
        assertEquals(50L, cache.marginRow(Market.TWSE, day, "2317")!!.finBal)
    }

    @Test
    fun nullUsageSurvivesTheRoundTrip() {
        val row = margin(0).copy(finLimit = 0, finUse = null)
        cache.putMarginDay(Market.TPEX, day, closed = false, rows = mapOf("6515" to row))
        assertEquals(row, cache.marginRow(Market.TPEX, day, "6515"))
    }

    @Test
    fun markets() {
        assertNull(cache.market("2330"))
        cache.putMarket("2330", Market.TWSE)
        cache.putMarket("6515", Market.TPEX)
        cache.putMarket("2330", Market.TWSE)
        assertEquals(Market.TWSE, cache.market("2330"))
        assertEquals(Market.TPEX, cache.market("6515"))
    }

    @Test
    fun holdersWeeksRoundTrip() {
        val week = HoldersSource.parseWeek(Samples.tdccWeek("t", listOf("20261106"), "20261106"), "20261106")!!
        cache.putHoldersWeek("2330", week)
        assertEquals(week, cache.holdersWeek("2330", day))
        assertNull(cache.holdersWeek("2330", day.minusDays(7)))
        assertNull(cache.holdersWeek("2317", day))
    }

    @Test
    fun weightsReplaceTheWholeTable() {
        cache.putWeights(listOf(WeightRow(1, "2330", "台積電", 40.0), WeightRow(2, "2317", "鴻海", 5.0), WeightRow(3, "2454", "聯發科", 4.0)), "2026-09-30", "2026-10-01 09:00")
        cache.putWeights(listOf(WeightRow(1, "2330", "台積電", 41.0), WeightRow(2, "2454", "聯發科", 4.5)), "2026-10-31", "2026-11-10 09:30")
        assertEquals(listOf("2330" to 41.0, "2454" to 4.5), cache.weights().map { it.code to it.weight })
        assertEquals("2026-10-31", cache.meta(CacheStore.META_WEIGHTS_DATE))
        assertEquals("2026-11-10 09:30", cache.meta(CacheStore.META_WEIGHTS_FETCHED))
    }

    @Test
    fun govBankDaysNewestFirstReplacedAndPruned() {
        fun d(date: String, buy: Long = 1_000) = GovBankDay(date, buy, 400, buy * 10.0, 4_000.0)
        cache.putGovBankDays("2330", listOf(d("2026-11-05"), d("2026-11-06"), d("2026-08-01")), keepSince = LocalDate.of(2026, 7, 1))
        assertEquals(listOf("2026-11-06", "2026-11-05", "2026-08-01"), cache.govBankDays("2330", 10).map { it.date })
        assertEquals(listOf("2026-11-06"), cache.govBankDays("2330", 1).map { it.date })
        // 同一天覆蓋；keepSince 以前的刪掉（只刪這一檔的）
        cache.putGovBankDays("6515", listOf(d("2026-08-01")), keepSince = LocalDate.of(2026, 7, 1))
        cache.putGovBankDays("2330", listOf(d("2026-11-06", buy = 2_000)), keepSince = LocalDate.of(2026, 9, 1))
        assertEquals(listOf("2026-11-06" to 2_000L, "2026-11-05" to 1_000L), cache.govBankDays("2330", 10).map { it.date to it.buy })
        assertEquals(listOf("2026-08-01"), cache.govBankDays("6515", 10).map { it.date })
        assertEquals(emptyList<GovBankDay>(), cache.govBankDays("2317", 10))
        assertEquals(16_000.0, cache.govBankDays("2330", 1).single().netAmount, 1e-9)
    }

    @Test
    fun reopeningKeepsEverything() {
        // App 重開：同一個資料庫再建一次 CacheStore（CREATE TABLE IF NOT EXISTS），資料不能不見
        cache.putMarket("2330", Market.TWSE)
        cache.putMeta("k", "v")
        val again = CacheStore(sql)
        assertEquals(Market.TWSE, again.market("2330"))
        assertEquals("v", again.meta("k"))
        assertNull(again.meta("missing"))
    }
}
