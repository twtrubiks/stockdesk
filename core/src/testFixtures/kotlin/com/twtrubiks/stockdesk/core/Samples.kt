package com.twtrubiks.stockdesk.core

import com.twtrubiks.stockdesk.core.http.Taipei
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * 照 `docs/data-sources.md` 的實測格式「造」出來的官方回應（不是錄的）：證交所 MI_MARGN、櫃買 margin/balance、
 * 集保 qryStock、期交所 futuresQADetail、Yahoo 的 spark（國際指數）、blave 與 HiStock 的八大官股。
 *
 * 用途：core 的抓取流程測試（快取、市場判斷、token、節流、健全檢查），以及模擬器上 `FakeSources` 的展示資料。
 * **欄位順序本身對不對要靠 fixture 測試**（`MarginParseTest` 等吃錄好的真實回應）：這裡跟 parse 照的是同一份筆記，錯會一起錯。
 */
object Samples {
    // --- 融資融券 -----------------------------------------------------------------

    /**
     * 一檔一天的融資融券（張）。買進、賣出沒給就從前日、今日餘額推出來，讓餘額對得起來
     * （融資 今日 ＝ 前日 ＋ 買進 − 賣出 − 償還；融券 今日 ＝ 前日 ＋ 賣出 − 買進 − 償還），parse 會檢查這件事。
     */
    data class MarginRow(
        val code: String,
        val name: String = code,
        val finPrev: Long = 0,
        val finBal: Long = 0,
        val finLimit: Long = 0,
        val shortPrev: Long = 0,
        val shortBal: Long = 0,
        val shortLimit: Long = 0,
        val finBuy: Long? = null,
        val finSell: Long? = null,
        val finRedeem: Long = 0,
        val shortSell: Long? = null,
        val shortBuy: Long? = null,
        val shortRedeem: Long = 0,
        val offset: Long = 0,
    ) {
        private val finNet = finBal - finPrev + finRedeem // 買進 − 賣出
        private val shortNet = shortBal - shortPrev + shortRedeem // 賣出 − 買進
        val fb: Long get() = finBuy ?: (finSell?.let { it + finNet } ?: maxOf(finNet, 0))
        val fs: Long get() = finSell ?: (fb - finNet)
        val ss: Long get() = shortSell ?: (shortBuy?.let { it + shortNet } ?: maxOf(shortNet, 0))
        val sb: Long get() = shortBuy ?: (ss - shortNet)
    }

    /** 證交所個股那張表的欄位（2026-11-08 對過 2026-11-06 的真實回應）：融券是先買進、後賣出。 */
    private val TWSE_FIELDS = listOf(
        "代號", "名稱", "買進", "賣出", "現金償還", "前日餘額", "今日餘額", "次一營業日限額",
        "買進", "賣出", "現券償還", "前日餘額", "今日餘額", "次一營業日限額", "資券互抵", "註記",
    )

    /** 證交所 MI_MARGN 有開市的一天：第一張是「信用交易統計」（不是個股），第二張才是個股。 */
    fun twseMarginDay(date: LocalDate, rows: List<MarginRow>): String {
        val summary = """{"title":"${roc(date)} 信用交易統計","fields":${arr(listOf("項目", "買進", "賣出", "現金(券)償還", "前日餘額", "今日餘額"))},""" +
            """"data":[["融資(交易單位)","281,234","265,345","3,012","7,654,321","7,667,198"]]}"""
        val data = rows.map { r ->
            listOf(
                r.code, r.name, n(r.fb), n(r.fs), n(r.finRedeem), n(r.finPrev), n(r.finBal), n(r.finLimit),
                n(r.sb), n(r.ss), n(r.shortRedeem), n(r.shortPrev), n(r.shortBal), n(r.shortLimit), n(r.offset), " ",
            )
        }
        val stocks = """{"title":"${roc(date)} 融資融券彙總 (全部)","fields":${arr(TWSE_FIELDS)},"data":${arr2(data)}}"""
        return """{"stat":"OK","date":"${Taipei.ymd(date)}","tables":[$summary,$stocks]}"""
    }

    /** 證交所休市或還沒公布。 */
    fun twseClosed(): String = """{"stat":"很抱歉，沒有符合條件的資料!"}"""

