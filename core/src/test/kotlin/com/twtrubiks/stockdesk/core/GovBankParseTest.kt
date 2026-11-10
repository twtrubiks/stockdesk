package com.twtrubiks.stockdesk.core

import com.twtrubiks.stockdesk.core.govbank.GovBankException
import com.twtrubiks.stockdesk.core.govbank.GovBankSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 八大官股的解析：blave（個股）、HiStock（全市場、blave 沒有的個股）。名字有 `real` 的吃 2026-11-10 錄的真實回應
 * （`blave-gov-bank-2330.json` 是 2330 的 11/02～11/09、`histock-broker8.html` 是 11/09 的頁面，在使用者電腦上錄的；
 * `histock-broker-3131.html` 是上櫃的弘塑，在 VM 錄的），數字對照當時 blave 網頁的排行（台積電 11/09 買超 21.3 億、955 張）與 HiStock 網頁。
 */
class GovBankParseTest {
    @Test
    fun realBlaveAddsUpTheEightBanksPerDay() {
        val days = GovBankSource.parseBlave(Fixtures.require("blave-gov-bank-2330.json"))
        assertEquals(listOf("2026-11-09", "2026-11-06", "2026-11-05", "2026-11-04", "2026-11-03", "2026-11-02"), days.map { it.date })
        val d = days.first()
        assertEquals(2_863_879L, d.buy)
        assertEquals(1_908_988L, d.sell)
        assertEquals(954_891L, d.netShares)
        assertEquals(955L, d.netLots)
        assertEquals(2_131_618_285.0, d.netAmount, 1.0)
        assertEquals(3_288L, days[1].netLots)
    }

    @Test
    fun realHistockHasTheDailyTotalAndTheRanks() {
        val m = GovBankSource.parseHistock(Fixtures.require("histock-broker8.html"))
        assertEquals(GovBankSource.MARKET_DAYS, m.days.size)
        assertEquals("2026-11-09", m.latest!!.date)
        assertEquals(102.562e8, m.latest!!.netAmount, 1.0)
        assertEquals("2026-11-06", m.days[1].date)
        assertEquals(260.694e8, m.days[1].netAmount, 1.0)

        assertEquals("2026-11-09", m.topDate)
        assertEquals(GovBankSource.TOP_N, m.topBuy.size)
        assertEquals(GovBankSource.TOP_N, m.topSell.size)
        val tsmc = m.topBuy.first()
        assertEquals(1, tsmc.rank)
        assertEquals("2330", tsmc.code)
        assertEquals("台積電", tsmc.name)
        assertEquals(2_131_620_000.0, tsmc.netAmount, 1.0)
        // 名稱帶符號的照原樣
        val yageo = m.topSell.first()
        assertEquals("2327", yageo.code)
        assertEquals("國巨*", yageo.name)
        assertEquals(-691_720_000.0, yageo.netAmount, 1.0)
        assertEquals((1..GovBankSource.TOP_N).toList(), m.topSell.map { it.rank })
    }

    @Test
    fun sampleBlaveIsNewestFirst() {
        val body = Samples.blaveGovBank(
            "2330",
            mapOf(
                LocalDate.of(2026, 11, 6) to Samples.govBankFlows(buyLots = 1_000, sellLots = 4_000, price = 1_500.0),
                LocalDate.of(2026, 11, 9) to Samples.govBankFlows(buyLots = 3_001, sellLots = 1_000, price = 2_000.0),
            ),
        )
        val days = GovBankSource.parseBlave(body)
        assertEquals(listOf("2026-11-09", "2026-11-06"), days.map { it.date })
        assertEquals(3_001_000L, days[0].buy)
        assertEquals(2_001L, days[0].netLots)
        assertEquals(2_001_000L * 2_000.0, days[0].netAmount, 0.01)
        assertEquals(-3_000L, days[1].netLots)
        assertTrue(days[1].netAmount < 0)
    }

    @Test
    fun blaveWithoutDataIsEmpty() {
        assertEquals(emptyList<Any>(), GovBankSource.parseBlave(Samples.blaveGovBank("9999", emptyMap())))
    }

    @Test
    fun brokenBlaveStopsTheFetch() {
        for (body in listOf("<html>blocked</html>", """{"error":"x"}""", """{"data":[{"date":"2026-11-09","buy":1}]}""", """{"data":[{"date":"11/09","buy":1,"sell":1,"buy_amount":1,"sell_amount":1}]}""")) {
            val e = assertThrows(GovBankException::class.java) { GovBankSource.parseBlave(body) }
            assertTrue(body, e.stop)
            assertTrue(e.message!!, e.message!!.startsWith("blave："))
        }
    }

    @Test
    fun sampleHistockRoundTrip() {
        val html = Samples.histockBroker8(
            listOf(LocalDate.of(2026, 11, 6) to 260.694, LocalDate.of(2026, 11, 9) to -12.5),
            topBuy = listOf(Samples.RankSample("2330", "台積電", 213_162), Samples.RankSample("0050", "元大台灣50", 138_302)),
            topSell = listOf(Samples.RankSample("2327", "國巨*", -69_172)),
        )
        val m = GovBankSource.parseHistock(html)
        assertEquals(listOf("2026-11-09", "2026-11-06"), m.days.map { it.date })
        assertEquals(-12.5e8, m.latest!!.netAmount, 1.0)
        assertEquals(listOf("2330", "0050"), m.topBuy.map { it.code })
        assertEquals("元大台灣50", m.topBuy[1].name)
        assertEquals(-691_720_000.0, m.topSell.single().netAmount, 1.0)
        assertEquals("2026-11-09", m.topDate)
    }

