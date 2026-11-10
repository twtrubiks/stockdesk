package com.twtrubiks.stockdesk

import com.twtrubiks.stockdesk.core.FakeTdcc
import com.twtrubiks.stockdesk.core.Samples
import com.twtrubiks.stockdesk.core.http.Taipei
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import java.net.InetAddress
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 模擬器上假裝資料來源的 MockWebServer（App 抓資料都打這裡）。回應用 core testFixtures 的 [Samples] 現造（照實測格式，數字是假的）：
 *   /rwd/zh/marginTrading/MI_MARGN → 證交所：平日有 2330、2317，週六日休市
 *   /www/zh-tw/margin/balance      → 櫃買：平日有 6515、8299，週六日休市
 *   /portal/zh/smWeb/qryStock      → 集保（[FakeTdcc]：token 綁 session cookie）：GET 查詢頁（最近 10 個星期五），POST 任何代號都有 15 級
 *   /cht/9/futuresQADetail         → 期交所：上個月底的權重表（1,070 檔）
 *   /v7/finance/spark              → Yahoo：8 個國際指數，台股是今天盤中（到 11:00），其他是前一個平日收盤
 *   /studio/charts/twstock/gov_bank/<代號> → blave：上櫃的（[OTC]，跟上面櫃買同兩檔）回空的（實測 blave 沒有上櫃股），其他代號都有最近幾個平日的八大官股
 *   /stock/broker8.aspx            → HiStock：最近 20 個平日的八大官股總額 ＋ 買超、賣超前 5 名
 *   /stock/broker.aspx?no=<代號>    → HiStock 的個股頁：任何代號都有最近 40 個平日的八大官股（只有淨額）
 * App 走 debug 版的 network security config 才連得到這裡的 http://localhost（`src/debug/res/xml/network_security_config.xml`）。
 * fixture 錄好之後可以改成回錄好的真實回應（放 `androidTest/assets/`）。
 */
class FakeSources : AutoCloseable {
    private val server = MockWebServer()
    private val today = Taipei.today()

    /** 收到的請求（方法 ＋ 路徑），測試失敗時看有沒有真的打到這裡。 */
    val requests = CopyOnWriteArrayList<String>()