    private val TPEX_FIELDS = listOf(
        "代號", "名稱", "前資餘額(張)", "資買", "資賣", "現償", "資餘額", "資屬證金", "資使用率(%)", "資限額",
        "前券餘額(張)", "券賣", "券買", "券償", "券餘額", "券屬證金", "券使用率(%)", "券限額", "資券相抵(張)", "備註",
    )

    /** 櫃買 margin/balance 有開市的一天（資使用率端點直接給）。 */
    fun tpexMarginDay(date: LocalDate, rows: List<MarginRow>): String = tpex(
        date,
        rows.map { r ->
            listOf(
                r.code, r.name, n(r.finPrev), n(r.fb), n(r.fs), n(r.finRedeem), n(r.finBal), "0", pct(r.finBal, r.finLimit), n(r.finLimit),
                n(r.shortPrev), n(r.ss), n(r.sb), n(r.shortRedeem), n(r.shortBal), "0", pct(r.shortBal, r.shortLimit), n(r.shortLimit), n(r.offset), "",
            )
        },
    )

    /** 櫃買休市或還沒公布：`data` 是空陣列。 */
    fun tpexClosed(date: LocalDate): String = tpex(date, emptyList())

    private fun tpex(date: LocalDate, data: List<List<String>>) =
        """{"stat":"ok","tables":[{"title":"上櫃股票融資融券餘額","date":"${Taipei.ymd(date)}","fields":${arr(TPEX_FIELDS)},"data":${arr2(data)}}]}"""

    // --- 股東持股分級 --------------------------------------------------------------

    /** 集保的 15 個持股分級（股）。 */
    val LEVEL_LABELS = listOf(
        "1-999", "1,000-5,000", "5,001-10,000", "10,001-15,000", "15,001-20,000", "20,001-30,000", "30,001-40,000", "40,001-50,000",
        "50,001-100,000", "100,001-200,000", "200,001-400,000", "400,001-600,000", "600,001-800,000", "800,001-1,000,000", "1,000,001以上",
    )

    data class Level(val holders: Int, val shares: Long, val pct: Double)

    private val LEVEL_HOLDERS = listOf(1_200_000, 650_000, 90_000, 30_000, 15_000, 14_000, 6_000, 3_500, 7_000, 3_000, 1_800, 600, 300, 200, 1_500)
    private val LEVEL_WEIGHTS = listOf(5.0, 8.0, 3.0, 2.0, 1.5, 2.0, 1.5, 1.0, 3.0, 3.0, 4.0, 3.0, 2.0, 2.0)

    /** 15 級的假數字：最後一級（>1000 張，大戶）佔 [bigPct]%，其餘照固定比例分，比例加總 100。 */
    fun tdccLevels(bigPct: Double = 70.0, totalShares: Long = 25_900_000_000, holdersScale: Double = 1.0): List<Level> {
        val rest = 100.0 - bigPct
        val others = LEVEL_WEIGHTS.drop(1).map { round2(rest * it / LEVEL_WEIGHTS.sum()) }
        val pcts = listOf(round2(rest - others.sum())) + others + bigPct
        return pcts.mapIndexed { i, p -> Level((LEVEL_HOLDERS[i] * holdersScale).toInt(), (totalShares * p / 100).toLong(), p) }
    }

    /** 集保查詢頁（GET）：`SYNCHRONIZER_TOKEN`、`firDate`（最新的一週）＋ 可查的週（yyyyMMdd，新到舊）。 */
    fun tdccForm(token: String, weeks: List<String>): String = tdccPage(token, weeks, body = "")

    /**
     * 集保查一檔一週的結果頁（POST）。回應裡帶的是**下一發**要用的 token（[nextToken]）。
     * [adjustment]：多一列「差異數調整」（有些股票會有，不屬於任何分級）。
     */
    fun tdccWeek(nextToken: String, weeks: List<String>, ymd: String, levels: List<Level> = tdccLevels(), adjustment: Boolean = false): String {
        val rows = levels.mapIndexed { i, l -> row("${i + 1}", LEVEL_LABELS[i], l) }.toMutableList()
        if (adjustment) rows += """<tr><td>16</td><td>差異數調整（說明4）</td><td>0</td><td>-1,234</td><td>0.00</td></tr>"""
        val total = Level(levels.sumOf { it.holders }, levels.sumOf { it.shares }, 100.0)
        rows += row("${if (adjustment) 17 else 16}", "合　計", total)
        return tdccPage(nextToken, weeks, body = "<p>資料日期：${ymd}</p>${table(rows)}")
    }

