package com.twtrubiks.stockdesk.core

import com.twtrubiks.stockdesk.core.govbank.GovBank
import com.twtrubiks.stockdesk.core.govbank.GovBankEvent
import com.twtrubiks.stockdesk.core.govbank.GovBankSource
import com.twtrubiks.stockdesk.core.govbank.GovBankVia
import com.twtrubiks.stockdesk.core.http.Pacer
import com.twtrubiks.stockdesk.core.model.StockRef
import com.twtrubiks.stockdesk.core.store.CacheStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 八大官股的抓取流程（不連網，blave 與 HiStock 的回應用 [Samples] 造）：先全市場、再一檔一發，
 * 「該公布的那天」到了才抓、還沒公布 10 分鐘內不再問、假日跟著全市場、重新整理全部重抓、沒回應就停、每個主機的節流；
 * blave 沒有的檔（上櫃）改抓 HiStock 的個股頁、記住之後直接抓 HiStock。
 */
class GovBankSourceTest {
    private val cache = CacheStore(JdbcSql())
    private val stocks = listOf(StockRef("2330", "台積電"), StockRef("6515", "穎崴"))

    /** 2026-11-10（星期二）20:00：當天的數字已經公布了。 */
    private var now = at(2026, 11, 10, 20, 0)

    private val mon = LocalDate.of(2026, 11, 9)
    private val tue = LocalDate.of(2026, 11, 10)
    private val wed = LocalDate.of(2026, 11, 11)

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = LocalDateTime.of(y, m, d, h, min).toInstant(ZoneOffset.ofHours(8)).toEpochMilli()

    private fun market(vararg days: Pair<LocalDate, Double>) = Samples.histockBroker8(
        days.toList(),
        topBuy = listOf(Samples.RankSample("2330", "台積電", 213_162)),
        topSell = listOf(Samples.RankSample("2327", "國巨*", -69_172)),
    )

    private fun stock(code: String, vararg days: LocalDate, buyLots: Long = 3_000, sellLots: Long = 1_000) =
        Samples.blaveGovBank(code, days.associateWith { Samples.govBankFlows(buyLots, sellLots, price = 1_000.0) })

    private val http = FakeHttp().apply {
        on("broker8.aspx", body = market(mon to 50.0, tue to 102.5))
        on("gov_bank/2330", body = stock("2330", mon, tue))
        on("gov_bank/6515", body = stock("6515", mon, tue, buyLots = 100, sellLots = 400))
    }

    private fun source(http: FakeHttp = this.http) = GovBankSource(
        http, cache, blavePacer = Pacer(0), histockPacer = Pacer(0), blaveBase = "https://blave.test",
        histockUrl = "https://histock.test/stock/broker8.aspx", histockStockUrl = "https://histock.test/stock/broker.aspx", now = { now },
    )

    /** 上櫃的一檔：blave 回空的 data（實測如此）、HiStock 的個股頁有。 */
    private val otc = StockRef("3131", "弘塑")

    private fun otcPage(vararg days: Samples.StockDaySample) = Samples.histockStockGovBank("3131", "弘塑", days.toList())

    /** 請求的網址只留主機以後、`?` 以前的最後一段（HiStock 個股頁留 `?no=`）。 */
    private fun FakeHttp.paths(from: Int = 0) = requests.drop(from).map { r ->
        if ("broker.aspx" in r.url) "broker.aspx?" + r.url.substringAfter('?') else r.url.substringAfter("twstock/").substringAfter(".test/stock/").substringBefore('?')
    }

    private suspend fun GovBankSource.result(force: Boolean = false, stocks: List<StockRef> = this@GovBankSourceTest.stocks): GovBank =
        (load(stocks, force).toList().last() as GovBankEvent.Done).result

