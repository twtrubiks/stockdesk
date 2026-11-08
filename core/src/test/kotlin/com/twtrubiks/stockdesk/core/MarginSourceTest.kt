package com.twtrubiks.stockdesk.core

import com.twtrubiks.stockdesk.core.Samples.MarginRow
import com.twtrubiks.stockdesk.core.http.HttpResponse
import com.twtrubiks.stockdesk.core.http.Pacer
import com.twtrubiks.stockdesk.core.margin.MarginEvent
import com.twtrubiks.stockdesk.core.margin.MarginSource
import com.twtrubiks.stockdesk.core.model.Market
import com.twtrubiks.stockdesk.core.store.CacheStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 融資融券的抓取流程（不連網）：證交所、櫃買用 [Samples] 造的回應，快取是真的 SQLite（[JdbcSql]），節流走 runTest 的虛擬時間。
 * 欄位順序本身靠 [MarginParseTest] 的 fixture 測試。
 */
class MarginSourceTest {
    private val today = LocalDate.of(2026, 11, 10) // 星期二
    private val mon = today.minusDays(1)
    private val sun = today.minusDays(2)
    private val sat = today.minusDays(3)
    private val fri = today.minusDays(4)
    private val thu = today.minusDays(5)

    private val cache = CacheStore(JdbcSql())

    /** MarginSource 的 now（「今天還沒公布」記 10 分鐘用的），跟節流的虛擬時間分開，測試自己撥。 */
    private var clock = 0L

    /** 證交所／櫃買每天回什麼；沒列的日子是休市（或今天還沒公布）。 */
    private val twseDays = mutableMapOf<LocalDate, List<MarginRow>>()
    private val tpexDays = mutableMapOf<LocalDate, List<MarginRow>>()

    private fun row(code: String, bal: Long) = MarginRow(
        code, finPrev = bal - 30, finBal = bal, finLimit = bal * 4, shortPrev = bal / 10 + 5, shortBal = bal / 10, shortLimit = bal * 4,
        finBuy = 120, finSell = 80, finRedeem = 10,
    )

    /** 一般的一週：上市的 2330、2317，上櫃的 6515；週六日休市。餘額每天不同，看得出新到舊的順序。 */
    private fun normalWeek() {
        listOf(today to 1_000L, mon to 990L, fri to 980L, thu to 970L).forEach { (d, bal) ->
            twseDays[d] = listOf(row("2330", bal), row("2317", bal * 2))
            tpexDays[d] = listOf(row("6515", bal / 10))
        }
    }