    /** 代號不存在。 */
    fun tdccNotFound(nextToken: String, weeks: List<String>): String = tdccPage(nextToken, weeks, body = "<p>資料日期：</p><p>查無此資料</p>")

    /** token 失效（用了舊的 token）：「資料日期：」後面是空的、表格只有表頭。 */
    fun tdccExpired(nextToken: String, weeks: List<String>): String = tdccPage(nextToken, weeks, body = "<p>資料日期：</p>${table(emptyList())}")

    private fun row(seq: String, label: String, l: Level) =
        "<tr><td>$seq</td><td>$label</td><td>${n(l.holders.toLong())}</td><td>${n(l.shares)}</td><td>${"%.2f".format(Locale.US, l.pct)}</td></tr>"

    private fun table(rows: List<String>) =
        """<table class="table"><tr><th>序</th><th>持股/單位數分級</th><th>人數</th><th>股數/單位數</th><th>占集保庫存數比例 (%)</th></tr>${rows.joinToString("")}</table>"""

    private fun tdccPage(token: String, weeks: List<String>, body: String) = """
        <html><head><title>集保戶股權分散表查詢</title></head><body>
        <form id="form1" method="post" action="/portal/zh/smWeb/qryStock">
        <input type="hidden" name="SYNCHRONIZER_TOKEN" value="$token" />
        <input type="hidden" name="SYNCHRONIZER_URI" value="/portal/zh/smWeb/qryStock" />
        <input type="hidden" name="method" value="submit" />
        <input type="hidden" name="firDate" value="${weeks.firstOrNull().orEmpty()}" />
        <select name="scaDate" id="scaDate">${weeks.joinToString("") { "<option value=\"$it\">$it</option>" }}</select>
        <input type="text" name="stockNo" value="" />
        </form>
        $body
        </body></html>
    """.trimIndent()

    // --- 權重 ------------------------------------------------------------------------

    /** 權重表的一筆。 */
    data class Constituent(val rank: Int, val code: String, val name: String, val weight: Double)

    private val NAMED = listOf("2330" to "台積電", "2317" to "鴻海", "2454" to "聯發科", "2308" to "台達電", "2382" to "廣達", "2327" to "國巨*", "6829" to "正瀚-創")

    /**
     * [n] 檔、比重加總約 100% 的假成分股：第一名 2330 約 40%，其餘 60% 照排行的 1.2 次方遞減（第二名約 7%、最後一名約 0.004%），
     * 名稱帶符號的（「國巨*」、「正瀚-創」）也在裡面。
     */
    fun constituents(n: Int = 1_070): List<Constituent> {
        val shape = (2..n).map { Math.pow(it.toDouble(), -1.2) }
        val scale = 60.0 / shape.sum()
        return (1..n).map { rank ->
            val (code, name) = NAMED.getOrNull(rank - 1) ?: ("%04d".format(Locale.US, 3000 + rank) to "成分股$rank")
            val weight = if (rank == 1) 40.0 else scale * shape[rank - 2]
            Constituent(rank, code, name, Math.round(weight * 10_000) / 10_000.0)
        }
    }

    /**
     * 期交所權重頁：「資料日期：YYYY/M/D」＋ 左右兩欄的表（前 [leftRows] 筆在左、其餘在右），每筆 4 格：排行／代號／名稱／比重。
     * 排行超過千帶千分位（「1,068」）。右欄比左欄短的列，右邊 4 格是空的。
     */
    fun taifexWeights(date: LocalDate, rows: List<Constituent>, leftRows: Int = 535): String {
        val left = rows.take(leftRows)
        val right = rows.drop(leftRows)
        fun cells(c: Constituent?) = if (c == null) "<td></td><td></td><td></td><td></td>"
        else "<td>${n(c.rank.toLong())}</td><td>${c.code}</td><td>${c.name}</td><td>${"%.4f".format(Locale.US, c.weight)}%</td>"
        val trs = left.indices.joinToString("\n") { i -> "<tr>${cells(left[i])}${cells(right.getOrNull(i))}</tr>" }
        return """
            <html><body>
            <h3>臺灣證券交易所發行量加權股價指數成分股暨市值比重</h3>
            <p>資料日期：${date.year}/${date.monthValue}/${date.dayOfMonth}</p>
            <table class="table_c">
            <tr><th>排行</th><th>證券代號</th><th>證券名稱</th><th>市值佔 大盤比重</th><th>排行</th><th>證券代號</th><th>證券名稱</th><th>市值佔 大盤比重</th></tr>
            $trs
            </table>
            <p>註：每月月底更新一次</p>
            </body></html>
        """.trimIndent()
    }