    private val weeks = generateSequence(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY))) { it.minusWeeks(1) }
        .take(10).map(Taipei::ymd).toList()

    /** 任何代號都有 15 級，每週的數字略有不同。 */
    private val tdcc = FakeTdcc(weeks) { _, ymd ->
        val k = LocalDate.parse(ymd, DateTimeFormatter.BASIC_ISO_DATE).toEpochDay() / 7
        Samples.tdccLevels(bigPct = 72.0 + (k % 5) * 0.13, holdersScale = 1.0 + (k % 4) * 0.002)
    }

    /** 集保回空表（token 或 cookie 沒帶對）的次數。 */
    val tdccRejected: Int get() = tdcc.rejected

    fun start(): Endpoints {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.target}"
                val url = request.url
                if (url.encodedPath == "/portal/zh/smWeb/qryStock") return tdcc(request)
                if (url.encodedPath.startsWith(GOV_BANK_PATH)) return json(blave(url.encodedPath.removePrefix(GOV_BANK_PATH)))
                val body = when (url.encodedPath) {
                    "/rwd/zh/marginTrading/MI_MARGN" -> twse(LocalDate.parse(url.queryParameter("date")!!, DateTimeFormatter.BASIC_ISO_DATE))
                    "/www/zh-tw/margin/balance" -> tpex(LocalDate.parse(url.queryParameter("date")!!, DateTimeFormatter.ofPattern("yyyy/MM/dd")))
                    "/cht/9/futuresQADetail" -> Samples.taifexWeights(today.withDayOfMonth(1).minusDays(1), Samples.constituents())
                    "/v7/finance/spark" -> yahoo()
                    "/stock/broker8.aspx" -> histock()
                    "/stock/broker.aspx" -> histockStock(url.queryParameter("no").orEmpty())
                    else -> return MockResponse(404, Headers.headersOf(), "FakeSources：沒有 ${url.encodedPath}")
                }
                return MockResponse(200, Headers.headersOf("Content-Type", "text/plain; charset=utf-8"), body)
            }
        }
        // 明確綁 127.0.0.1：Android 上 localhost 可能解析成 ::1，network security config 只放行 localhost / 127.0.0.1
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val base = server.url("/").toString().trimEnd('/')
        return Endpoints(
            twse = base,
            tpex = base,
            tdcc = "$base/portal/zh/smWeb/qryStock",
            taifex = "$base/cht/9/futuresQADetail",
            yahoo = "$base/v7/finance/spark",
            blave = base,
            histock = "$base/stock/broker8.aspx",
            histockStock = "$base/stock/broker.aspx",
        )
    }

    private fun json(body: String) = MockResponse(200, Headers.headersOf("Content-Type", "application/json"), body)

    /** 最近 [n] 個平日（新到舊，今天是平日就含今天）。 */
    private fun weekdays(n: Int) = generateSequence(today) { it.minusDays(1) }.filter { !weekend(it) }.take(n).toList()

    /** 八大官股：每檔每天的張數不一樣、有買超有賣超，價錢隨代號給一個。上櫃的回空的 data（App 會改抓 HiStock 的個股頁）。 */
    private fun blave(code: String): String {
        if (code in OTC) return Samples.blaveGovBank(code, emptyMap())
        val seed = code.sumOf { it.code }
        val price = 100.0 + seed % 900
        return Samples.blaveGovBank(
            code,
            weekdays(8).associateWith { d ->
                val k = (d.toEpochDay() + seed) % 7
                Samples.govBankFlows(buyLots = 800 + k * 150, sellLots = 1_000 - k * 60, price = price)
            },
        )
    }

    /** HiStock 的個股頁：最近 40 個平日，每天的張數不一樣、有買超有賣超（萬元 ＝ 張 × 價錢 ÷ 10）。 */
    private fun histockStock(code: String): String {
        val seed = code.sumOf { it.code }
        val price = 100L + seed % 900
        return Samples.histockStockGovBank(
            code,
            code,
            weekdays(40).map { d ->
                val lots = ((d.toEpochDay() + seed) % 7 - 3) * 45 + 12
                Samples.StockDaySample(d, lots, lots * price / 10)
            },
        )
    }

    private fun histock(): String = Samples.histockBroker8(
        weekdays(20).map { d -> d to ((d.toEpochDay() % 9) - 3) * 37.5 + 12.25 },
        topBuy = listOf(
            Samples.RankSample("2330", "台積電", 213_162),
            Samples.RankSample("0050", "元大台灣50", 138_302),
            Samples.RankSample("3037", "欣興", 125_116),
            Samples.RankSample("3189", "景碩", 92_198),
            Samples.RankSample("2303", "聯電", 75_997),
        ),
        topSell = listOf(
            Samples.RankSample("2327", "國巨*", -69_172),
            Samples.RankSample("2317", "鴻海", -43_662),
            Samples.RankSample("2886", "兆豐金", -34_257),
            Samples.RankSample("2395", "研華", -21_004),
            Samples.RankSample("2454", "聯發科", -18_880),
        ),
    )

    private fun weekend(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY

    private fun twse(d: LocalDate) =
        if (weekend(d)) Samples.twseClosed() else Samples.twseMarginDay(d, listOf(margin("2330", "台積電", d, 24_000), margin("2317", "鴻海", d, 41_000)))

    private fun tpex(d: LocalDate) =
        if (weekend(d)) Samples.tpexClosed(d) else Samples.tpexMarginDay(d, listOf(margin("6515", "穎崴", d, 1_800), margin("8299", "群聯", d, 2_500)))

    /** 看起來像真的、每天不一樣的假數字（張）。 */
    private fun margin(code: String, name: String, d: LocalDate, base: Long): Samples.MarginRow {
        val k = d.toEpochDay()
        val bal = base + (k % 11) * base / 200
        val prev = bal - ((k % 5) - 2) * base / 300
        val short = base / 12 + (k % 7) * base / 900
        return Samples.MarginRow(
            code, name, finPrev = prev, finBal = bal, finLimit = base * 9, shortPrev = short + (k % 3) * 7 - 7, shortBal = short, shortLimit = base * 9,
            finSell = base / 45, finRedeem = base / 900, shortBuy = base / 100,
        )
    }

    /** 國際指數：數字照 2026-11-06 那天抓的真實回應取整，台股多一個盤中的樣子（線只畫到一半）。 */
    private fun yahoo(): String {
        val lastWeekday = generateSequence(today) { it.minusDays(1) }.first { !weekend(it) }
        val prevWeekday = generateSequence(lastWeekday.minusDays(1)) { it.minusDays(1) }.first { !weekend(it) }
        fun day(symbol: String, zone: String, open: LocalTime, close: LocalTime, prev: Double, pct: Double, date: LocalDate = prevWeekday, upTo: LocalTime? = null) =
            Samples.sparkDay(symbol, zone, date, open, close, prev, pct, upTo)
        val ny = "America/New_York"
        return Samples.yahooSpark(
            listOf(
                day("^TWII", "Asia/Taipei", LocalTime.of(9, 0), LocalTime.of(13, 30), 41_574.72, 0.85, date = lastWeekday, upTo = LocalTime.of(11, 0)),
                day("^DJI", ny, LocalTime.of(9, 30), LocalTime.of(16, 0), 51_523.06, -0.46),
                day("^GSPC", ny, LocalTime.of(9, 30), LocalTime.of(16, 0), 7_353.73, 0.31),
                day("^IXIC", ny, LocalTime.of(9, 30), LocalTime.of(16, 0), 24_315.0, 1.09),
                day("^SOX", ny, LocalTime.of(9, 30), LocalTime.of(16, 0), 9_011.14, 4.21),
                day("^N225", "Asia/Tokyo", LocalTime.of(9, 0), LocalTime.of(15, 30), 61_077.9, -5.96),
                day("^KS11", "Asia/Seoul", LocalTime.of(9, 0), LocalTime.of(15, 30), 5_383.8, -6.02),
                day("^HSI", "Asia/Hong_Kong", LocalTime.of(9, 30), LocalTime.of(16, 0), 24_796.98, -1.27),
            ),
        )
    }

    private fun tdcc(request: RecordedRequest): MockResponse {
        val cookie = request.headers["Cookie"]
        val reply = if (request.method == "GET") tdcc.get(cookie) else tdcc.post(cookie, FakeTdcc.parseForm(request.body?.utf8().orEmpty()))
        val headers = Headers.Builder().add("Content-Type", "text/html; charset=utf-8")
        reply.setCookie?.let { headers.add("Set-Cookie", it) }
        return MockResponse(200, headers.build(), reply.body)
    }

    override fun close() = server.close()

    private companion object {
        const val GOV_BANK_PATH = "/studio/charts/twstock/gov_bank/"

        /** 假的上櫃股（櫃買的融資融券也是這兩檔）：blave 回空的。 */
        val OTC = setOf("6515", "8299")
    }
}
