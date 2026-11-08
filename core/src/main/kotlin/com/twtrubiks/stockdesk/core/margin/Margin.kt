package com.twtrubiks.stockdesk.core.margin

import com.twtrubiks.stockdesk.core.http.Http
import com.twtrubiks.stockdesk.core.http.Pacer
import com.twtrubiks.stockdesk.core.http.Taipei
import com.twtrubiks.stockdesk.core.http.USER_AGENT
import com.twtrubiks.stockdesk.core.model.Market
import com.twtrubiks.stockdesk.core.store.CacheStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.time.LocalDate

/** 一檔某一天的融資融券。單位：張。`finUse` 資使用率 %、`ratio` 券資比 %，可能算不出來（餘額 0）。 */
@Serializable
data class MarginDay(
    val date: String,
    val finBuy: Long,
    val finSell: Long,
    val finRedeem: Long,
    val finPrev: Long,
    val finBal: Long,
    val finLimit: Long,
    val finUse: Double?,
    val shortSell: Long,
    val shortBuy: Long,
    val shortRedeem: Long,
    val shortPrev: Long,
    val shortBal: Long,
    val shortLimit: Long,
    val offset: Long,
) {
    /** 增減 = 今日餘額 − 前日餘額。 */
    val finChg: Long get() = finBal - finPrev
    val shortChg: Long get() = shortBal - shortPrev

    /** 券資比 = 券餘額 ÷ 資餘額 × 100。 */
    val ratio: Double? get() = if (finBal == 0L) null else shortBal * 100.0 / finBal
}

class MarginException(message: String) : Exception(message)

/** 抓的過程中一步一步回報，畫面顯示進度；最後一個是 [Done]。 */
sealed interface MarginEvent {
    data class Progress(val done: Int, val total: Int, val date: LocalDate, val cached: Boolean) : MarginEvent
    data class Done(val market: Market, val rows: List<MarginDay>) : MarginEvent
    data class Failed(val message: String, val partial: List<MarginDay>) : MarginEvent
}

/**
 * 個股融資融券（每日餘額），抓官方原始資料：
 *
 * - 上市：證交所 `MI_MARGN?date=YYYYMMDD&selectType=ALL&response=json`
 * - 上櫃：櫃買 `margin/balance?date=YYYY/MM/DD&response=json`
 *
 * 兩個端點都是「一次回一天、全市場」，所以 N 天就從今天往回一天一天抓；每一天只存自選股那幾列（[CacheStore]）。
 * 休市：證交所 stat 是「很抱歉，沒有符合條件的資料」、櫃買 data 是空陣列。過去的日期永久記成休市；
 * 今天沒資料可能只是還沒公布，只在記憶體記 [TODAY_EMPTY_TTL]。
 * 市場別事先不知道：第一個有開市的日子先看證交所，沒有這檔再看櫃買；某一邊沒回應就跳過它看另一邊。
 * 衍生欄位：上市的資使用率端點沒給，用 融資餘額 ÷ 次一營業日限額 推算（櫃買直接給，實測同一個算法）。
 *
 * `twseBase`、`tpexBase` 可以換（測試接 MockWebServer）。
 */