    // --- 國際指數（Yahoo spark） ----------------------------------------------------

    /**
     * 一個指數一天的假走勢：[start]～[end] 是那天的盤（epoch 秒），每 5 分鐘一點，從昨收走到 [last]、中間有起伏；
     * [upTo] 比 [end] 早就是盤中抓的（點只到那時候）。
     */
    data class SparkDay(
        val symbol: String,
        val zone: String,
        val prevClose: Double,
        val last: Double,
        val start: Long,
        val end: Long,
        val upTo: Long = end,
    ) {
        val times: List<Long> get() = (start..minOf(end, upTo) step 300).toList()

        val values: List<Double>
            get() {
                val n = times.size
                return times.indices.map { i ->
                    val f = if (n <= 1) 1.0 else i.toDouble() / (n - 1)
                    val wiggle = if (i == n - 1) 0.0 else prevClose * 0.004 * Math.sin(i * 0.55) * Math.sin(f * Math.PI)
                    round2(prevClose + (last - prevClose) * f + wiggle)
                }
            }
    }

    /** [date] 在 [zone] 當地 [open]～[close] 的盤，漲跌 [changePct]%；[upTo] 不是 null 就是盤中（當地時間）。 */
    fun sparkDay(
        symbol: String,
        zone: String,
        date: LocalDate,
        open: LocalTime,
        close: LocalTime,
        prevClose: Double,
        changePct: Double,
        upTo: LocalTime? = null,
    ): SparkDay {
        val z = ZoneId.of(zone)
        val start = date.atTime(open).atZone(z).toEpochSecond()
        val end = date.atTime(close).atZone(z).toEpochSecond()
        val until = upTo?.let { date.atTime(it).atZone(z).toEpochSecond() } ?: end
        // 盤中的話「最新」是走到一半的值
        val f = (until - start).toDouble() / (end - start)
        return SparkDay(symbol, zone, prevClose, round2(prevClose * (1 + changePct / 100 * f)), start, end, until)
    }

    /**
     * Yahoo `v7/finance/spark?range=1d&interval=5m` 的回應（照 2026-11-06 錄的格式，`fixtures/yahoo-spark.json`）。
     * [nullEvery] > 0 時每隔幾點塞一個 null（那 5 分鐘沒成交）；[noPoints] 裡的代號只有 meta、沒有點（開盤前的樣子）。
     */
    fun yahooSpark(days: List<SparkDay>, nullEvery: Int = 0, noPoints: Set<String> = emptySet()): String {
        val results = days.joinToString(",") { d ->
            val period = """{"timezone":"X","start":${d.start},"end":${d.end},"gmtoffset":0}"""
            val price = d.values.last()
            val meta = """{"currency":"X","symbol":"${esc(d.symbol)}","exchangeTimezoneName":"${d.zone}","instrumentType":"INDEX",""" +
                """"regularMarketPrice":$price,"regularMarketTime":${minOf(d.upTo, d.end) + 60},"chartPreviousClose":${d.prevClose},"previousClose":${d.prevClose},""" +
                """"currentTradingPeriod":{"regular":$period},"tradingPeriods":[[$period]],"dataGranularity":"5m","range":"1d"}"""
            val response = if (d.symbol in noPoints) {
                """{"meta":$meta,"indicators":{"quote":[{}]}}"""
            } else {
                val closes = d.values.mapIndexed { i, v -> if (nullEvery > 0 && i % nullEvery == nullEvery - 1 && i != d.values.size - 1) "null" else "$v" }
                """{"meta":$meta,"timestamp":${d.times.joinToString(",", "[", "]")},"indicators":{"quote":[{"close":${closes.joinToString(",", "[", "]")}}]}}"""
            }
            """{"symbol":"${esc(d.symbol)}","response":[$response]}"""
        }
        return """{"spark":{"result":[$results],"error":null}}"""
    }

