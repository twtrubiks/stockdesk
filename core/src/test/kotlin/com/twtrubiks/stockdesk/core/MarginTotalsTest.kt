package com.twtrubiks.stockdesk.core

import com.twtrubiks.stockdesk.core.Samples.TotalRow
import com.twtrubiks.stockdesk.core.http.HttpResponse
import com.twtrubiks.stockdesk.core.http.Pacer
import com.twtrubiks.stockdesk.core.http.Taipei
import com.twtrubiks.stockdesk.core.margin.MarginException
import com.twtrubiks.stockdesk.core.margin.MarginSource
import com.twtrubiks.stockdesk.core.margin.MarginTotal
import com.twtrubiks.stockdesk.core.model.Market
import com.twtrubiks.stockdesk.core.store.CacheStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 整個市場的信用交易統計（大盤「資券」頁）：解析吃 11/18 的真實回應（上市資減 6.94 億、券減 14,415 張；
 * 上櫃資減 32.18 億、券減 4,333 張），抓取流程用 [Samples] 造的回應、真的 SQLite、虛擬時間。
 */
class MarginTotalsTest {
    private val nov18 = LocalDate.of(2026, 11, 18)

    // --- 解析 -------------------------------------------------------------------------

    @Test
    fun twseRealTotal() {
        val t = MarginSource.parseTwseTotal(Fixtures.require("twse-margin-total.json"), nov18)!!
        assertEquals(MarginTotal("2026-11-18", 8_986_680, 9_092_268, 207_959, 193_544, 508_721_448, 508_026_570), t)
        assertEquals(-694_878L, t.amountChg)
        assertEquals("6.94", MarginTotal.yi(t.amountChg))
        assertEquals(-14_415L, t.shortChg)
        assertEquals(105_588L, t.finChg) // 張數是增加的，金額卻減少：畫面上的「資減」看的是金額
    }

    @Test
    fun twseRealTotalIsAlsoInTheFullDay() {
        // 抓個股用的 selectType=ALL，同一份回應的第一張表就是統計
        val all = MarginSource.parseTwseTotal(Fixtures.require("twse-margin-day.json"), nov18)
        assertEquals(MarginSource.parseTwseTotal(Fixtures.require("twse-margin-total.json"), nov18), all)
    }

    @Test
    fun tpexRealTotal() {
        val t = MarginSource.parseTpexTotal(Fixtures.require("tpex-margin-day.json"), nov18)!!
        assertEquals(MarginTotal("2026-11-18", 2_086_346, 2_078_508, 34_077, 29_744, 160_441_825, 157_222_849), t)
        assertEquals(-3_218_976L, t.amountChg)
        assertEquals("32.18", MarginTotal.yi(t.amountChg))
        assertEquals(-4_333L, t.shortChg)
    }

    @Test
    fun yiTruncatesAndGroups() {
        assertEquals("6.94", MarginTotal.yi(694_878)) // 6.94878，不進位
        assertEquals("6.94", MarginTotal.yi(-694_878))
        assertEquals("32.18", MarginTotal.yi(3_218_976)) // 32.18976
        assertEquals("5,080.26", MarginTotal.yi(508_026_570))
        assertEquals("0.00", MarginTotal.yi(999))
        assertEquals("0.01", MarginTotal.yi(1_000))
        assertEquals("1.05", MarginTotal.yi(105_000))
        assertEquals("0.00", MarginTotal.yi(0))
    }

    @Test
    fun closedDaysAreNull() {
        assertNull(MarginSource.parseTwseTotal("""{"stat":"很抱歉，沒有符合條件的資料"}""", nov18))
        assertNull(MarginSource.parseTpexTotal(Samples.tpexClosed(nov18), nov18))
    }

