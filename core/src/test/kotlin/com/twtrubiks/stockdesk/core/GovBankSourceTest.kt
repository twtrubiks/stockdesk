package com.twtrubiks.stockdesk.core

import com.twtrubiks.stockdesk.core.govbank.GovBankSource
import com.twtrubiks.stockdesk.core.govbank.GovBankVia
import com.twtrubiks.stockdesk.core.http.Pacer
import com.twtrubiks.stockdesk.core.model.StockRef
import com.twtrubiks.stockdesk.core.store.CacheStore
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
 * 八大官股的抓取流程（不連網，blave 與 HiStock 的回應用 [Samples] 造）：全市場（官股頁）與一檔（goodinfo 卡片）各抓各的，
 * 「該公布的那天」到了才抓、還沒公布 10 分鐘內不再問、force 重抓、沒回應／429／503 是 stop、這一檔自己的問題是 note、每個主機的節流；
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

    @Test
    fun marketIsOneRequest() = runTest {
        val r = source().market()
        assertEquals(listOf("https://histock.test/stock/broker8.aspx"), http.requests.map { it.url })
        assertTrue(http.requests.all { it.headers["User-Agent"]!!.startsWith("Mozilla/5.0") })
        assertFalse(r.cached)
        assertNull(r.marketNote)
        assertEquals("2026-11-10 20:00", r.fetchedAt)
        assertEquals("2026-11-10", r.market!!.latest!!.date)
        assertEquals(102.5e8, r.market!!.latest!!.netAmount, 1.0)
        assertEquals("台積電", r.market!!.topBuy.single().name)
    }

    @Test
    fun stockIsOneRequestAndDoesNotTouchTheMarket() = runTest {
        val r = source().stock("2330")
        assertEquals(listOf("https://blave.test/studio/charts/twstock/gov_bank/2330?start=2026-10-21&end=2026-11-10"), http.requests.map { it.url })
        assertTrue(http.requests.all { it.headers["User-Agent"]!!.startsWith("Mozilla/5.0") })
        assertFalse(r.cached)
        assertNull(r.note)
        assertNull(r.stop)
        assertEquals(GovBankVia.BLAVE, r.via)
        assertEquals(listOf("2026-11-10", "2026-11-09"), r.days.map { it.date })
        assertEquals(2_000L, r.latest!!.netLots)
        assertEquals(-300L, source().stock("6515").latest!!.netLots)
        assertEquals("只要一天", listOf("2026-11-10"), source().stock("2330", days = 1).days.map { it.date })
        assertEquals(setOf("2330", "6515"), GovBankSource.latest(cache, listOf("2330", "6515", "2317")).keys)
    }

    @Test
    fun sameEveningReadsTheCache() = runTest {
        source().market()
        source().stock("2330")
        now += 3 * 3_600_000 // 23:00
        val m = source().market()
        val s = source().stock("2330")
        assertTrue(m.cached)
        assertTrue(s.cached)
        assertEquals("2026-11-10 20:00", m.fetchedAt)
        assertEquals(2, http.requests.size)
        assertEquals(2_000L, s.latest!!.netLots)
        assertEquals(102.5e8, m.market!!.latest!!.netAmount, 1.0)
    }

    @Test
    fun nextMorningIsStillFresh() = runTest {
        source().market()
        source().stock("2330")
        now = at(2026, 11, 11, 10, 0)
        assertTrue(source().market().cached)
        assertTrue(source().stock("2330").cached)
        assertEquals(2, http.requests.size)
    }

    @Test
    fun afterSixThirtyTheNextDayIsFetched() = runTest {
        source().market()
        source().stock("2330")
        now = at(2026, 11, 11, 19, 0)
        http.on("broker8.aspx", body = market(tue to 102.5, wed to -30.0))
        http.on("gov_bank/2330", body = stock("2330", tue, wed, buyLots = 500, sellLots = 900))
        val m = source().market()
        val s = source().stock("2330")
        assertFalse(m.cached)
        assertFalse(s.cached)
        assertEquals(4, http.requests.size)
        assertEquals("2026-11-11", m.market!!.latest!!.date)
        assertEquals(listOf("2026-11-11", "2026-11-10", "2026-11-09"), s.days.map { it.date })
        assertEquals(-400L, s.latest!!.netLots)
    }

    @Test
    fun notPublishedYetRetriesAfterTenMinutes() = runTest {
        source().market()
        source().stock("2330")
        // 星期三 18:40：兩邊都還是星期二的
        now = at(2026, 11, 11, 18, 40)
        assertFalse("問過 HiStock", source().market().cached)
        val s = source().stock("2330")
        assertFalse("問過 blave", s.cached)
        assertEquals("2026-11-10", s.latest!!.date)
        assertEquals(4, http.requests.size)

        // 10 分鐘內再來不再問
        now += 5 * 60_000
        assertTrue(source().market().cached)
        assertTrue(source().stock("2330").cached)
        assertEquals(4, http.requests.size)

        // 過了 10 分鐘再問一次；這次公布了
        now += 6 * 60_000
        http.on("broker8.aspx", body = market(tue to 102.5, wed to -30.0))
        http.on("gov_bank/2330", body = stock("2330", tue, wed))
        assertEquals("2026-11-11", source().market().market!!.latest!!.date)
        assertEquals("2026-11-11", source().stock("2330").latest!!.date)
        assertEquals(6, http.requests.size)
    }

    @Test
    fun forceFetchesAgain() = runTest {
        source().market()
        source().stock("2330")
        assertFalse(source().market(force = true).cached)
        assertFalse(source().stock("2330", force = true).cached)
        assertEquals(4, http.requests.size)
    }

    @Test
    fun stockWithoutDataGetsANote() = runTest {
        // 9999：blave 空的；HiStock 查不到代號時回台積電的頁面（實測如此）→ 兩邊都沒有
        http.on("gov_bank/9999", body = Samples.blaveGovBank("9999", emptyMap()))
        http.on("broker.aspx?no=9999", body = Samples.histockStockGovBank("2330", "台積電", listOf(Samples.StockDaySample(tue, 954, 213_161))))
        http.on("gov_bank/6515", code = 400, body = """{"error":"invalid stock_id"}""")
        val none = source().stock("9999")
        assertEquals(listOf("gov_bank/9999", "broker.aspx?no=9999"), http.paths())
        assertEquals(GovBankSource.NO_DATA, none.note)
        assertNull("不是來源的問題", none.stop)
        assertTrue(none.days.isEmpty())
        assertEquals("兩邊都沒有不記成 HiStock", GovBankVia.BLAVE, none.via)
        val invalid = source().stock("6515")
        assertTrue(invalid.note!!, "無效" in invalid.note!!)
        assertNull(invalid.stop)

        // 10 分鐘內再按：不再問，沒資料的還是寫原因
        now += 60_000
        val again = source().stock("9999")
        assertTrue(again.cached)
        assertEquals(GovBankSource.NO_DATA, again.note)
        assertTrue(source().stock("6515").cached)
        assertEquals(3, http.requests.size)
    }

    @Test
    fun otcStockFallsBackToHistockAndRemembersIt() = runTest {
        http.on("gov_bank/3131", body = Samples.blaveGovBank("3131", emptyMap()))
        http.on("broker.aspx?no=3131", body = otcPage(Samples.StockDaySample(mon, -14, -2_156), Samples.StockDaySample(tue, 5, 801)))
        val s = source().stock("3131")
        assertEquals(listOf("gov_bank/3131", "broker.aspx?no=3131"), http.paths())
        assertEquals(GovBankVia.HISTOCK, s.via)
        assertNull(s.note)
        assertEquals(listOf("2026-11-10", "2026-11-09"), s.days.map { it.date })
        assertEquals(5L, s.latest!!.netLots)
        assertEquals(801e4, s.latest!!.netAmount, 0.01)
        assertEquals(-14L, s.days[1].netLots)
        assertEquals(-2_156e4, s.days[1].netAmount, 0.01)
        assertEquals("自選股分頁那一行也有", 5L, GovBankSource.latest(cache, listOf("3131")).getValue("3131").netLots)

        // 隔天晚上：記住了，直接抓 HiStock、不再白打 blave
        now = at(2026, 11, 11, 19, 0)
        http.on("broker.aspx?no=3131", body = otcPage(Samples.StockDaySample(tue, 5, 801), Samples.StockDaySample(wed, -3, -629)))
        val next = source().stock("3131")
        assertEquals(listOf("broker.aspx?no=3131"), http.paths(from = 2))
        assertEquals("2026-11-11", next.latest!!.date)
        assertEquals(-629e4, next.latest!!.netAmount, 0.01)
        assertEquals(GovBankVia.HISTOCK, next.via)
    }

    @Test
    fun brokenHistockStockPageIsOnlyANote() = runTest {
        http.on("gov_bank/3131", body = Samples.blaveGovBank("3131", emptyMap()))
        http.on("broker.aspx?no=3131", body = "<html><head><title>弘塑 (3131) 八大官股買賣超 - HiStock</title></head><body>改版了</body></html>")
        val r = source().stock("3131")
        assertTrue(r.note!!, r.note!!.startsWith("HiStock："))
        assertNull("不是 stop", r.stop)
        assertEquals(GovBankVia.BLAVE, r.via)
    }

    @Test
    fun histockStockPageNotRespondingIsAStop() = runTest {
        http.on("gov_bank/3131", body = Samples.blaveGovBank("3131", emptyMap()))
        http.down("broker.aspx?no=3131")
        val r = source().stock("3131")
        assertTrue(r.stop!!, r.stop!!.startsWith("HiStock 沒回應"))
        assertNull(r.note)

        http.on("broker.aspx?no=3131", code = 429, body = "Too Many Requests")
        val again = source().stock("3131")
        assertFalse("沒回應的不記抓過，馬上再按會再問", again.cached)
        assertTrue(again.stop!!, "429" in again.stop!!)
    }

    @Test
    fun noResponseIsAStopAndKeepsTheOldData() = runTest {
        source().stock("2330")
        http.down("gov_bank/")
        val r = source().stock("2330", force = true)
        assertTrue(r.stop!!, r.stop!!.startsWith("blave 沒回應"))
        assertEquals("還有上次的", 2_000L, r.latest!!.netLots)
        assertFalse(r.cached)
    }

    @Test
    fun quotaExhaustedIsAStop() = runTest {
        http.on("gov_bank/2330", code = 503, body = "upstream quota exhausted")
        val r = source().stock("2330")
        assertTrue(r.stop!!, "503" in r.stop!!)
        assertEquals(1, http.requests.size)
        assertTrue(r.days.isEmpty())
    }

    @Test
    fun histockDownHasANote() = runTest {
        http.down("broker8.aspx")
        val r = source().market()
        assertNull(r.market)
        assertTrue(r.marketNote!!, r.marketNote!!.startsWith("HiStock 沒回應"))
        assertTrue(r.cached)
        assertEquals("全市場抓不到不影響個股", 2_000L, source().stock("2330").latest!!.netLots)
    }

    @Test
    fun histockDownWithOldDataShowsTheOldOne() = runTest {
        source().market()
        http.on("broker8.aspx", code = 500, body = "error")
        val r = source().market(force = true)
        assertEquals(102.5e8, r.market!!.latest!!.netAmount, 1.0)
        assertTrue(r.marketNote!!, r.marketNote!!.startsWith("重抓失敗") && "HTTP 500" in r.marketNote!!)
    }

    @Test
    fun cachedSnapshotNeedsNoNetwork() = runTest {
        assertNull(GovBankSource.cached(cache).market)
        assertNull(GovBankSource.cached(cache).fetchedAt)
        source().market()
        val snap = GovBankSource.cached(cache)
        assertTrue(snap.cached)
        assertEquals("2026-11-10 20:00", snap.fetchedAt)
        assertNotNull(snap.market)
    }

    private fun pacedSource(clock: () -> Long, http: FakeHttp) = GovBankSource(
        http, cache,
        blavePacer = Pacer(Pacer.BLAVE_MS, now = clock),
        histockPacer = Pacer(Pacer.HISTOCK_MS, now = clock),
        blaveBase = "https://blave.test", histockUrl = "https://histock.test/stock/broker8.aspx",
        histockStockUrl = "https://histock.test/stock/broker.aspx", now = { now },
    )

    @Test
    fun eachHostIsPaced() = runTest {
        val clocked = FakeHttp { testScheduler.currentTime }.apply {
            on("broker8.aspx", body = market(tue to 1.0))
            on("gov_bank/", body = stock("2330", tue))
        }
        val s = pacedSource({ testScheduler.currentTime }, clocked)
        s.market()
        for (code in listOf("2330", "6515", "2317")) s.stock(code)
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
        val s = pacedSource({ testScheduler.currentTime }, clocked)
        s.market()
        s.stock("3131")
        val (market, blave, page) = clocked.requests.map { it.at }
        assertEquals("不同主機不用等", market, blave)
        assertTrue("全市場與個股頁同一台主機，間隔 ${page - market} ms", page - market >= Pacer.HISTOCK_MS)
    }
}