class MarginSource(
    private val http: Http,
    private val cache: CacheStore,
    private val twsePacer: Pacer = Pacer(Pacer.TWSE_MS),
    private val tpexPacer: Pacer = Pacer(Pacer.TPEX_MS),
    private val twseBase: String = "https://www.twse.com.tw",
    private val tpexBase: String = "https://www.tpex.org.tw",
    private val today: () -> LocalDate = { Taipei.today() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** 「今天還沒公布」的記憶：market → 記下來的時間。 */
    private val todayEmptyAt = mutableMapOf<Market, Long>()

    /**
     * 近 [days] 個交易日（新到舊）。[watch] 是目前自選股的代號：抓到一天就把這些檔的列都存進快取（同市場共用）。
     * 最多往回看 [MAX_LOOKBACK_DAYS] 個日曆日，免得連假、長期休市時一直往回打。
     */
    fun history(code: String, days: Int, watch: Set<String>): Flow<MarginEvent> = flow {
        val wanted = days.coerceIn(1, MAX_DAYS)
        val rows = mutableListOf<MarginDay>()
        var market: Market? = cache.market(code)
        var date = today()
        var looked = 0
        try {
            while (rows.size < wanted && looked < MAX_LOOKBACK_DAYS) {
                looked++
                val lookup = dayFor(code, date, market, watch + code)
                market = lookup.market ?: market
                if (lookup.row != null) {
                    rows += lookup.row
                    emit(MarginEvent.Progress(rows.size, wanted, date, lookup.cached))
                } else if (lookup.missing) {
                    // 有開市、但這一檔沒有列（停止信用交易、下市）：不再往回一天一天打
                    if (rows.isEmpty()) throw MarginException("${source(market!!)}最近一個交易日沒有 $code 的融資融券資料（可能停止信用交易或下市）")
                    break
                }
                // 其他（休市、今天還沒公布、一邊沒回應）：往前一天繼續找
                date = date.minusDays(1)
            }
            val m = market ?: throw MarginException("兩邊都找不到 $code：不是上市櫃股票，或最近都沒有資料")
            emit(MarginEvent.Done(m, rows))
        } catch (e: MarginException) {
            emit(MarginEvent.Failed(e.message ?: "抓取失敗", rows))
        } catch (e: IOException) {
            emit(MarginEvent.Failed("沒回應，可能被限流，過一陣子再試（${e.message ?: e.javaClass.simpleName}）", rows))
        }
    }

    /** [missing]：市場已知、那天有開市，但沒有這一檔的列。 */
    private data class DayLookup(val row: MarginDay?, val cached: Boolean, val market: Market?, val missing: Boolean = false)

    /**
     * 某檔某一天：先看快取，沒有才打端點。市場不確定時兩邊都試。
     * 「都沒有這一檔」只在兩邊**都有開市**、都沒這檔時才下結論：一邊休市、今天還沒公布或沒回應，就往前一天再看。
     */
    private suspend fun dayFor(code: String, date: LocalDate, known: Market?, watch: Set<String>): DayLookup {
        val candidates = if (known != null) listOf(known) else Market.entries
        var openWithout = 0 // 有開市、但沒有這一檔的市場數
        val failures = mutableListOf<IOException>()
        for (market in candidates) {
            val state = cache.marginDayState(market, date)
            if (state == true) continue // 休市
            if (state == false) {
                val row = cache.marginRow(market, date, code)
                if (row != null) {
                    if (known == null) cache.putMarket(code, market)
                    return DayLookup(row, true, market)
                }
                // 這一天抓過但沒這檔：可能是後來才加的自選股，重抓這一天
            }
            val todayEmpty = todayEmptyAt[market]
            if (date == today() && todayEmpty != null && now() - todayEmpty < TODAY_EMPTY_TTL) continue
            val day = try {
                fetchDay(market, date)
            } catch (e: IOException) {
                failures += e
                continue
            }
            if (day == null) {
                if (date == today()) todayEmptyAt[market] = now() else cache.putMarginDay(market, date, closed = true, rows = emptyMap())
                continue
            }
            cache.putMarginDay(market, date, closed = false, rows = day.filterKeys { it in watch })
            val row = day[code]
            if (row != null) {
                if (known == null) cache.putMarket(code, market)
                return DayLookup(row, false, market)
            }
            openWithout++
        }
        if (failures.size == candidates.size) throw failures.first()
        if (openWithout == candidates.size) {
            if (known == null) throw MarginException("證交所與櫃買都沒有 $code 這一檔")
            return DayLookup(null, false, null, missing = true)
        }
        // 一邊有開市沒這檔、另一邊沒回應：不能斷定沒有這一檔
        if (failures.isNotEmpty() && openWithout > 0) throw failures.first()
        return DayLookup(null, false, null)
    }

    /** 抓一天的全市場。回 null 表示休市或還沒公布。 */
    internal suspend fun fetchDay(market: Market, date: LocalDate): Map<String, MarginDay>? = when (market) {
        Market.TWSE -> {
            twsePacer.await()
            val url = "$twseBase/rwd/zh/marginTrading/MI_MARGN?date=${Taipei.ymd(date)}&selectType=ALL&response=json"
            val r = http.get(url, headers)
            if (!r.ok) throw MarginException("證交所：HTTP ${r.code}")
            parseTwse(r.body, date)
        }
        Market.TPEX -> {
            tpexPacer.await()
            val url = "$tpexBase/www/zh-tw/margin/balance?date=${Taipei.slash(date)}&response=json"
            val r = http.get(url, headers)
            if (!r.ok) throw MarginException("櫃買：HTTP ${r.code}")
            parseTpex(r.body, date)
        }
    }

    private fun source(market: Market) = when (market) {
        Market.TWSE -> "證交所"
        Market.TPEX -> "櫃買"
    }

    companion object {
        const val MAX_DAYS = 60
        const val MAX_LOOKBACK_DAYS = 120
        const val TODAY_EMPTY_TTL = 10 * 60 * 1000L
        private val headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json")
        private const val NO_DATA_TWSE = "沒有符合條件的資料"

        /**
         * 證交所 MI_MARGN。`stat` 不是 OK 就是休市；`tables` 第一張是「信用交易統計」，個股那張的第一欄是「代號」
         * （2026-11-08 對過 2026-11-06 的真實回應；舊版 API 叫「股票代號」，兩種都認）。
         * 欄位：代號、名稱、融資（買進、賣出、現金償還、前日餘額、今日餘額、次一營業日限額）、
         * 融券（**買進、賣出**、現券償還、前日餘額、今日餘額、次一營業日限額）、資券互抵、註記。
         * 融資、融券的欄名重複：照位置分成兩組（第 3～8 欄、第 9～14 欄），組內照欄名找，順序換了也讀得對。
         */
        fun parseTwse(body: String, date: LocalDate): Map<String, MarginDay>? = parsing("證交所") {
            val root = Json.parseToJsonElement(body).jsonObject
            val stat = root["stat"]?.jsonPrimitive?.content ?: ""
            if (NO_DATA_TWSE in stat) return@parsing null
            if (stat != "OK") throw MarginException("證交所：stat=$stat")
            val table = root["tables"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { t -> t["fields"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content?.trim()?.endsWith("代號") == true }
                ?: throw MarginException("證交所：找不到個股的表，格式可能改了")
            val fields = table.getValue("fields").jsonArray.map { it.jsonPrimitive.content.trim() }
            val fin = Columns("證交所", fields, 2..7)
            val short = Columns("證交所", fields, 8..13)
            val finBuy = fin["買進"]; val finSell = fin["賣出"]; val finRedeem = fin["償還"]; val finPrev = fin["前日餘額"]; val finBal = fin["今日餘額"]; val finLimit = fin["限額"]
            val shortBuy = short["買進"]; val shortSell = short["賣出"]; val shortRedeem = short["償還"]; val shortPrev = short["前日餘額"]; val shortBal = short["今日餘額"]; val shortLimit = short["限額"]
            val offset = fields.indexOfFirst { "互抵" in it }
            val data = table["data"]?.jsonArray ?: throw MarginException("證交所：個股的表沒有 data，格式可能改了")
            val rows = data.associate { raw ->
                val c = raw.jsonArray.map { it.jsonPrimitive.content }
                c[0].trim() to MarginDay(
                    date = date.toString(),
                    finBuy = num(c[finBuy]), finSell = num(c[finSell]), finRedeem = num(c[finRedeem]), finPrev = num(c[finPrev]), finBal = num(c[finBal]),
                    finLimit = num(c[finLimit]), finUse = ratio(num(c[finBal]), num(c[finLimit])),
                    shortSell = num(c[shortSell]), shortBuy = num(c[shortBuy]), shortRedeem = num(c[shortRedeem]), shortPrev = num(c[shortPrev]),
                    shortBal = num(c[shortBal]), shortLimit = num(c[shortLimit]),
                    offset = if (offset >= 0) num(c[offset]) else 0,
                )
            }
            checkBalances("證交所", fields, rows)
        }

        /**
         * 櫃買 margin/balance。`stat` 是 ok；`tables[0].data` 空陣列是休市。
         * 欄位順序（實測筆記，**還沒對過真實回應**）：代號、名稱、前資餘額、資買、資賣、現償、資餘額、資屬證金、資使用率、資限額、
         * 前券餘額、券賣、券買、券償、券餘額、券屬證金、券使用率、券限額、資券相抵、備註。
         * 順序錯了 [checkBalances] 會擋下來（餘額對不起來），錯誤訊息裡有實際的欄名。
         */
        fun parseTpex(body: String, date: LocalDate): Map<String, MarginDay>? = parsing("櫃買") {
            val root = Json.parseToJsonElement(body).jsonObject
            val stat = root["stat"]?.jsonPrimitive?.content ?: ""
            if (!stat.equals("ok", ignoreCase = true)) throw MarginException("櫃買：stat=$stat")
            val table = root["tables"]?.jsonArray?.firstOrNull()?.jsonObject ?: throw MarginException("櫃買：沒有 tables")
            val data = table["data"]?.jsonArray ?: return@parsing null
            if (data.isEmpty()) return@parsing null
            val fields = table["fields"]?.jsonArray?.map { it.jsonPrimitive.content.trim() }.orEmpty()
            val rows = data.associate { raw ->
                val c = raw.jsonArray.map { it.jsonPrimitive.content }
                c[0].trim() to MarginDay(
                    date = date.toString(),
                    finPrev = num(c[2]), finBuy = num(c[3]), finSell = num(c[4]), finRedeem = num(c[5]), finBal = num(c[6]),
                    finUse = c[8].replace("%", "").trim().toDoubleOrNull(), finLimit = num(c[9]),
                    shortPrev = num(c[10]), shortSell = num(c[11]), shortBuy = num(c[12]), shortRedeem = num(c[13]), shortBal = num(c[14]),
                    shortLimit = num(c[17]), offset = num(c[18]),
                )
            }
            checkBalances("櫃買", fields, rows)
        }

        /** 融資或融券那一組（欄名重複，只在 [range] 裡找）。 */
        private class Columns(private val source: String, private val fields: List<String>, private val range: IntRange) {
            operator fun get(name: String): Int = range.firstOrNull { it < fields.size && name in fields[it] }
                ?: throw MarginException("$source：第 ${range.first + 1}～${range.last + 1} 欄裡找不到「$name」，格式可能改了。欄位：${fields.joinToString("、")}")
        }

        /**
         * 用資料本身驗欄位有沒有讀對：融資 今日餘額 ＝ 前日餘額 ＋ 買進 − 賣出 − 現金償還；融券 今日餘額 ＝ 前日餘額 ＋ 賣出 − 買進 − 現券償還。
         * 欄位順序錯了（例如融券的買進、賣出對調）大部分列都會對不起來；容許 1/5 的列例外（除權息之類的調整）。
         */
        private fun checkBalances(source: String, fields: List<String>, rows: Map<String, MarginDay>): Map<String, MarginDay> {
            val bad = rows.values.count { r ->
                r.finPrev + r.finBuy - r.finSell - r.finRedeem != r.finBal || r.shortPrev + r.shortSell - r.shortBuy - r.shortRedeem != r.shortBal
            }
            if (bad > rows.size / 5) {
                throw MarginException("$source：$bad/${rows.size} 檔的餘額對不起來，欄位順序可能改了。欄位：${fields.joinToString("、").ifEmpty { "（沒有）" }}")
            }
            return rows
        }

        /**
         * 回應不是 JSON（例如被擋時回的 HTML 頁）、欄位數不夠：轉成 [MarginException]，抓取流程才會回報「失敗」，
         * 不會變成沒接住的例外。
         */
        private inline fun <T> parsing(source: String, block: () -> T): T = try {
            block()
        } catch (e: IllegalArgumentException) { // kotlinx.serialization 的 SerializationException 也是這一種
            throw MarginException("$source：回應不是預期的格式，可能改版了")
        } catch (e: IndexOutOfBoundsException) {
            throw MarginException("$source：欄位數不對，格式可能改了")
        }

        private fun num(s: String): Long = s.replace(",", "").trim().toLongOrNull() ?: 0L

        private fun ratio(a: Long, b: Long): Double? = if (b == 0L) null else a * 100.0 / b
    }
}