    @Test
    fun brokenTotalsAreMarginExceptions() {
        assertThrows(MarginException::class.java) { MarginSource.parseTwseTotal("<html>請稍後再試</html>", nov18) }
        // 只有個股那張、沒有統計
        assertThrows(MarginException::class.java) { MarginSource.parseTwseTotal("""{"stat":"OK","tables":[{"fields":["代號"],"data":[]}]}""", nov18) }
        // 少了「融資金額」那一列
        val noAmount = Samples.twseMarginTotal(nov18).replace("融資金額(仟元)", "其他")
        assertTrue(assertThrows(MarginException::class.java) { MarginSource.parseTwseTotal(noAmount, nov18) }.message!!.contains("融資金額"))
        // 櫃買有個股、沒有合計
        val noSummary = Samples.tpexMarginDay(nov18, listOf(Samples.MarginRow("6515"))).replace("合計(張)", "x").replace("融資金(仟元)", "y")
        assertThrows(MarginException::class.java) { MarginSource.parseTpexTotal(noSummary, nov18) }
        // 餘額讀出來是 0：欄位對錯了
        assertThrows(MarginException::class.java) { MarginSource.parseTwseTotal(Samples.twseMarginTotal(nov18, TotalRow(amountBal = 0)), nov18) }
    }

    // --- 該公布的那天 -------------------------------------------------------------------

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(Taipei.ZONE).toInstant().toEpochMilli()

    @Test
    fun totalsDateSwitchesAtNinePm() {
        assertEquals(LocalDate.of(2026, 11, 9), MarginSource.totalsDate(at("2026-11-10T20:59:00"))) // 星期二 21:00 前：星期一
        assertEquals(LocalDate.of(2026, 11, 10), MarginSource.totalsDate(at("2026-11-10T21:00:00")))
        assertEquals(LocalDate.of(2026, 11, 6), MarginSource.totalsDate(at("2026-11-09T08:00:00"))) // 星期一早上：上星期五
        assertEquals(LocalDate.of(2026, 11, 6), MarginSource.totalsDate(at("2026-11-07T23:00:00"))) // 星期六
        assertEquals(LocalDate.of(2026, 11, 6), MarginSource.totalsDate(at("2026-11-08T23:00:00"))) // 星期日
    }

    // --- 抓取流程 ----------------------------------------------------------------------

    private val today = LocalDate.of(2026, 11, 10) // 星期二
    private val mon = today.minusDays(1)
    private val fri = today.minusDays(4)
    private val thu = today.minusDays(5)
    private val cache = CacheStore(JdbcSql())
    private var clock = at("2026-11-10T21:30:00")

    /** 每天的統計；沒列的日子是休市（或今天還沒公布）。融券餘額每天不同，看得出是哪一天。 */
    private val twseDays = mutableMapOf<LocalDate, TotalRow>()
    private val tpexDays = mutableMapOf<LocalDate, TotalRow>()

    private fun week(vararg days: LocalDate) = days.forEach { d ->
        twseDays[d] = TotalRow(shortBal = 190_000L + d.dayOfMonth)
        tpexDays[d] = TotalRow(shortBal = 29_000L + d.dayOfMonth)
    }

    private fun TestScope.http() = FakeHttp { testScheduler.currentTime }.apply {
        on({ "MI_MARGN" in it.url }) { r ->
            val d = LocalDate.parse(r.param("date")!!, DateTimeFormatter.BASIC_ISO_DATE)
            val total = twseDays[d]
            val body = when {
                total == null -> Samples.twseClosed()
                r.param("selectType") == "MS" -> Samples.twseMarginTotal(d, total)
                else -> Samples.twseMarginDay(d, listOf(Samples.MarginRow("2330", finPrev = 900, finBal = 1_000, finLimit = 4_000)), total)
            }
            HttpResponse(200, body, r.url)
        }
        on({ "margin/balance" in it.url }) { r ->
            val d = LocalDate.parse(r.param("date")!!, DateTimeFormatter.ofPattern("yyyy/MM/dd"))
            val body = tpexDays[d]?.let { Samples.tpexMarginDay(d, listOf(Samples.MarginRow("6515", finPrev = 90, finBal = 100, finLimit = 400)), it) } ?: Samples.tpexClosed(d)
            HttpResponse(200, body, r.url)
        }
    }

