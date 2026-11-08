package com.twtrubiks.stockdesk.core.indices

import com.twtrubiks.stockdesk.core.http.Http
import com.twtrubiks.stockdesk.core.http.Pacer
import com.twtrubiks.stockdesk.core.http.Taipei
import com.twtrubiks.stockdesk.core.http.USER_AGENT
import com.twtrubiks.stockdesk.core.store.CacheStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class IndicesException(message: String) : Exception(message)

/** 要看的指數：Yahoo 的代號與畫面上的名稱。 */
data class IndexSpec(val symbol: String, val name: String)

/**
 * 一個指數最近一個交易日的走勢。時間都是 epoch 秒。
 * [prevClose] 是昨收（走勢圖的虛線、漲跌的基準）；[sessionStart]～[sessionEnd] 是這個交易日的盤（走勢圖的 x 軸，盤中時線只畫到一半）。
 */
@Serializable
data class IndexDay(
    val symbol: String,
    val name: String,
    /** 交易所的時區（`America/New_York`），用來算這是哪一天的盤。 */
    val zone: String,
    val price: Double,
    val prevClose: Double,
    val sessionStart: Long,
    val sessionEnd: Long,
    /** 最新一筆的時間（Yahoo 的 `regularMarketTime`）。 */
    val marketTime: Long,
    val times: List<Long>,
    val values: List<Double>,
) {
    val change: Double get() = price - prevClose
    val changePct: Double get() = if (prevClose == 0.0) 0.0 else change / prevClose * 100

    /** 這是交易所當地哪一天的盤（美股 11/06 的盤，台北時間是 11/06 晚上到 11/07 清晨）。 */
    val sessionDate: LocalDate get() = Instant.ofEpochSecond(sessionStart).atZone(ZoneId.of(zone)).toLocalDate()

    /** 收盤了沒：最新一筆已經到收盤時間（容許 5 分鐘）。抓的時候還在盤中、之後沒重抓，就一直是盤中的樣子。 */
    val closed: Boolean get() = marketTime >= sessionEnd - CLOSE_TOLERANCE_S
}

/** 最新一筆離收盤時間在這之內就當作收盤了（秒）。不放在 [IndexDay] 的 companion：@Serializable 的 companion 不能是 private。 */
private const val CLOSE_TOLERANCE_S = 300L

/** 國際指數頁要顯示的：每個指數最近一個交易日、抓取時間、是不是從快取來的、重抓失敗時的提示。 */
data class Indices(
    val days: List<IndexDay>,
    val fetchedAt: String,
    val cached: Boolean,
    val warning: String? = null,
)

/**
 * 各國股市指數最近一個交易日的走勢，來源是 Yahoo Finance 的 `v7/finance/spark`（tw.stock.yahoo.com 國際指數頁用的同一份資料）：
 *
 * - **不是官方來源，也沒有公開文件**：不用金鑰，但哪天改格式、限流（HTTP 429）或要 cookie 都有可能；壞了就顯示錯誤，
 *   畫面上有「在瀏覽器開 Yahoo 國際指數」當備案。美、日、韓、港的指數沒有免費的官方分時資料
 * - 一發拿全部指數（`symbols=^TWII,^DJI,…&range=1d&interval=5m`，約 21 KB）：每個指數「最近一個交易日」的 5 分鐘收盤價、
 *   昨收（`chartPreviousClose`，跟 Yahoo 網頁上的漲跌一樣）、這個交易日的盤（`tradingPeriods`）。
 *   不用 `range=5d` 自己切：5 分鐘線最後一根不一定等於收盤價（韓國有收盤競價），算出來的漲跌會跟網頁對不上
 * - 一定要帶瀏覽器的 User-Agent：沒有 User-Agent 會回 429
 * - 沒有自動更新：進頁面時上次抓取超過 [FRESH_MS] 才抓，其他時候按重新整理（[load] 的 force）
 * - 某個指數這次沒有走勢（例如開盤前、Yahoo 漏回）就沿用快取裡那個指數上一次的資料；全部都沒有才算失敗。
 *   重抓失敗但快取裡有舊資料時照樣顯示舊的，附 warning
 *
 * `url` 可以換（測試接 MockWebServer）。
 */