    // --- 八大官股（blave、HiStock，非官方） ---------------------------------------------

    /** 八家券商在 blave 的名稱（`bank_name`，2026-11-10 實測的順序）。 */
    val GOV_BANKS = listOf("兆豐", "台企銀", "合庫", "土銀", "彰銀", "第一", "臺銀", "華南")

    /** 一家券商一天：股數（股）、金額（元）。 */
    data class BankFlow(val bank: String, val buy: Long, val sell: Long, val buyAmount: Double, val sellAmount: Double)

    /** 一天買 [buyLots] 張、賣 [sellLots] 張、每股 [price] 元，八家平均分（除不盡的放在第一家）。 */
    fun govBankFlows(buyLots: Long, sellLots: Long, price: Double): List<BankFlow> {
        val n = GOV_BANKS.size
        fun part(total: Long, i: Int) = total / n + if (i == 0) total % n else 0
        return GOV_BANKS.mapIndexed { i, bank ->
            val b = part(buyLots * 1000, i)
            val s = part(sellLots * 1000, i)
            BankFlow(bank, b, s, b * price, s * price)
        }
    }

    /**
     * blave `studio/charts/twstock/gov_bank/<代號>?start=&end=` 的回應（照 2026-11-10 錄的格式，`fixtures/blave-gov-bank-2330.json`）：
     * 一列一家券商一天，日期舊到新，金額帶 `.0`；沒登入時 `history_tier` 是 anon。[days] 是空的就是 blave 沒有這一檔（實測回 200 ＋ 空陣列）。
     */
    fun blaveGovBank(code: String, days: Map<LocalDate, List<BankFlow>>): String {
        val rows = days.toSortedMap().flatMap { (d, flows) ->
            flows.map { f ->
                """{"bank_name": "${esc(f.bank)}", "buy": ${f.buy}, "buy_amount": ${"%.1f".format(Locale.US, f.buyAmount)}, "date": "$d", """ +
                    """"sell": ${f.sell}, "sell_amount": ${"%.1f".format(Locale.US, f.sellAmount)}, "stock_id": "${esc(code)}"}"""
            }
        }
        return """{"data": [${rows.joinToString(", ")}], "history_days": 92, "history_next_tier": "login", "history_next_window": "6m", """ +
            """"history_tier": "anon", "history_truncated": true, "history_window": "3m", "pro_limited": true, "stock_id": "${esc(code)}"}"""
    }

    /** HiStock 排行的一列：金額是萬元（賣超是負的）。 */
    data class RankSample(val code: String, val name: String, val wan: Long)

    /**
     * HiStock `stock/broker8.aspx`（照 2026-11-10 錄的頁面，`fixtures/histock-broker8.html`）：
     * `loadChart({"SumMoney":"[[epoch 毫秒（台北的午夜）, 億元], …]","Price":"…"})`，舊到新；
     * 底下買超、賣超排行一檔一個 `<li title="2026-11-09 八大公股行庫合計買超 Top.1 : 台積電 (2330) 合計：213,162萬元">`（日期是最新那天）。
     */
    fun histockBroker8(days: List<Pair<LocalDate, Double>>, topBuy: List<RankSample> = emptyList(), topSell: List<RankSample> = emptyList()): String {
        val sorted = days.sortedBy { it.first }
        fun ms(d: LocalDate) = d.atStartOfDay(Taipei.ZONE).toInstant().toEpochMilli()
        val sum = sorted.joinToString(",", "[", "]") { (d, yi) -> "[${ms(d)},$yi]" }
        val price = sorted.joinToString(",", "[", "]") { (d, _) -> "[${ms(d)},42711.92]" }
        val date = sorted.lastOrNull()?.first
        fun list(side: String, rows: List<RankSample>) = rows.mapIndexed { i, r ->
            """<li onclick='goUrl("${r.code}");' title="$date 八大公股行庫合計$side Top.${i + 1} : ${r.name} (${r.code}) 合計：${n(r.wan)}萬元" """ +
                """style="cursor:pointer;height:26px;"><span class="w100 name">&nbsp;${r.name}</span><span class="w70">${n(r.wan)}</span></li>"""
        }.joinToString("")
        return """
            <html><head><title>八大官股銀行/公股行庫買賣超排名 - HiStock嗨投資理財社群</title>
            <script type="text/javascript">
                ${'$'}(document).ready(function () {
                    loadChart({"SumMoney":"$sum","Price":"$price"});
                });
            </script></head><body>
            <ul class="menu"><li title="自選股"><a href="/stock/mystock.aspx">自選股</a></li></ul>
            <div class="head1">八大官股銀行/公股行庫買賣總金額走勢圖</div><div id="hChart"></div>
            <h3 class="seoh3">Top 30 八大官股銀行合計買超排名</h3><ul class="stock-list">${list("買超", topBuy)}</ul>
            <h3 class="seoh3">Top 30 八大官股銀行合計賣超排名</h3><ul class="stock-list">${list("賣超", topSell)}</ul>
            </body></html>
        """.trimIndent()
    }