    private fun TestScope.source(http: FakeHttp) = MarginSource(
        http,
        cache,
        twsePacer = Pacer(Pacer.TWSE_MS, now = { testScheduler.currentTime }),
        tpexPacer = Pacer(Pacer.TPEX_MS, now = { testScheduler.currentTime }),
        twseBase = "https://twse.test",
        tpexBase = "https://tpex.test",
        today = { today },
        now = { clock },
    )

    private fun FakeHttp.twseDates() = requestsTo("MI_MARGN").map { LocalDate.parse(it.param("date")!!, DateTimeFormatter.BASIC_ISO_DATE) }
    private fun FakeHttp.tpexDates() = requestsTo("margin/balance").map { LocalDate.parse(it.param("date")!!, DateTimeFormatter.ofPattern("yyyy/MM/dd")) }

    @Test
    fun threeTradingDaysNewestFirstThenFromCache() = runTest {
        week(today, mon, fri, thu)
        val http = http()
        val source = source(http)

        val twse = source.totals(Market.TWSE)
        assertEquals(listOf(today, mon, fri).map { it.toString() }, twse.days.map { it.date })
        assertEquals(190_010L, twse.days.first().shortBal)
        assertNull(twse.note)
        assertFalse(twse.cached)
        // 週六日不問；證交所只要統計那張表
        assertEquals(listOf(today, mon, fri), http.twseDates())
        assertEquals("https://twse.test/rwd/zh/marginTrading/MI_MARGN?date=20261110&selectType=MS&response=json", http.requests[0].url)
        http.requestsTo("MI_MARGN").map { it.at }.zipWithNext { a, b -> assertTrue("證交所兩發只隔 ${b - a} ms", b - a >= Pacer.TWSE_MS) }

        val tpex = source.totals(Market.TPEX)
        assertEquals(listOf(today, mon, fri).map { it.toString() }, tpex.days.map { it.date })
        assertEquals(29_010L, tpex.days.first().shortBal)
        assertEquals(listOf(today, mon, fri), http.tpexDates())

        // 第二次：都在快取裡，不連網
        val before = http.requests.size
        val again = source.totals(Market.TWSE)
        assertEquals(twse.days, again.days)
        assertTrue(again.cached)
        assertEquals(before, http.requests.size)
        // 不用建 source 也讀得到
        assertEquals(listOf(twse.days, tpex.days), MarginSource.cachedTotals(cache).map { it.days })
    }

    @Test
    fun beforeNinePmTodayIsNotAskedUnlessForced() = runTest {
        week(today, mon, fri, thu)
        clock = at("2026-11-10T20:00:00")
        val http = http()
        val source = source(http)

        assertEquals(listOf(mon, fri, thu).map { it.toString() }, source.totals(Market.TWSE).days.map { it.date })
        assertEquals(listOf(mon, fri, thu), http.twseDates())

        // 重新整理：今天的已經出來了就拿得到，其他兩天讀快取
        val forced = source.totals(Market.TWSE, force = true)
        assertEquals(listOf(today, mon, fri).map { it.toString() }, forced.days.map { it.date })
        assertEquals(listOf(mon, fri, thu, today), http.twseDates())
    }

    @Test
    fun todayNotPublishedYetIsRememberedForTenMinutes() = runTest {
        week(mon, fri, thu)
        val http = http()
        val source = source(http)

        assertEquals(listOf(mon, fri, thu).map { it.toString() }, source.totals(Market.TWSE).days.map { it.date })
        assertEquals(listOf(today, mon, fri, thu), http.twseDates())
        // 今天沒資料不記成休市
        assertNull(cache.marginDayState(Market.TWSE, today))

        // 10 分鐘內再進頁面：不問
        clock += 5 * 60 * 1000L
        assertTrue(source.totals(Market.TWSE).cached)
        assertEquals(4, http.requestsTo("MI_MARGN").size)
        // 按重新整理：再問今天一次
        source.totals(Market.TWSE, force = true)
        assertEquals(today, http.twseDates().last())
        assertEquals(5, http.requestsTo("MI_MARGN").size)
        // 過了 10 分鐘、公布了：拿到今天
        twseDays[today] = TotalRow(shortBal = 190_010)
        clock += 11 * 60 * 1000L
        assertEquals(today.toString(), source.totals(Market.TWSE).days.first().date)
    }