    @Test
    fun marketFirstThenOneRequestPerStock() = runTest {
        val events = source().load(stocks).toList()
        assertEquals(listOf(1, 2, 3), events.filterIsInstance<GovBankEvent.Progress>().map { it.done })
        assertTrue(events.filterIsInstance<GovBankEvent.Progress>().all { it.total == 3 && !it.cached })

        assertEquals(
            listOf(
                "https://histock.test/stock/broker8.aspx",
                "https://blave.test/studio/charts/twstock/gov_bank/2330?start=2026-10-21&end=2026-11-10",
                "https://blave.test/studio/charts/twstock/gov_bank/6515?start=2026-10-21&end=2026-11-10",
            ),
            http.requests.map { it.url },
        )
        assertTrue(http.requests.all { it.headers["User-Agent"]!!.startsWith("Mozilla/5.0") })

        val r = (events.last() as GovBankEvent.Done).result
        assertFalse(r.cached)
        assertNull(r.warning)
        assertNull(r.marketNote)
        assertEquals("2026-11-10 20:00", r.fetchedAt)
        assertEquals("2026-11-10", r.market!!.latest!!.date)
        assertEquals(102.5e8, r.market!!.latest!!.netAmount, 1.0)
        assertEquals("台積電", r.market!!.topBuy.single().name)

        assertEquals(listOf("2330", "6515"), r.stocks.map { it.stock.code })
        assertEquals(listOf("2026-11-10", "2026-11-09"), r.stocks[0].days.map { it.date })
        assertEquals(2_000L, r.stocks[0].latest!!.netLots)
        assertEquals(-300L, r.stocks[1].latest!!.netLots)

        val total = r.watchlistTotal()!!
        assertEquals("2026-11-10", total.date)
        assertEquals(2, total.count)
        assertEquals(1_700L, total.netLots)
        assertEquals(1_700_000L * 1_000.0, total.netAmount, 0.01)
    }

    @Test
    fun sameEveningReadsTheCache() = runTest {
        source().result()
        now += 3 * 3_600_000 // 23:00
        val r = source().result()
        assertTrue(r.cached)
        assertEquals("2026-11-10 20:00", r.fetchedAt)
        assertEquals(3, http.requests.size)
        assertEquals(2_000L, r.stocks[0].latest!!.netLots)
        assertEquals(102.5e8, r.market!!.latest!!.netAmount, 1.0)
    }

    @Test
    fun nextMorningIsStillFresh() = runTest {
        source().result()
        now = at(2026, 11, 11, 10, 0)
        assertTrue(source().result().cached)
        assertEquals(3, http.requests.size)
    }

    @Test
    fun afterSixThirtyTheNextDayIsFetched() = runTest {
        source().result()
        now = at(2026, 11, 11, 19, 0)
        http.on("broker8.aspx", body = market(tue to 102.5, wed to -30.0))
        http.on("gov_bank/2330", body = stock("2330", tue, wed, buyLots = 500, sellLots = 900))
        http.on("gov_bank/6515", body = stock("6515", tue, wed))
        val r = source().result()
        assertFalse(r.cached)
        assertEquals(6, http.requests.size)
        assertEquals("2026-11-11", r.market!!.latest!!.date)
        assertEquals(listOf("2026-11-11", "2026-11-10", "2026-11-09"), r.stocks[0].days.map { it.date })
        assertEquals(-400L, r.stocks[0].latest!!.netLots)
    }

    @Test
    fun notPublishedYetFollowsTheMarketAndRetriesLater() = runTest {
        source().result()
        // 星期三 18:40：HiStock 還是星期二的 → 個股也只要到星期二，不打 blave
        now = at(2026, 11, 11, 18, 40)
        val r = source().result()
        assertEquals(4, http.requests.size)
        assertEquals("https://histock.test/stock/broker8.aspx", http.requests.last().url)
        assertFalse("問過 HiStock", r.cached)
        assertEquals("2026-11-10", r.stocks[0].latest!!.date)

        // 10 分鐘內再進來不再問
        now += 5 * 60_000
        assertTrue(source().result().cached)
        assertEquals(4, http.requests.size)

        // 過了 10 分鐘再問一次；這次公布了，個股也跟著抓
        now += 6 * 60_000
        http.on("broker8.aspx", body = market(tue to 102.5, wed to -30.0))
        http.on("gov_bank/2330", body = stock("2330", tue, wed))
        http.on("gov_bank/6515", body = stock("6515", tue, wed))
        val later = source().result()
        assertEquals(7, http.requests.size)
        assertEquals("2026-11-11", later.stocks[1].latest!!.date)
    }

