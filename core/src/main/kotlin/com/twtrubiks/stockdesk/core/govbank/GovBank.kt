package com.twtrubiks.stockdesk.core.govbank

import com.twtrubiks.stockdesk.core.http.Http
import com.twtrubiks.stockdesk.core.http.Pacer
import com.twtrubiks.stockdesk.core.http.Taipei
import com.twtrubiks.stockdesk.core.http.USER_AGENT
import com.twtrubiks.stockdesk.core.model.StockRef
import com.twtrubiks.stockdesk.core.store.CacheStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException
import kotlin.math.roundToLong

/**
 * 八大官股（八家公股銀行旗下的券商：臺銀、土銀、合庫、第一、華南、彰銀、兆豐、台企銀，所有分點加起來）某一檔某一天的買賣。
 * 股數是「股」、金額是「元」（blave 回的單位）；1 張 ＝ 1,000 股。HiStock 的個股頁只有淨額，見 [net]。
 */
@Serializable
data class GovBankDay(
    val date: String,
    val buy: Long,
    val sell: Long,
    val buyAmount: Double,
    val sellAmount: Double,
) {
    /** 買超是正的、賣超是負的（股）。 */
    val netShares: Long get() = buy - sell
    val netLots: Long get() = (netShares / 1000.0).roundToLong()

    /** 買超金額（元），賣超是負的。張數與金額的正負可能不一樣（同一天買的價位比賣的低）。 */
    val netAmount: Double get() = buyAmount - sellAmount

    companion object {
        /**
         * 只有淨額的一天（HiStock 的個股頁）：買超記在買的那一邊、賣超記在賣的那一邊，另一邊是 0。
         * [netShares]、[netAmount] 跟傳進來的一樣（畫面只用淨額）；[buy]、[sell] 就不是真的買進、賣出各多少。
         */
        fun net(date: String, netShares: Long, netAmount: Double) = GovBankDay(
            date,
            buy = maxOf(0L, netShares),
            sell = maxOf(0L, -netShares),
            buyAmount = maxOf(0.0, netAmount),
            sellAmount = maxOf(0.0, -netAmount),
        )
    }
}

/**
 * 自選股的一檔從哪裡抓：blave（上市股票），或 HiStock 的個股頁（blave 沒有的：上櫃股票 blave 都是空的）。
 * blave 沒有資料、HiStock 有的檔記在 `meta`（[GovBankSource.viaKey]），之後直接抓 HiStock。
 */
enum class GovBankVia { BLAVE, HISTOCK }

/** 全市場一天的八大官股買賣超金額（元，正的是買超）。 */
@Serializable
data class MarketDay(val date: String, val netAmount: Double)

/** 全市場最新一天的買超或賣超排行的一列（金額：元，賣超是負的）。 */
@Serializable
data class RankRow(val rank: Int, val code: String, val name: String, val netAmount: Double)

/** HiStock 的八大官股頁：每天的買賣超總金額（新到舊）、[topDate] 那天買超與賣超的前幾名。 */
@Serializable
data class GovBankMarket(
    val days: List<MarketDay>,
    val topDate: String?,
    val topBuy: List<RankRow>,
    val topSell: List<RankRow>,
) {
    val latest: MarketDay? get() = days.firstOrNull()
}

/**
 * 自選股的一檔：最近幾個交易日（新到舊，沒抓過是空的）。[note] 是這一檔這次沒抓到的原因（blave、HiStock 都沒有這一檔、代號無效）；
 * [via] 是資料從哪裡來（官股頁點這一檔開那一家的網頁）。
 */
data class StockGovBank(val stock: StockRef, val days: List<GovBankDay>, val note: String? = null, val via: GovBankVia = GovBankVia.BLAVE) {
    val latest: GovBankDay? get() = days.firstOrNull()
}

/** 自選股在 [date] 那天的合計：那天有資料的 [count] 檔加起來。 */
data class WatchlistTotal(val date: String, val count: Int, val netShares: Long, val netAmount: Double) {
    val netLots: Long get() = (netShares / 1000.0).roundToLong()
}