    @Test
    fun histockWithoutRanksStillHasTheTotal() {
        val m = GovBankSource.parseHistock(Samples.histockBroker8(listOf(LocalDate.of(2026, 11, 9) to 1.0)))
        assertEquals(1, m.days.size)
        assertEquals(emptyList<Any>(), m.topBuy)
        assertNull(m.topDate)
    }

    @Test
    fun histockWithoutTheChartIsAChange() {
        for (html in listOf("<html><body>維護中</html>", Samples.histockBroker8(emptyList()))) {
            val e = assertThrows(GovBankException::class.java) { GovBankSource.parseHistock(html) }
            assertTrue(e.message!!, e.message!!.startsWith("HiStock："))
        }
    }

    @Test
    fun realHistockStockPageHasAboutThreeMonthsOfNetTotals() {
        val days = GovBankSource.parseHistockStock(Fixtures.require("histock-broker-3131.html"), "3131")
        assertEquals(58, days.size)
        assertEquals(listOf("2026-11-09", "2026-11-06", "2026-11-05"), days.take(3).map { it.date })
        assertEquals("2026-08-13", days.last().date)
        // 網頁上 11/09 合計 1 張、291 萬元；11/05 賣超 14 張、2,156 萬元
        assertEquals(1L, days[0].netLots)
        assertEquals(291e4, days[0].netAmount, 0.01)
        assertEquals(-14L, days[2].netLots)
        assertEquals(-2_156e4, days[2].netAmount, 0.01)
        assertEquals("只有淨額：賣超放在賣的那一邊", 0L, days[2].buy)
        assertEquals(14_000L, days[2].sell)
    }

    @Test
    fun realHistockStockPageOfAnotherCodeIsEmpty() {
        // HiStock 查不到代號時回 200 ＋ 別檔（台積電）的頁面：標題的代號對不上就是沒有
        assertEquals(emptyList<Any>(), GovBankSource.parseHistockStock(Fixtures.require("histock-broker-3131.html"), "7734"))
    }

    @Test
    fun sampleHistockStockRoundTrip() {
        val html = Samples.histockStockGovBank(
            "7734", "印能科技",
            listOf(
                Samples.StockDaySample(LocalDate.of(2026, 11, 6), -3, -629),
                Samples.StockDaySample(LocalDate.of(2026, 11, 9), 6, 1_085),
                Samples.StockDaySample(LocalDate.of(2026, 11, 5), -35, -6_032),
            ),
        )
        val days = GovBankSource.parseHistockStock(html, "7734")
        assertEquals(listOf("2026-11-09", "2026-11-06", "2026-11-05"), days.map { it.date })
        assertEquals(listOf(6L, -3L, -35L), days.map { it.netLots })
        assertEquals(1_085e4, days[0].netAmount, 0.01)
        assertEquals(-6_032e4, days[2].netAmount, 0.01)
        assertEquals(emptyList<Any>(), GovBankSource.parseHistockStock(html, "3131"))
    }

    @Test
    fun brokenHistockStockPageIsANoteNotAStop() {
        val good = Samples.histockStockGovBank("3131", "弘塑", listOf(Samples.StockDaySample(LocalDate.of(2026, 11, 9), 1, 291)))
        for (html in listOf(
            "<html><body>維護中</body></html>",
            "<html><head><title>弘塑 (3131) 八大官股買賣超 - HiStock</title></head></html>",
            good.replace("\"SumCount\"", "\"Sum_Count\""),
            good.replace("\"SumMoney\":\"[[", "\"SumMoney\":\"[[x"),
        )) {
            val e = assertThrows(GovBankException::class.java) { GovBankSource.parseHistockStock(html, "3131") }
            assertTrue(e.message!!, e.message!!.startsWith("HiStock："))
            assertFalse(e.message!!, e.stop)
        }
    }

    @Test
    fun publishedDateIsTheLastWeekdayAfterSixThirty() {
        fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = LocalDateTime.of(y, m, d, h, min).toInstant(ZoneOffset.ofHours(8)).toEpochMilli()
        // 星期二 18:29 → 星期一；18:30 → 星期二
        assertEquals(LocalDate.of(2026, 11, 9), GovBankSource.publishedDate(at(2026, 11, 10, 18, 29)))
        assertEquals(LocalDate.of(2026, 11, 10), GovBankSource.publishedDate(at(2026, 11, 10, 18, 30)))
        // 星期一早上 → 上週五；週末整天都是星期五
        assertEquals(LocalDate.of(2026, 11, 6), GovBankSource.publishedDate(at(2026, 11, 9, 9, 0)))
        assertEquals(LocalDate.of(2026, 11, 6), GovBankSource.publishedDate(at(2026, 11, 7, 20, 0)))
        assertEquals(LocalDate.of(2026, 11, 6), GovBankSource.publishedDate(at(2026, 11, 8, 23, 59)))
    }
}