    @Test
    fun blaveLaterThanHistockAsksAgainAfterTenMinutes() = runTest {
        source().result()
        now = at(2026, 11, 11, 19, 0)
        http.on("broker8.aspx", body = market(tue to 102.5, wed to -30.0))
        // HiStock 有星期三了、blave 還沒
        source().result()
        assertEquals(6, http.requests.size)
        now += 5 * 60_000
        source().result()
        assertEquals("10 分鐘內不再問 blave", 6, http.requests.size)
        now += 6 * 60_000
        source().result()
        assertEquals(listOf("gov_bank/2330", "gov_bank/6515"), http.requests.takeLast(2).map { it.url.substringAfter("twstock/").substringBefore('?') })
    }

    @Test
    fun refreshFetchesEverything() = runTest {
        source().result()
        val r = source().result(force = true)
        assertFalse(r.cached)
        assertEquals(6, http.requests.size)
    }

    @Test
    fun stockWithoutDataGetsANoteAndTheOthersContinue() = runTest {
        // 9999：blave 空的；HiStock 查不到代號時回台積電的頁面（實測如此）→ 兩邊都沒有
        http.on("gov_bank/9999", body = Samples.blaveGovBank("9999", emptyMap()))
        http.on("broker.aspx?no=9999", body = Samples.histockStockGovBank("2330", "台積電", listOf(Samples.StockDaySample(tue, 954, 213_161))))
        http.on("gov_bank/6515", code = 400, body = """{"error":"invalid stock_id"}""")
        val watch = listOf(StockRef("9999", null), StockRef("6515", "穎崴"), StockRef("2317", "鴻海"))
        val r = source().result(stocks = watch)
        assertEquals(listOf("broker8.aspx", "gov_bank/9999", "broker.aspx?no=9999", "gov_bank/6515", "gov_bank/2317"), http.paths())
        assertTrue(r.stocks[0].note!!, "blave、HiStock 都沒有" in r.stocks[0].note!!)
        assertTrue(r.stocks[0].days.isEmpty())
        assertEquals("兩邊都沒有不記成 HiStock", GovBankVia.BLAVE, r.stocks[0].via)
        assertTrue(r.stocks[1].note!!, "無效" in r.stocks[1].note!!)
        // 2317 沒設定回應（FakeHttp 丟 IOException）：停下來
        assertTrue(r.warning!!, r.warning!!.startsWith("blave 沒回應"))
        assertNull(r.watchlistTotal())

        // 10 分鐘內再進來：沒資料、代號無效的不再問，沒回應的那檔再試
        now += 60_000
        source().result(stocks = watch)
        assertEquals(listOf("gov_bank/2317"), http.paths(from = 5))
    }

    @Test
    fun otcStockFallsBackToHistockAndRemembersIt() = runTest {
        http.on("gov_bank/3131", body = Samples.blaveGovBank("3131", emptyMap()))
        http.on("broker.aspx?no=3131", body = otcPage(Samples.StockDaySample(mon, -14, -2_156), Samples.StockDaySample(tue, 5, 801)))
        val watch = listOf(stocks[0], otc)
        val r = source().result(stocks = watch)
        assertEquals(listOf("broker8.aspx", "gov_bank/2330", "gov_bank/3131", "broker.aspx?no=3131"), http.paths())
        assertEquals(listOf(GovBankVia.BLAVE, GovBankVia.HISTOCK), r.stocks.map { it.via })
        val s = r.stocks[1]
        assertNull(s.note)
        assertEquals(listOf("2026-11-10", "2026-11-09"), s.days.map { it.date })
        assertEquals(5L, s.latest!!.netLots)
        assertEquals(801e4, s.latest!!.netAmount, 0.01)
        assertEquals(-14L, s.days[1].netLots)
        assertEquals(-2_156e4, s.days[1].netAmount, 0.01)
        // 合計：兩檔都有星期二
        val total = r.watchlistTotal()!!
        assertEquals(2, total.count)
        assertEquals(2_005L, total.netLots)
        assertEquals(GovBankVia.HISTOCK, GovBankSource.cached(cache, watch).stocks[1].via)
        assertEquals("自選股分頁那一行也有", 5L, GovBankSource.latest(cache, listOf("3131")).getValue("3131").netLots)

        // 隔天晚上：記住了，3131 直接抓 HiStock、不再白打 blave
        now = at(2026, 11, 11, 19, 0)
        http.on("broker8.aspx", body = market(tue to 102.5, wed to -30.0))
        http.on("gov_bank/2330", body = stock("2330", tue, wed))
        http.on("broker.aspx?no=3131", body = otcPage(Samples.StockDaySample(tue, 5, 801), Samples.StockDaySample(wed, -3, -629)))
        val next = source().result(stocks = watch)
        assertEquals(listOf("broker8.aspx", "gov_bank/2330", "broker.aspx?no=3131"), http.paths(from = 4))
        assertEquals("2026-11-11", next.stocks[1].latest!!.date)
        assertEquals(-629e4, next.stocks[1].latest!!.netAmount, 0.01)
    }