class IndicesSource(
    private val http: Http,
    private val cache: CacheStore,
    private val url: String = "https://query1.finance.yahoo.com/v7/finance/spark",
    private val pacer: Pacer = Pacer(Pacer.YAHOO_MS),
    private val now: () -> Long = System::currentTimeMillis,
    val specs: List<IndexSpec> = DEFAULT,
) {
    /** [force] 是按了重新整理；否則上次抓取在 [FRESH_MS] 內就讀快取。 */
    suspend fun load(force: Boolean = false): Indices {
        val cached = cachedDays()
        val fetchedAt = cache.meta(META_FETCHED)?.toLongOrNull()
        if (!force && cached.isNotEmpty() && fetchedAt != null && now() - fetchedAt in 0L until FRESH_MS) {
            return Indices(cached, Taipei.stamp(fetchedAt), cached = true)
        }

        return try {
            pacer.await()
            val r = http.get(requestUrl(), mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"))
            if (r.code == 429) throw IndicesException("Yahoo：請求太頻繁（HTTP 429），過一陣子再試")
            if (!r.ok) throw IndicesException("Yahoo：HTTP ${r.code}")
            val fresh = parse(r.body, specs).associateBy { it.symbol }
            if (fresh.isEmpty()) throw IndicesException("Yahoo：回應裡沒有任何指數的走勢，格式可能改了")
            // 這次沒回走勢的指數沿用上次的
            val old = cached.associateBy { it.symbol }
            val days = specs.mapNotNull { fresh[it.symbol] ?: old[it.symbol] }
            val stamp = now()
            cache.putMeta(META_DAYS, json.encodeToString(days))
            cache.putMeta(META_FETCHED, stamp.toString())
            Indices(days, Taipei.stamp(stamp), cached = false)
        } catch (e: Exception) {
            if (e !is IndicesException && e !is IOException) throw e
            if (cached.isNotEmpty() && fetchedAt != null) {
                Indices(cached, Taipei.stamp(fetchedAt), cached = true, warning = "重抓失敗（${e.message}），顯示上次的資料")
            } else {
                throw IndicesException(e.message ?: "抓取失敗")
            }
        }
    }

    private fun cachedDays(): List<IndexDay> =
        cache.meta(META_DAYS)?.let { runCatching { json.decodeFromString<List<IndexDay>>(it) }.getOrNull() }.orEmpty()

    private fun requestUrl(): String {
        val symbols = URLEncoder.encode(specs.joinToString(",") { it.symbol }, "UTF-8")
        return "$url?symbols=$symbols&range=1d&interval=5m"
    }

    companion object {
        /** 畫面上的順序：台股、美股四個、日韓港。 */
        val DEFAULT = listOf(
            IndexSpec("^TWII", "台股加權"),
            IndexSpec("^DJI", "道瓊工業"),
            IndexSpec("^GSPC", "S&P 500"),
            IndexSpec("^IXIC", "NASDAQ"),
            IndexSpec("^SOX", "費城半導體"),
            IndexSpec("^N225", "日經 225"),
            IndexSpec("^KS11", "韓國 KOSPI"),
            IndexSpec("^HSI", "香港恆生"),
        )

        /** 進頁面時，上次抓取在這之內就不重抓（毫秒）。 */
        const val FRESH_MS = 5 * 60 * 1000L

        const val META_DAYS = "indices.days"
        const val META_FETCHED = "indices.fetchedAt"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * `{"spark":{"result":[{"symbol":"^DJI","response":[{"meta":{…},"timestamp":[…],"indicators":{"quote":[{"close":[…]}]}}]}],"error":null}}`。
         * 照 [specs] 的順序回有走勢的那幾個；某個指數沒有 `response`、沒有點、缺昨收或時區就略過（由呼叫的人決定要不要沿用舊的）。
         * close 是 null 的點（那 5 分鐘沒有成交）跳過。整份不是這個格式才丟例外。
         */
        fun parse(body: String, specs: List<IndexSpec> = DEFAULT): List<IndexDay> {
            val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw IndicesException("Yahoo：回應不是 JSON，格式可能改了")
            val results = (root["spark"] as? JsonObject)?.get("result") as? JsonArray
                ?: throw IndicesException("Yahoo：回應裡沒有 spark.result，格式可能改了")
            val bySymbol = results.mapNotNull { it as? JsonObject }.associateBy { it.str("symbol") }
            return specs.mapNotNull { spec -> bySymbol[spec.symbol]?.let { day(spec, it) } }
        }

        private fun day(spec: IndexSpec, result: JsonObject): IndexDay? {
            val response = (result["response"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            val meta = response["meta"] as? JsonObject ?: return null
            val stamps = (response["timestamp"] as? JsonArray)?.map { it.long() }.orEmpty()
            val quote = ((response["indicators"] as? JsonObject)?.get("quote") as? JsonArray)?.firstOrNull() as? JsonObject
            val closes = (quote?.get("close") as? JsonArray)?.map { it.double() }.orEmpty()
            val points = stamps.zip(closes).mapNotNull { (t, v) -> if (t != null && v != null) t to v else null }
            if (points.isEmpty()) return null

            val zone = meta.str("exchangeTimezoneName") ?: return null
            val prevClose = meta.num("chartPreviousClose") ?: meta.num("previousClose") ?: return null
            // 這一天的盤：tradingPeriods 第一段的開始到最後一段的結束；沒有就用 currentTradingPeriod.regular，再沒有就用頭尾兩點
            val periods = (meta["tradingPeriods"] as? JsonArray)?.flatMap { (it as? JsonArray).orEmpty() }?.mapNotNull { it as? JsonObject }.orEmpty()
            val regular = (meta["currentTradingPeriod"] as? JsonObject)?.get("regular") as? JsonObject
            val start = periods.firstOrNull()?.lng("start") ?: regular?.lng("start") ?: points.first().first
            val end = periods.lastOrNull()?.lng("end") ?: regular?.lng("end") ?: points.last().first
            return IndexDay(
                symbol = spec.symbol,
                name = spec.name,
                zone = zone,
                price = meta.num("regularMarketPrice") ?: points.last().second,
                prevClose = prevClose,
                sessionStart = minOf(start, points.first().first),
                sessionEnd = maxOf(end, points.last().first),
                marketTime = meta.lng("regularMarketTime") ?: points.last().first,
                times = points.map { it.first },
                values = points.map { it.second },
            )
        }

        private fun JsonObject.str(key: String): String? = this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        private fun JsonObject.num(key: String): Double? = this[key]?.double()
        private fun JsonObject.lng(key: String): Long? = this[key]?.long()
        private fun JsonElement.double(): Double? = if (this is JsonNull) null else runCatching { jsonPrimitive.doubleOrNull }.getOrNull()
        private fun JsonElement.long(): Long? = if (this is JsonNull) null else runCatching { jsonPrimitive.longOrNull }.getOrNull()
    }
}