/**
 * 官股頁要顯示的。
 * [market] 是 HiStock 的全市場（抓不到又沒有舊的就是 null，原因在 [marketNote]；重抓失敗但有舊的，[marketNote] 是提示）。
 * [fetchedAt] 是最近一次真的連網的時間（台北）；[cached] 是這次完全沒有連網。
 * [warning] 是自選股抓到一半停下來的原因（沒回應、被限流）：還沒抓的那幾檔顯示快取裡的舊資料。
 */
data class GovBank(
    val market: GovBankMarket?,
    val marketNote: String?,
    val stocks: List<StockGovBank>,
    val fetchedAt: String?,
    val cached: Boolean,
    val warning: String? = null,
) {
    /** 自選股的合計：自選股裡最新的那一天、那天有資料的檔加起來（那天沒成交、還沒更新的檔不算在裡面）。 */
    fun watchlistTotal(): WatchlistTotal? {
        val date = stocks.mapNotNull { it.latest?.date }.maxOrNull() ?: return null
        val rows = stocks.mapNotNull { s -> s.days.firstOrNull { it.date == date } }
        return WatchlistTotal(date, rows.size, rows.sumOf { it.netShares }, rows.sumOf { it.netAmount })
    }
}

class GovBankException(message: String, val stop: Boolean = false) : Exception(message)

/** 抓的過程中一步一步回報（第 1 步是全市場，之後一檔一步）；最後一個是 [Done]。 */
sealed interface GovBankEvent {
    data class Progress(val done: Int, val total: Int, val label: String, val cached: Boolean) : GovBankEvent
    data class Done(val result: GovBank) : GovBankEvent
}

/**
 * 八大官股的買賣超。**兩個來源都不是官方的**（證交所、櫃買的券商分點日報表要過圖形驗證碼，不抓）：
 *
 * - 全市場：HiStock 的 `stock/broker8.aspx`（八大官股銀行／公股行庫買賣超排名），一發。頁面裡嵌著每天的買賣超總金額
 *   （`loadChart({"SumMoney":"[[epoch 毫秒, 億元], …]"})`，約半年）與最新一天買超、賣超前 30 名
 * - 自選股每一檔：blave 網頁用的 `studio/charts/twstock/gov_bank/<代號>?start=&end=`（不用登入；一發最多 31 天，
 *   沒登入只給最近 3 個月）。一列一家券商一天，這裡加成一天一筆。一檔一發，照順序打。
 *   **blave 只有上市股票**（上櫃股回 200 ＋ 空的 data）：空的就改抓 HiStock 的個股頁 `stock/broker.aspx?no=<代號>`
 *   （上市、上櫃都有，約 3 個月，只有淨額），HiStock 有資料就記住這一檔走 HiStock（[GovBankVia]），之後直接抓 HiStock
 *
 * 什麼時候要抓（進頁面時；[load] 的 force 是重新整理，全部重抓）：
 * - 「該公布的那天」：平日 18:30（台北）以後是今天，以前是前一個平日（[publishedDate]）
 * - 全市場：快取的最新一天 ≥ 該公布的那天就不抓；否則抓，[RETRY_MS] 內抓過也先不抓（還沒公布、假日）
 * - 個股：目標是全市場這次拿到的最新一天（假日、還沒公布都跟著它；全市場拿不到才用該公布的那天），
 *   快取的最新一天 ≥ 目標就不抓，[RETRY_MS] 內抓過也不抓（那天沒成交、blave 比 HiStock 晚更新）
 *
 * 每個主機一個 [Pacer]（各 2 秒，HiStock 的全市場與個股頁共用一個）。blave 沒回應、HTTP 429／503（上游配額用完）、格式不對就停下來，
 * 後面的檔顯示快取；HiStock 的個股頁沒回應、HTTP 錯誤也停。代號無效（400）、兩邊都沒有資料、HiStock 個股頁的格式不對只記在那一檔，
 * 繼續抓下一檔。HiStock 的全市場抓不到不影響個股。
 *
 * 快取：個股以 (代號, 日期) 存 `gov_bank_days`（只留 [KEEP_CALENDAR_DAYS] 天），全市場整份存 `meta`，抓取時間也在 `meta`。
 * `blaveBase`、`histockUrl`、`histockStockUrl` 可以換（測試接 MockWebServer）。
 */