    @Test
    fun brokenHistockStockPageOnlyNotesThatStock() = runTest {
        http.on("gov_bank/3131", body = Samples.blaveGovBank("3131", emptyMap()))
        http.on("broker.aspx?no=3131", body = "<html><head><title>弘塑 (3131) 八大官股買賣超 - HiStock</title></head><body>改版了</body></html>")
        val r = source().result(stocks = listOf(otc, stocks[0]))
        assertTrue(r.stocks[0].note!!, r.stocks[0].note!!.startsWith("HiStock："))
        assertNull("不停下來", r.warning)
        assertEquals("後面的檔照抓", 2_000L, r.stocks[1].latest!!.netLots)
        assertEquals(GovBankVia.BLAVE, r.stocks[0].via)
    }

    @Test
    fun histockStockPageNotRespondingStops() = runTest {
        http.on("gov_bank/3131", body = Samples.blaveGovBank("3131", emptyMap()))
        http.down("broker.aspx?no=3131")
        val r = source().result(stocks = listOf(otc, stocks[0]))
        assertTrue(r.warning!!, r.warning!!.startsWith("HiStock 沒回應"))
        assertEquals("停下來，2330 不抓", listOf("broker8.aspx", "gov_bank/3131", "broker.aspx?no=3131"), http.paths())

        http.on("broker.aspx?no=3131", code = 429, body = "Too Many Requests")
        val again = source().result(stocks = listOf(otc, stocks[0]), force = true)
        assertTrue(again.warning!!, "429" in again.warning!!)
        assertEquals(listOf("broker8.aspx", "gov_bank/3131", "broker.aspx?no=3131"), http.paths(from = 3))
    }

    @Test
    fun noResponseStopsAndKeepsTheOldData() = runTest {
        source().result()
        http.down("gov_bank/")
        val r = source().result(force = true)
        // HiStock 一發、blave 只打第一檔就停
        val again = http.requests.drop(3).map { it.url }
        assertEquals(2, again.size)
        assertTrue(again[0], again[0].endsWith("broker8.aspx"))
        assertTrue(again[1], "gov_bank/2330?" in again[1])
        assertTrue(r.warning!!, r.warning!!.startsWith("blave 沒回應"))
        assertEquals("兩檔都還有上次的", listOf(2_000L, -300L), r.stocks.map { it.latest!!.netLots })
        assertFalse("HiStock 有重抓到", r.cached)
    }

    @Test
    fun quotaExhaustedStops() = runTest {
        http.on("gov_bank/2330", code = 503, body = "upstream quota exhausted")
        val r = source().result()
        assertTrue(r.warning!!, "503" in r.warning!!)
        assertEquals(2, http.requests.size)
        assertTrue(r.stocks.all { it.days.isEmpty() })
    }

    @Test
    fun histockDownStillLoadsTheStocks() = runTest {
        http.down("broker8.aspx")
        val r = source().result()
        assertNull(r.market)
        assertTrue(r.marketNote!!, r.marketNote!!.startsWith("HiStock 沒回應"))
        assertEquals(2_000L, r.stocks[0].latest!!.netLots)
        assertFalse(r.cached)
    }