    private fun TestScope.http() = FakeHttp { testScheduler.currentTime }.apply {
        on({ "MI_MARGN" in it.url }) { r ->
            val d = LocalDate.parse(r.param("date")!!, DateTimeFormatter.BASIC_ISO_DATE)
            HttpResponse(200, twseDays[d]?.let { Samples.twseMarginDay(d, it) } ?: Samples.twseClosed(), r.url)
        }
        on({ "margin/balance" in it.url }) { r ->
            val d = LocalDate.parse(r.param("date")!!, DateTimeFormatter.ofPattern("yyyy/MM/dd"))
            HttpResponse(200, tpexDays[d]?.let { Samples.tpexMarginDay(d, it) } ?: Samples.tpexClosed(d), r.url)
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

    private suspend fun MarginSource.fetch(code: String, days: Int = 3, watch: Set<String> = setOf(code)) = history(code, days, watch).toList()

    private fun List<MarginEvent>.done() = last() as? MarginEvent.Done ?: throw AssertionError("最後一個不是 Done：${last()}")

    private fun List<MarginEvent>.failed() = last() as? MarginEvent.Failed ?: throw AssertionError("最後一個不是 Failed：${last()}")

    private fun FakeHttp.twseDates() = requestsTo("MI_MARGN").map { LocalDate.parse(it.param("date")!!, DateTimeFormatter.BASIC_ISO_DATE) }

    private fun FakeHttp.tpexDates() = requestsTo("margin/balance").map { LocalDate.parse(it.param("date")!!, DateTimeFormatter.ofPattern("yyyy/MM/dd")) }

    // --- 市場判斷、順序、休市 ---------------------------------------------------------

    @Test
    fun twseStockNewestFirstAndMarketRemembered() = runTest {
        normalWeek()
        val http = http()
        val events = source(http).fetch("2330")

        val done = events.done()
        assertEquals(Market.TWSE, done.market)
        assertEquals(listOf(today, mon, fri).map { it.toString() }, done.rows.map { it.date })
        assertEquals(listOf(1_000L, 990L, 980L), done.rows.map { it.finBal })
        val progress = events.filterIsInstance<MarginEvent.Progress>()
        assertEquals(listOf(1, 2, 3), progress.map { it.done })
        assertTrue(progress.all { it.total == 3 && !it.cached })

        // 第一天就在證交所找到，之後只打證交所；週六日各打一次（休市）
        assertEquals(listOf(today, mon, sun, sat, fri), http.twseDates())
        assertEquals(emptyList<LocalDate>(), http.tpexDates())
        assertEquals(Market.TWSE, cache.market("2330"))
        // 過去的休市日永久記進快取
        assertEquals(true, cache.marginDayState(Market.TWSE, sat))
        assertEquals(false, cache.marginDayState(Market.TWSE, fri))
    }

    @Test
    fun tpexStockIsFoundAfterTwse() = runTest {
        normalWeek()
        val http = http()
        val done = source(http).fetch("6515").done()

        assertEquals(Market.TPEX, done.market)
        assertEquals(listOf(today, mon, fri).map { it.toString() }, done.rows.map { it.date })
        // 證交所只在第一天看一次（沒有這檔），之後只打櫃買
        assertEquals(listOf(today), http.twseDates())
        assertEquals(listOf(today, mon, sun, sat, fri), http.tpexDates())
        assertEquals(Market.TPEX, cache.market("6515"))
        // 櫃買的資使用率是端點直接給的
        assertEquals(25.0, done.rows.first().finUse!!, 0.001)
    }

    @Test
    fun knownMarketGoesStraightThere() = runTest {
        normalWeek()
        cache.putMarket("6515", Market.TPEX)
        val http = http()
        source(http).fetch("6515").done()
        assertEquals(emptyList<LocalDate>(), http.twseDates())
    }

    @Test
    fun notListedAnywhere() = runTest {
        normalWeek()
        val http = http()
        val failed = source(http).fetch("9999").failed()
        assertTrue(failed.message, "證交所與櫃買都沒有 9999" in failed.message)
        // 兩邊都有開市、都沒有：第一個交易日就下結論，不往回打
        assertEquals(listOf(today), http.twseDates())
        assertEquals(listOf(today), http.tpexDates())
    }

    @Test
    fun daysAreClampedToAtLeastOne() = runTest {
        normalWeek()
        assertEquals(1, source(http()).fetch("2330", days = 0).done().rows.size)
    }

    // --- 快取 ------------------------------------------------------------------------

    @Test
    fun secondFetchComesFromCacheWithoutRequestsOrWaiting() = runTest {
        normalWeek()
        val http = http()
        val source = source(http)
        source.fetch("2330")
        val requests = http.requests.size
        val startedAt = testScheduler.currentTime

        val events = source(http).fetch("2330") // 新的 source（App 重開），快取在資料庫裡
        assertEquals(listOf(1_000L, 990L, 980L), events.done().rows.map { it.finBal })
        assertTrue(events.filterIsInstance<MarginEvent.Progress>().all { it.cached })
        assertEquals(requests, http.requests.size)
        // 讀快取不經過節流
        assertEquals(startedAt, testScheduler.currentTime)
    }

    @Test
    fun watchlistStocksShareTheSameDay() = runTest {
        normalWeek()
        val http = http()
        val watch = setOf("2330", "2317", "6515")
        source(http).fetch("2330", watch = watch)
        val requests = http.requests.size

        // 2317 同市場同幾天：整份都在快取裡，市場也從快取的列判斷出來
        val done = source(http).fetch("2317", watch = watch).done()
        assertEquals(Market.TWSE, done.market)
        assertEquals(listOf(2_000L, 1_980L, 1_960L), done.rows.map { it.finBal })
        assertEquals(requests, http.requests.size)
        assertEquals(Market.TWSE, cache.market("2317"))
    }

    @Test
    fun stockAddedLaterRefetchesItsDays() = runTest {
        normalWeek()
        val http = http()
        source(http).fetch("2330", watch = setOf("2330"))
        val before = http.twseDates().size

        // 2317 是後來才加的：那幾天的快取沒有它的列，要重抓；休市日不用再打
        val done = source(http).fetch("2317", watch = setOf("2330", "2317")).done()
        assertEquals(3, done.rows.size)
        assertEquals(listOf(today, mon, fri), http.twseDates().drop(before))
        assertEquals(emptyList<LocalDate>(), http.tpexDates())
    }

    @Test
    fun todayNotPublishedIsRememberedOnlyInMemoryForTenMinutes() = runTest {
        normalWeek()
        twseDays.remove(today)
        tpexDays.remove(today)
        val http = http()
        val source = source(http)

        val first = source.fetch("2330", days = 2).done()
        assertEquals(listOf(mon, fri).map { it.toString() }, first.rows.map { it.date })
        // 今天沒資料不記成休市
        assertNull(cache.marginDayState(Market.TWSE, today))
        assertNull(cache.marginDayState(Market.TPEX, today))

        clock += 5 * 60_000
        source.fetch("2330", days = 2).done()
        assertEquals("10 分鐘內不再問今天", 1, http.twseDates().count { it == today })

        clock += 6 * 60_000
        twseDays[today] = listOf(row("2330", 1_000))
        val later = source.fetch("2330", days = 2).done()
        assertEquals(2, http.twseDates().count { it == today })
        assertEquals(listOf(today, mon).map { it.toString() }, later.rows.map { it.date })
    }

    @Test
    fun twsePublishedButTpexNotYetIsNotNotListed() = runTest {
        // 證交所今天已公布、櫃買還沒：上櫃股不能因此判成「都沒有」，往前一天再看
        normalWeek()
        tpexDays.remove(today)
        val done = source(http()).fetch("6515").done()
        assertEquals(Market.TPEX, done.market)
        assertEquals(listOf(mon, fri, thu).map { it.toString() }, done.rows.map { it.date })
    }

    // --- 沒回應、錯誤 ----------------------------------------------------------------

    @Test
    fun oneSideDownTheOtherHasIt() = runTest {
        normalWeek()
        val http = http().apply { down("MI_MARGN") }
        val done = source(http).fetch("6515").done()
        assertEquals(Market.TPEX, done.market)
        assertEquals(3, done.rows.size)
        assertEquals("找到櫃買之後不再試證交所", 1, http.twseDates().size)
    }

    @Test
    fun oneSideDownTheOtherOpenWithoutItIsNoResponseNotNotListed() = runTest {
        normalWeek()
        val http = http().apply { down("margin/balance") }
        val failed = source(http).fetch("6515").failed()
        assertTrue(failed.message, "沒回應" in failed.message)
        assertNull(cache.market("6515"))
    }

    @Test
    fun bothDownStopsAtOnce() = runTest {
        normalWeek()
        val http = http().apply {
            down("MI_MARGN")
            down("margin/balance")
        }
        val failed = source(http).fetch("2330").failed()
        assertTrue(failed.message, "沒回應，可能被限流" in failed.message)
        assertEquals(emptyList<Any>(), failed.partial)
        assertEquals(2, http.requests.size)
    }

    @Test
    fun droppingMidwayKeepsWhatWasFetched() = runTest {
        normalWeek()
        val http = http().apply { on({ "MI_MARGN" in it.url && it.param("date") == "20261106" }) { throw IOException("timeout") } }
        val events = source(http).fetch("2330")
        val failed = events.failed()
        assertTrue(failed.message, "沒回應" in failed.message)
        assertEquals(listOf(1_000L, 990L), failed.partial.map { it.finBal })
        assertEquals(2, events.filterIsInstance<MarginEvent.Progress>().size)
    }

    @Test
    fun suspendedStockDoesNotWalkBackForMonths() = runTest {
        // 市場已知，但有開市的那天沒有這一檔（停止信用交易、下市）：停下來，不要往回打 120 天
        normalWeek()
        cache.putMarket("2330", Market.TWSE)
        twseDays.replaceAll { _, rows -> rows.filter { it.code != "2330" } }
        val http = http()
        val failed = source(http).fetch("2330").failed()
        assertTrue(failed.message, "證交所最近一個交易日沒有 2330" in failed.message)
        assertEquals(listOf(today), http.twseDates())
    }

    @Test
    fun stockMissingPartwayReturnsWhatItHas() = runTest {
        normalWeek()
        twseDays[mon] = listOf(row("2317", 1_980))
        val http = http()
        val done = source(http).fetch("2330", days = 3).done()
        assertEquals(listOf(today.toString()), done.rows.map { it.date })
        assertEquals(listOf(today, mon), http.twseDates())
    }

    @Test
    fun httpErrorIsReported() = runTest {
        val http = http().apply { on("MI_MARGN", code = 503, body = "Service Unavailable") }
        cache.putMarket("2330", Market.TWSE)
        assertEquals("證交所：HTTP 503", source(http).fetch("2330").failed().message)
    }

    @Test
    fun notJsonIsReportedInsteadOfThrown() = runTest {
        // 被擋的時候可能回一頁 HTML：要變成「失敗」事件，不是沒接住的例外
        val http = http().apply { on("MI_MARGN", body = "<html><body>請稍後再試</body></html>") }
        cache.putMarket("2330", Market.TWSE)
        val failed = source(http).fetch("2330").failed()
        assertTrue(failed.message, failed.message.startsWith("證交所："))
    }

    // --- 節流、網址、衍生欄位 ---------------------------------------------------------

    @Test
    fun requestsArePacedPerHost() = runTest {
        normalWeek()
        val http = http()
        // 整個 App 一個 MarginSource，每個主機一個 Pacer
        val source = source(http)
        source.fetch("6515", days = 4)
        source.fetch("2330", days = 3)
        val all = http.requests
        assertEquals("第一發不用等", 0L, all.first().at)
        val twse = http.requestsTo("MI_MARGN").map { it.at }
        val tpex = http.requestsTo("margin/balance").map { it.at }
        assertTrue(twse.size >= 3 && tpex.size >= 4)
        tpex.zipWithNext { a, b -> assertTrue("櫃買兩發只隔 ${b - a} ms", b - a >= Pacer.TPEX_MS) }
        twse.zipWithNext { a, b -> assertTrue("證交所兩發只隔 ${b - a} ms", b - a >= Pacer.TWSE_MS) }
        // 一次只跑一個請求：時間不會倒退
        assertEquals(all.map { it.at }, all.map { it.at }.sorted())
    }

    @Test
    fun urlsAndHeaders() = runTest {
        normalWeek()
        val http = http()
        source(http).fetch("6515", days = 1)
        assertEquals("https://twse.test/rwd/zh/marginTrading/MI_MARGN?date=20261110&selectType=ALL&response=json", http.requests[0].url)
        assertEquals("https://tpex.test/www/zh-tw/margin/balance?date=2026/11/10&response=json", http.requests[1].url)
        assertTrue(http.requests.all { it.headers["User-Agent"]!!.startsWith("Mozilla/5.0") })
    }

    @Test
    fun derivedNumbers() = runTest {
        normalWeek()
        val day = source(http()).fetch("2330", days = 1).done().rows.single()
        assertEquals(30L, day.finChg) // 今日 − 前日
        assertEquals(-5L, day.shortChg)
        assertEquals(10.0, day.ratio!!, 0.001) // 券資比 = 券餘額 ÷ 資餘額
        assertEquals(25.0, day.finUse!!, 0.001) // 上市：融資餘額 ÷ 次一營業日限額
        assertEquals(120L, day.finBuy)
        assertEquals(80L, day.finSell)
        assertEquals(10L, day.finRedeem)
    }
}