    /** HiStock 個股頁一天的合計：買超張數（張，捨去成整數）、金額（萬元），賣超是負的。 */
    data class StockDaySample(val date: LocalDate, val lots: Long, val wan: Long)

    /**
     * HiStock `stock/broker.aspx?no=<代號>`（個股的八大官股，照 2026-11-10 錄的頁面，`fixtures/histock-broker-3131.html`）：
     * 標題 `弘塑 (3131) 八大官股買賣超 - HiStock嗨投資理財社群`；
     * `initalChart_Broker8({"Money1":"[[epoch 毫秒（UTC 的午夜）, 萬元], …]", …, "Count8":"…","SumCount":"…","SumMoney":"…","Price":"…"})`，舊到新。
     * 八家各自的照合計平均分（除不盡的放在第一家），`Count` 帶 `.0`；`Price` 比合計多一天（今天盤中，實測如此）。
     * HiStock 查不到代號時回的是台積電的頁面：[code] 傳別的代號就造得出來。
     */
    fun histockStockGovBank(code: String, name: String, days: List<StockDaySample>): String {
        val sorted = days.sortedBy { it.date }
        fun ms(d: LocalDate) = d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        fun part(total: Long, i: Int) = total / 8 + if (i == 0) total % 8 else 0
        fun series(value: (StockDaySample) -> String) = sorted.joinToString(",", "[", "]") { "[${ms(it.date)},${value(it)}]" }
        val money = (1..8).map { i -> "\"Money$i\":\"${series { "${part(it.wan, i - 1)}" }}\"" }
        val count = (1..8).map { i -> "\"Count$i\":\"${series { "${part(it.lots, i - 1)}.0" }}\"" }
        val priceDays = sorted.map { it.date } + listOfNotNull(sorted.lastOrNull()?.date?.plusDays(1))
        val price = priceDays.joinToString(",", "[", "]") { "[${ms(it)},1580]" }
        val chart = (money + count + listOf(
            "\"SumCount\":\"${series { "${it.lots}" }}\"",
            "\"SumMoney\":\"${series { "${it.wan}" }}\"",
            "\"Price\":\"$price\"",
        )).joinToString(",", "{", "}")
        return """
            <!DOCTYPE html><html><head><title>${name} (${code}) 八大官股買賣超 - HiStock嗨投資理財社群</title></head><body>
            <div class="cbchart"><div id="chart_Broker8" class="mainchart schart" style="height:355px;"></div><div id="chartInfo_Broker8" ></div></div>
            <script>
                ${'$'}(document).ready(function () {
                    initalChart_Broker8($chart);
                });

                function initalChart_Broker8(data) {
                    options.series[1].data = eval(data['SumMoney']);
                }
            </script></body></html>
        """.trimIndent()
    }

    // --- 共用 ------------------------------------------------------------------------

    /** 千分位（官方來源的數字都帶）。 */
    private fun n(v: Long) = "%,d".format(Locale.US, v)

    private fun pct(a: Long, b: Long) = if (b == 0L) "0.00" else "%.2f".format(Locale.US, a * 100.0 / b)

    private fun round2(d: Double) = Math.round(d * 100) / 100.0

    /** 民國年的日期（「115年11月06日」），證交所表格標題用的。 */
    private fun roc(d: LocalDate) = "%d年%02d月%02d日".format(Locale.US, d.year - 1911, d.monthValue, d.dayOfMonth)

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun arr(items: List<String>) = items.joinToString(",", "[", "]") { "\"${esc(it)}\"" }

    private fun arr2(rows: List<List<String>>) = rows.joinToString(",", "[", "]") { arr(it) }
}