    @Test
    fun histockDownWithOldDataShowsTheOldOne() = runTest {
        source().result()
        http.on("broker8.aspx", code = 500, body = "error")
        val r = source().result(force = true)
        assertEquals(102.5e8, r.market!!.latest!!.netAmount, 1.0)
        assertTrue(r.marketNote!!, r.marketNote!!.startsWith("重抓失敗") && "HTTP 500" in r.marketNote!!)
    }

    @Test
    fun totalOnlyCountsStocksWithTheLatestDay() = runTest {
        http.on("gov_bank/6515", body = stock("6515", mon))
        val total = source().result().watchlistTotal()!!
        assertEquals("2026-11-10", total.date)
        assertEquals(1, total.count)
        assertEquals(2_000L, total.netLots)
    }

    @Test
    fun cachedSnapshotNeedsNoNetwork() = runTest {
        assertTrue(GovBankSource.cached(cache, stocks).stocks.all { it.days.isEmpty() })
        assertNull(GovBankSource.cached(cache, stocks).fetchedAt)
        source().result()
        val snap = GovBankSource.cached(cache, stocks)
        assertTrue(snap.cached)
        assertEquals("2026-11-10 20:00", snap.fetchedAt)
        assertNotNull(snap.market)
        assertEquals(listOf(2_000L, -300L), snap.stocks.map { it.latest!!.netLots })
        assertEquals(setOf("2330", "6515"), GovBankSource.latest(cache, listOf("2330", "6515", "2317")).keys)
    }

    @Test
    fun emptyWatchlistStillGetsTheMarket() = runTest {
        val r = source().result(stocks = emptyList())
        assertEquals(1, http.requests.size)
        assertNotNull(r.market)
        assertNull(r.watchlistTotal())
    }

    @Test
    fun eachHostIsPaced() = runTest {
        val clocked = FakeHttp { testScheduler.currentTime }.apply {
            on("broker8.aspx", body = market(tue to 1.0))
            on("gov_bank/", body = stock("2330", tue))
        }
        val s = GovBankSource(
            clocked, cache,
            blavePacer = Pacer(Pacer.BLAVE_MS, now = { testScheduler.currentTime }),
            histockPacer = Pacer(Pacer.HISTOCK_MS, now = { testScheduler.currentTime }),
            blaveBase = "https://blave.test", histockUrl = "https://histock.test/stock/broker8.aspx",
            histockStockUrl = "https://histock.test/stock/broker.aspx", now = { now },
        )
        s.load(stocks + StockRef("2317", "鴻海")).toList()
        val (h, b1, b2, b3) = clocked.requests.map { it.at }
        assertEquals("不同主機不用等", h, b1)
        assertTrue("間隔 ${b2 - b1} ms", b2 - b1 >= Pacer.BLAVE_MS)
        assertTrue("間隔 ${b3 - b2} ms", b3 - b2 >= Pacer.BLAVE_MS)
    }

    @Test
    fun histockStockPageSharesTheHistockPacer() = runTest {
        val clocked = FakeHttp { testScheduler.currentTime }.apply {
            on("broker8.aspx", body = market(tue to 1.0))
            on("gov_bank/", body = Samples.blaveGovBank("3131", emptyMap()))
            on("broker.aspx?no=3131", body = otcPage(Samples.StockDaySample(tue, 5, 801)))
        }
        val s = GovBankSource(
            clocked, cache,
            blavePacer = Pacer(Pacer.BLAVE_MS, now = { testScheduler.currentTime }),
            histockPacer = Pacer(Pacer.HISTOCK_MS, now = { testScheduler.currentTime }),
            blaveBase = "https://blave.test", histockUrl = "https://histock.test/stock/broker8.aspx",
            histockStockUrl = "https://histock.test/stock/broker.aspx", now = { now },
        )
        s.load(listOf(otc)).toList()
        val (market, blave, page) = clocked.requests.map { it.at }
        assertEquals("不同主機不用等", market, blave)
        assertTrue("全市場與個股頁同一台主機，間隔 ${page - market} ms", page - market >= Pacer.HISTOCK_MS)
    }
}