    @Test
    fun pastHolidayIsRememberedAsClosed() = runTest {
        week(today, fri, thu) // 星期一放假
        val http = http()
        val source = source(http)
        assertEquals(listOf(today, fri, thu).map { it.toString() }, source.totals(Market.TPEX).days.map { it.date })
        assertEquals(listOf(today, mon, fri, thu), http.tpexDates())
        assertEquals(true, cache.marginDayState(Market.TPEX, mon))
    }

    @Test
    fun noResponseShowsWhatIsCached() = runTest {
        week(today, mon, fri)
        val http = http()
        val source = source(http)
        source.totals(Market.TWSE, days = 2) // 快取裡有今天、星期一
        http.down("MI_MARGN")

        val r = source.totals(Market.TWSE)
        assertEquals(listOf(today, mon).map { it.toString() }, r.days.map { it.date })
        assertTrue(r.note!!, r.note!!.startsWith("證交所沒回應"))
        assertFalse(r.cached)

        // 一邊沒回應不影響另一邊
        assertNull(source.totals(Market.TPEX).note)
    }

    @Test
    fun brokenFormatIsANoteNotAnException() = runTest {
        val http = http()
        http.on("MI_MARGN", body = "<html>請稍後再試</html>")
        val r = source(http).totals(Market.TWSE)
        assertEquals(emptyList<MarginTotal>(), r.days)
        assertTrue(r.note!!, "格式" in r.note!!)
        // 第一發就壞了：不再往回一天一天打
        assertEquals(1, http.requests.size)
    }

    @Test
    fun longClosureStopsAfterTheLookback() = runTest {
        val http = http() // 每天都休市
        val r = source(http).totals(Market.TWSE)
        assertEquals(emptyList<MarginTotal>(), r.days)
        assertNull(r.note)
        // 14 個日曆日（含今天 15 天）裡的平日，週六日不問
        assertEquals(11, http.requests.size)
    }

    @Test
    fun fetchingAStockKeepsTheTotalsFromTheSameResponse() = runTest {
        week(today, mon, fri)
        val http = http()
        val source = source(http)
        source.history("2330", days = 3, watch = setOf("2330")).toList()
        val asked = http.requests.size

        // 個股那三天的回應裡就有統計：資券頁不用再問證交所
        val twse = source.totals(Market.TWSE)
        assertEquals(listOf(today, mon, fri).map { it.toString() }, twse.days.map { it.date })
        assertTrue(twse.cached)
        assertEquals(asked, http.requests.size)
    }

    // --- 快取 -------------------------------------------------------------------------

    @Test
    fun totalsRoundTripNewestFirstReplacedAndPruned() {
        fun t(date: String, shortBal: Long = 100) = MarginTotal(date, 10, 20, 90, shortBal, 1_000, 2_000)
        val keep = LocalDate.of(2026, 9, 1)
        listOf(t("2026-11-06"), t("2026-11-05"), t("2026-08-01")).forEach { cache.putMarginTotal(Market.TWSE, it, keep) }
        cache.putMarginTotal(Market.TPEX, t("2026-11-06", shortBal = 7), keep)
        cache.putMarginTotal(Market.TWSE, t("2026-11-06", shortBal = 55), keep)

        assertEquals(listOf("2026-11-06", "2026-11-05"), cache.marginTotals(Market.TWSE, 10).map { it.date })
        assertEquals(55L, cache.marginTotal(Market.TWSE, LocalDate.of(2026, 11, 6))!!.shortBal)
        assertEquals(7L, cache.marginTotals(Market.TPEX, 1).single().shortBal)
        assertNull(cache.marginTotal(Market.TPEX, LocalDate.of(2026, 11, 5)))
    }
}