class GovBankSource(
    private val http: Http,
    private val cache: CacheStore,
    private val blavePacer: Pacer = Pacer(Pacer.BLAVE_MS),
    private val histockPacer: Pacer = Pacer(Pacer.HISTOCK_MS),
    private val blaveBase: String = "https://api.blave.org",
    private val histockUrl: String = "https://histock.tw/stock/broker8.aspx",
    private val histockStockUrl: String = "https://histock.tw/stock/broker.aspx",
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun load(stocks: List<StockRef>, force: Boolean = false): Flow<GovBankEvent> = flow {
        val t = now()
        val total = stocks.size + 1
        var fetchedAny = false

        // 1. 全市場（HiStock）
        val oldMarket = cachedMarket(cache)
        val oldMarketAt = cache.meta(META_MARKET_FETCHED)?.toLongOrNull()
        val marketFresh = !force && oldMarket != null && oldMarketAt != null &&
            fresh(oldMarket.latest?.date, oldMarketAt, publishedDate(t).toString(), t)
        var market = oldMarket
        var marketOk = marketFresh
        var marketNote: String? = null
        if (!marketFresh) {
            try {
                val m = fetchMarket()
                cache.putMeta(META_MARKET, json.encodeToString(m))
                cache.putMeta(META_MARKET_FETCHED, now().toString())
                market = m
                marketOk = true
                fetchedAny = true
            } catch (e: GovBankException) {
                marketNote = failNote(e.message ?: "抓取失敗", oldMarket != null)
            } catch (e: IOException) {
                marketNote = failNote("HiStock 沒回應（${e.message ?: e.javaClass.simpleName}）", oldMarket != null)
            }
        }
        emit(GovBankEvent.Progress(1, total, "全市場", cached = marketFresh))

        // 2. 自選股一檔一發（blave）
        val target = market?.latest?.date?.takeIf { marketOk } ?: publishedDate(t).toString()
        val results = mutableListOf<StockGovBank>()
        var stopped: String? = null
        for ((i, s) in stocks.withIndex()) {
            val old = cache.govBankDays(s.code, KEEP_DAYS)
            val at = cache.meta(fetchedKey(s.code))?.toLongOrNull()
            val isFresh = !force && at != null && fresh(old.firstOrNull()?.date, at, target, t)
            if (isFresh || stopped != null) {
                results += StockGovBank(s, old, via = via(cache, s.code))
                emit(GovBankEvent.Progress(i + 2, total, s.label, cached = true))
                continue
            }
            var note: String? = null
            try {
                val days = fetchStock(s.code, t)
                cache.putGovBankDays(s.code, days, keepSince = LocalDate.parse(target).minusDays(KEEP_CALENDAR_DAYS))
                cache.putMeta(fetchedKey(s.code), now().toString())
                fetchedAny = true
                if (days.isEmpty()) note = "blave、HiStock 都沒有這一檔的八大官股資料（興櫃、停止交易，或代號不對）"
            } catch (e: GovBankException) {
                if (e.stop) {
                    stopped = e.message
                } else {
                    note = e.message
                    // 代號無效之類的：記下抓過的時間，不要每次進頁面都再問一次
                    cache.putMeta(fetchedKey(s.code), now().toString())
                    fetchedAny = true
                }
            } catch (e: IOException) {
                stopped = "blave 沒回應，可能被限流，過一陣子再試（${e.message ?: e.javaClass.simpleName}）"
            }
            results += StockGovBank(s, cache.govBankDays(s.code, KEEP_DAYS), note, via(cache, s.code))
            emit(GovBankEvent.Progress(i + 2, total, s.label, cached = false))
        }

        emit(GovBankEvent.Done(GovBank(market, marketNote, results, lastFetched(cache, stocks), cached = !fetchedAny, warning = stopped)))
    }

    /** 抓 HiStock 的八大官股頁。 */
    internal suspend fun fetchMarket(): GovBankMarket {
        histockPacer.await()
        val r = http.get(histockUrl, mapOf("User-Agent" to USER_AGENT, "Accept" to "text/html"))
        if (r.code == 429) throw GovBankException("HiStock：請求太頻繁（HTTP 429），過一陣子再試")
        if (!r.ok) throw GovBankException("HiStock：HTTP ${r.code}")
        return parseHistock(r.body)
    }

    /**
     * 抓一檔（新到舊，兩邊都沒有資料回空的）：先 blave；blave 沒有這一檔（上櫃股都沒有）就抓 HiStock 的個股頁，
     * 有資料就記住這一檔走 HiStock（[viaKey]），之後直接抓 HiStock、不再白打 blave。
     */
    internal suspend fun fetchStock(code: String, t: Long): List<GovBankDay> {
        if (via(cache, code) == GovBankVia.BLAVE) {
            val days = fetchBlave(code, t)
            if (days.isNotEmpty()) return days
        }
        val days = fetchHistockStock(code)
        if (days.isNotEmpty()) cache.putMeta(viaKey(code), GovBankVia.HISTOCK.name)
        return days
    }

    /** blave：一檔最近 [RANGE_DAYS] 天（新到舊）。沒有資料回空的。 */
    internal suspend fun fetchBlave(code: String, t: Long): List<GovBankDay> {
        val end = Taipei.today { Instant.ofEpochMilli(t) }
        val start = end.minusDays(RANGE_DAYS.toLong())
        blavePacer.await()
        val url = "$blaveBase/studio/charts/twstock/gov_bank/${URLEncoder.encode(code, "UTF-8")}?start=$start&end=$end"
        val r = http.get(url, mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"))
        when (r.code) {
            404 -> return emptyList()
            400 -> throw GovBankException("blave：代號 $code 無效")
            429 -> throw GovBankException("blave：請求太頻繁（HTTP 429），過一陣子再試", stop = true)
            503 -> throw GovBankException("blave：資料來源的配額用完了（HTTP 503），晚點再試", stop = true)
        }
        if (!r.ok) throw GovBankException("blave：HTTP ${r.code}", stop = true)
        return parseBlave(r.body)
    }

    /** HiStock 的個股八大官股頁：一檔約 3 個月（新到舊）。HiStock 沒有這一檔回空的。 */
    internal suspend fun fetchHistockStock(code: String): List<GovBankDay> {
        histockPacer.await()
        val url = "$histockStockUrl?no=${URLEncoder.encode(code, "UTF-8")}"
        val r = try {
            http.get(url, mapOf("User-Agent" to USER_AGENT, "Accept" to "text/html"))
        } catch (e: IOException) {
            throw GovBankException("HiStock 沒回應，可能被限流，過一陣子再試（${e.message ?: e.javaClass.simpleName}）", stop = true)
        }
        when (r.code) {
            404 -> return emptyList()
            429 -> throw GovBankException("HiStock：請求太頻繁（HTTP 429），過一陣子再試", stop = true)
        }
        if (!r.ok) throw GovBankException("HiStock：HTTP ${r.code}", stop = true)
        return parseHistockStock(r.body, code)
    }

    companion object {
        /** 平日幾點以後才有當天的數字（blave 寫「今日資料約 18:30 更新」）。 */
        val PUBLISH_TIME: LocalTime = LocalTime.of(18, 30)

        /** 沒拿到該有的那天時，多久內不再問（還沒公布、假日、那天沒成交）。 */
        const val RETRY_MS = 10 * 60 * 1000L

        /** 一檔一發要幾天（日曆日，blave 一發最多 31 天）：約 14 個交易日。 */
        const val RANGE_DAYS = 20

        /** 畫面上一檔最多讀幾天。 */
        const val KEEP_DAYS = 10

        /** 快取裡一檔只留這麼多天（日曆日）。 */
        const val KEEP_CALENDAR_DAYS = 90L

        /** 全市場留幾天、排行留前幾名。 */
        const val MARKET_DAYS = 20
        const val TOP_N = 10

        const val META_MARKET = "govbank.market"
        const val META_MARKET_FETCHED = "govbank.market.fetchedAt"
        fun fetchedKey(code: String) = "govbank.fetchedAt.$code"

        /** 這一檔改走 HiStock 的記號（值是 [GovBankVia.HISTOCK] 的 name）；沒有就是 blave。 */
        fun viaKey(code: String) = "govbank.via.$code"

        fun via(cache: CacheStore, code: String): GovBankVia =
            if (cache.meta(viaKey(code)) == GovBankVia.HISTOCK.name) GovBankVia.HISTOCK else GovBankVia.BLAVE

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val SUM_MONEY = Regex("\"SumMoney\"\\s*:\\s*\"(\\[[^\"]*])\"")

        /** 個股頁的標題「弘塑 (3131) 八大官股買賣超 - HiStock…」裡的代號。 */
        private val STOCK_TITLE = Regex("<title>[^<]*?\\(([0-9A-Za-z]+)\\)\\s*八大官股買賣超")

        /**
         * 個股頁的 `initalChart_Broker8({…})`（HiStock 自己的拼法）；裡面的值都是字串，沒有大括號。
         * **大括號一定要跳脫**：Android 的 regex 是 ICU，沒跳脫的 `}` 會丟 PatternSyntaxException（JVM 接受，JVM 測試擋不下來，見 DeviceTest）。
         */
        private val STOCK_CHART = Regex("initalChart_Broker8\\((\\{[^\\}]*\\})\\)")
        private val RANK = Regex(
            "(\\d{4}-\\d{2}-\\d{2})\\s*八大公股行庫合計(買超|賣超)\\s*Top\\.(\\d+)\\s*:\\s*(.+?)\\s*\\(([0-9A-Za-z]+)\\)\\s*合計[：:]\\s*(-?[\\d,]+(?:\\.\\d+)?)\\s*萬元",
        )

        /** 「該公布的那天」：平日 [PUBLISH_TIME] 以後是今天，以前是前一個平日；週末是星期五。國定假日不知道，靠全市場的最新一天。 */
        fun publishedDate(now: Long): LocalDate {
            val local = Instant.ofEpochMilli(now).atZone(Taipei.ZONE)
            var d = local.toLocalDate()
            if (local.toLocalTime() < PUBLISH_TIME) d = d.minusDays(1)
            while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.minusDays(1)
            return d
        }

        /** 快取的最新一天到 [target] 了就是新的；沒到的話，[RETRY_MS] 內抓過也先不抓。日期都是 ISO 字串，直接比大小。 */
        internal fun fresh(latest: String?, fetchedAt: Long, target: String, now: Long): Boolean =
            (latest != null && latest >= target) || now - fetchedAt in 0L until RETRY_MS

        private fun failNote(message: String, hasOld: Boolean) = if (hasOld) "重抓失敗（$message），顯示上次的資料" else message

        /** 只讀快取、不連網：自選股分頁與官股頁一進來先顯示這個。不用建 [GovBankSource]（不會因此建立 OkHttpClient）。 */
        fun cached(cache: CacheStore, stocks: List<StockRef>): GovBank = GovBank(
            cachedMarket(cache),
            null,
            stocks.map { StockGovBank(it, cache.govBankDays(it.code, KEEP_DAYS), via = via(cache, it.code)) },
            lastFetched(cache, stocks),
            cached = true,
        )

        /** 每一檔快取裡最新的一天（沒抓過的不在裡面）。自選股分頁每列底下那一行用。 */
        fun latest(cache: CacheStore, codes: List<String>): Map<String, GovBankDay> =
            codes.mapNotNull { code -> cache.govBankDays(code, 1).firstOrNull()?.let { code to it } }.toMap()

        private fun cachedMarket(cache: CacheStore): GovBankMarket? =
            cache.meta(META_MARKET)?.let { runCatching { json.decodeFromString<GovBankMarket>(it) }.getOrNull() }

        private fun lastFetched(cache: CacheStore, stocks: List<StockRef>): String? {
            val ats = listOfNotNull(cache.meta(META_MARKET_FETCHED)?.toLongOrNull()) +
                stocks.mapNotNull { cache.meta(fetchedKey(it.code))?.toLongOrNull() }
            return ats.maxOrNull()?.let(Taipei::stamp)
        }

        /**
         * blave：`{"data":[{"bank_name":"兆豐","buy":716188,"buy_amount":1670269710.0,"date":"2026-11-02","sell":338301,"sell_amount":789166250.0,"stock_id":"2330"}, …], "history_tier":"anon", …}`。
         * 一列一家券商一天（那天沒成交的券商沒有列），同一天加起來；回新到舊。`data` 是空陣列就是沒有資料。
         * 不是這個格式（沒有 `data`、欄位不見、不是 JSON）一律 [GovBankException]（stop）。
         */
        fun parseBlave(body: String): List<GovBankDay> = parsing("blave") {
            val root = Json.parseToJsonElement(body).jsonObject
            val data = root["data"] as? JsonArray ?: throw GovBankException("blave：回應裡沒有 data，格式可能改了", stop = true)
            data.map { it.jsonObject }
                .groupBy { LocalDate.parse(it.text("date")).toString() }
                .map { (date, rows) ->
                    GovBankDay(
                        date = date,
                        buy = rows.sumOf { it.number("buy").roundToLong() },
                        sell = rows.sumOf { it.number("sell").roundToLong() },
                        buyAmount = rows.sumOf { it.number("buy_amount") },
                        sellAmount = rows.sumOf { it.number("sell_amount") },
                    )
                }
                .sortedByDescending { it.date }
        }

        /**
         * HiStock 的 `broker8.aspx`：`loadChart({"SumMoney":"[[1794182400000,102.562], …]","Price":"…"})`，時間是 UTC 的午夜（epoch 毫秒，換成台北是同一天早上 8 點）、
         * 金額是億元（圖例「買賣總金額(億元)」）；排行在 `<li title="2026-11-09 八大公股行庫合計買超 Top.1 : 台積電 (2330) 合計：213,162萬元">`。
         * 找不到 SumMoney 或是空的就當改版（[GovBankException]）；排行找不到只是空的。
         */
        fun parseHistock(html: String): GovBankMarket = parsing("HiStock") {
            val raw = SUM_MONEY.find(html)?.groupValues?.get(1)
                ?: throw GovBankException("HiStock：找不到八大官股的買賣總金額，頁面可能改版了")
            val days = Json.parseToJsonElement(raw).jsonArray
                .map { p ->
                    val a = p.jsonArray
                    val date = Instant.ofEpochMilli(a[0].jsonPrimitive.long).atZone(Taipei.ZONE).toLocalDate().toString()
                    MarketDay(date, a[1].jsonPrimitive.double * 1e8)
                }
                .sortedByDescending { it.date }
                .distinctBy { it.date }
                .take(MARKET_DAYS)
            if (days.isEmpty()) throw GovBankException("HiStock：買賣總金額是空的，頁面可能改版了")
            val ranks = Jsoup.parse(html).select("li[title]").mapNotNull { RANK.matchEntire(it.attr("title").replace(' ', ' ').trim()) }
            fun side(label: String) = ranks.filter { it.groupValues[2] == label }
                .map { m -> RankRow(m.groupValues[3].toInt(), m.groupValues[5], m.groupValues[4].trim(), m.groupValues[6].replace(",", "").toDouble() * 1e4) }
                .sortedBy { it.rank }
                .take(TOP_N)
            GovBankMarket(days, ranks.firstOrNull()?.groupValues?.get(1), side("買超"), side("賣超"))
        }

        /**
         * HiStock 的個股八大官股頁 `stock/broker.aspx?no=<代號>`（上市、上櫃都有，約 3 個月）：
         * `initalChart_Broker8({"Money1":"[[epoch 毫秒（UTC 的午夜，台北早上 8 點）, 萬元], …]", …, "SumCount":"[[…, 張]]","SumMoney":"[[…, 萬元]]","Price":"…"})`，舊到新。
         * Money1～8、Count1～8 是八家各自的，這裡只用合計（SumMoney、SumCount，HiStock 已經捨去成整數）。只有淨額（[GovBankDay.net]）。
         * **代號查不到時 HiStock 回 200 ＋ 台積電的頁面**：標題裡的代號不是 [code] 就是沒有這一檔（回空的）。
         * 找不到標題、圖的資料、欄位就當改版（[GovBankException]，不停下來，只記在這一檔）。
         */
        fun parseHistockStock(html: String, code: String): List<GovBankDay> {
            val shown = STOCK_TITLE.find(html)?.groupValues?.get(1)
                ?: throw GovBankException("HiStock：個股頁找不到標題，頁面可能改版了")
            if (!shown.equals(code, ignoreCase = true)) return emptyList()
            return parsing("HiStock", stop = false) {
                val raw = STOCK_CHART.find(html)?.groupValues?.get(1)
                    ?: throw GovBankException("HiStock：個股頁找不到八大官股的資料，頁面可能改版了")
                val chart = Json.parseToJsonElement(raw).jsonObject
                fun series(key: String): Map<Long, Double> {
                    val text = chart[key]?.jsonPrimitive?.content ?: throw GovBankException("HiStock：個股頁的「$key」不見了，頁面可能改版了")
                    return Json.parseToJsonElement(text).jsonArray.associate { p -> p.jsonArray.let { it[0].jsonPrimitive.long to it[1].jsonPrimitive.double } }
                }
                val lots = series("SumCount")
                series("SumMoney")
                    .map { (ms, wan) ->
                        val count = lots[ms] ?: throw GovBankException("HiStock：個股頁的張數與金額對不起來，頁面可能改版了")
                        val date = Instant.ofEpochMilli(ms).atZone(Taipei.ZONE).toLocalDate().toString()
                        GovBankDay.net(date, netShares = (count * 1000).roundToLong(), netAmount = wan * 1e4)
                    }
                    .sortedByDescending { it.date }
                    .distinctBy { it.date }
            }
        }

        private fun JsonObject.text(key: String): String =
            this[key]?.jsonPrimitive?.content ?: throw GovBankException("blave：欄位「$key」不見了，格式可能改了", stop = true)

        private fun JsonObject.number(key: String): Double =
            this[key]?.jsonPrimitive?.doubleOrNull ?: throw GovBankException("blave：欄位「$key」不見了，格式可能改了", stop = true)

        /**
         * 回應不是 JSON（被擋時回的 HTML）、日期或數字的格式不對：轉成 [GovBankException]，抓取流程才會回報、不會變成沒接住的例外。
         * [stop] 是要不要停下來不抓後面的檔（blave 是；HiStock 的個股頁只記在那一檔）。
         */
        private inline fun <T> parsing(source: String, stop: Boolean = true, block: () -> T): T = try {
            block()
        } catch (e: IllegalArgumentException) { // kotlinx.serialization 的 SerializationException、NumberFormatException 都是這一種
            throw GovBankException("$source：回應不是預期的格式，可能改版了", stop = stop)
        } catch (e: DateTimeParseException) {
            throw GovBankException("$source：日期的格式不對，可能改版了", stop = stop)
        } catch (e: IndexOutOfBoundsException) {
            throw GovBankException("$source：欄位數不對，格式可能改了", stop = stop)
        }
    }
}
