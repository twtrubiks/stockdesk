package com.twtrubiks.stockdesk.core

import com.twtrubiks.stockdesk.core.http.Taipei
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * 照 `docs/data-sources.md` 的實測格式「造」出來的官方回應（不是錄的）：證交所 MI_MARGN、櫃買 margin/balance、
 * 集保 qryStock、期交所 futuresQADetail、Yahoo 的 spark（國際指數）。
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
