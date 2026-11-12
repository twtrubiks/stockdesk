package com.twtrubiks.stockdesk.core.holders

import com.twtrubiks.stockdesk.core.http.Http
import com.twtrubiks.stockdesk.core.http.Pacer
import com.twtrubiks.stockdesk.core.http.Taipei
import com.twtrubiks.stockdesk.core.http.USER_AGENT
import com.twtrubiks.stockdesk.core.links.Links
import com.twtrubiks.stockdesk.core.store.CacheStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import java.io.IOException
import java.time.LocalDate

/** 一個持股分級（集保的 15 級之一）。單位：股。 */
@Serializable
data class HolderLevel(val level: Int, val label: String, val holders: Int, val shares: Long, val pct: Double)

/** 合併後的級距（張）：≤10、10–50、50–100、100–400、400–1000、>1000。 */
@Serializable
data class HolderGroup(val label: String, val holders: Int, val shares: Long, val pct: Double)

/** 一檔某一週的股權分散。`levels` 是原始 15 級，`groups` 是合併後的 6 組（最後一組是「>1000 張」，也就是大戶）。 */
@Serializable
data class HolderWeek(
    val date: String,
    val holders: Int,
    val shares: Long,
    val levels: List<HolderLevel>,
    val groups: List<HolderGroup>,
) {
    val bigPct: Double get() = groups.last().pct
}

open class HoldersException(message: String) : Exception(message)

/** 回應裡沒有分級表、也不是「查無此資料」：token 或 session 失效（或改版）。抓的時候會從查詢頁重來一次。 */
class HoldersExpiredException(message: String) : HoldersException(message)

sealed interface HoldersEvent {
    data class Progress(val done: Int, val total: Int, val date: LocalDate, val cached: Boolean) : HoldersEvent
    data class Done(val weeks: List<HolderWeek>) : HoldersEvent
    /** [stop]：集保沒回應（可能被限流），不是這一檔的問題；「全部抓取」看到就不再打集保。 */
    data class Failed(val message: String, val partial: List<HolderWeek>, val stop: Boolean = false) : HoldersEvent
}

/**
 * 股東持股分級（集保戶股權分散表），抓集保結算所的查詢頁：
 *
 * 1. GET 查詢頁拿表單：`SYNCHRONIZER_TOKEN`、`firDate`（隱藏欄位，最新的一週）與可查的週（`<select name="scaDate">`，約 52 週）
 * 2. POST 查「某檔、某週」，回 HTML，表格是 15 個持股分級 ＋ 合計
 *
 * 要記住的事：
 * - token 存在伺服器的 session 裡：**要帶 GET 查詢頁拿到的 cookie**（`JSESSIONID`，[com.twtrubiks.stockdesk.core.http.MemoryCookies]），
 *   沒帶 cookie 的 POST 一律當成 token 失效（手機上第一版就是壞在這裡）
 * - 一次只回「一檔、一週」，N 週就是 N 次 POST
 * - `SYNCHRONIZER_TOKEN` **每次都會換新**：回應頁裡帶下一發要用的 token，舊的再用就回空表，所以請求必須照順序串、不能平行
 * - 代號不存在回「查無此資料」；有些股票多一列「差異數調整」，不屬於任何分級，略過
 * - 週資料公布後不會再變，所以以 (代號, 週) 存進 [CacheStore]，抓過的週不再打
 * - 查詢頁（可查的週）一天拿一次（台北日期）：App 在背景放到下一週，回來才看得到新公布的那週。
 *   回空表（token 或 session 過期，session 閒置一陣子就會失效）就重拿查詢頁、同一週再試一次；
 *   還是不行、或沒回應，就丟掉手上的 token，下次從查詢頁重來
 *
 * `base` 可以換（測試接 MockWebServer）。
 */
class HoldersSource(
    private val http: Http,
    private val cache: CacheStore,
    private val pacer: Pacer = Pacer(Pacer.TDCC_MS),
    private val base: String = Links.TDCC_QUERY,
    private val today: () -> LocalDate = { Taipei.today() },
) {
    private var token: String? = null
    private var dates: List<String> = emptyList() // yyyyMMdd，新到舊
    private var firstDate: String? = null
    private var formDay: LocalDate? = null

    /** 近 [weeks] 週（新到舊）。 */
    fun history(code: String, weeks: Int): Flow<HoldersEvent> = flow {
        val wanted = weeks.coerceIn(1, MAX_WEEKS)
        val out = mutableListOf<HolderWeek>()
        try {
            ensureForm()
            for (ymd in dates.take(wanted)) {
                val date = LocalDate.of(ymd.substring(0, 4).toInt(), ymd.substring(4, 6).toInt(), ymd.substring(6, 8).toInt())
                val cached = cache.holdersWeek(code, date)
                val week = cached ?: fetchWeek(code, ymd)?.also { cache.putHoldersWeek(code, it) }
                if (week == null) {
                    if (out.isEmpty()) throw HoldersException("集保查無 $code 的資料")
                    break
                }
                out += week
                emit(HoldersEvent.Progress(out.size, wanted, date, cached != null))
            }
            emit(HoldersEvent.Done(out))
        } catch (e: HoldersException) {
            token = null
            emit(HoldersEvent.Failed(e.message ?: "抓取失敗", out))
        } catch (e: IOException) {
            token = null
            emit(HoldersEvent.Failed("集保沒回應，可能被限流，過一陣子再試", out, stop = true))
        }
    }

    private suspend fun ensureForm() {
        if (dates.isNotEmpty() && formDay == today()) return
        loadForm()
    }

    /** GET 查詢頁：token、firDate、可查的週，順便拿到 session cookie。 */
    private suspend fun loadForm() {
        token = null
        pacer.await()
        val r = http.get(base, headers)
        if (!r.ok) throw HoldersException("集保：HTTP ${r.code}")
        val found = parseDates(r.body)
        if (found.isEmpty()) throw HoldersException("集保：讀不到可查的週，頁面可能改版了")
        token = parseToken(r.body) ?: throw HoldersException("集保：讀不到 SYNCHRONIZER_TOKEN，頁面可能改版了")
        dates = found
        firstDate = parseFirstDate(r.body) ?: found.first()
        formDay = today()
    }

    /** 回 null 表示「查無此資料」。token 失效就重拿查詢頁、同一週再試一次。 */
    private suspend fun fetchWeek(code: String, ymd: String): HolderWeek? {
        var attempt = 0
        while (true) {
            if (token == null) loadForm()
            pacer.await()
            val form = mapOf(
                "SYNCHRONIZER_TOKEN" to (token ?: ""),
                "SYNCHRONIZER_URI" to "/portal/zh/smWeb/qryStock",
                "method" to "submit",
                "firDate" to (firstDate ?: ""),
                "scaDate" to ymd,
                "sqlMethod" to "StockNo",
                "stockNo" to code,
                "stockName" to "",
            )
            val r = http.postForm(base, form, postHeaders)
            if (!r.ok) throw HoldersException("集保：HTTP ${r.code}")
            // 下一發用回應裡的新 token；回應裡沒有就下一發前重拿查詢頁
            token = parseToken(r.body)
            try {
                return parseWeek(r.body, ymd)
            } catch (e: HoldersExpiredException) {
                token = null
                if (++attempt >= 2) throw e
            }
        }
    }

    private val postHeaders = headers + mapOf("Referer" to base, "Origin" to origin(base))

    companion object {
        const val MAX_WEEKS = 52
        private val headers = mapOf("User-Agent" to USER_AGENT)

        /** 15 級怎麼合併成 6 組（張）：級距編號 1 起算，1 張 = 1,000 股。 */
        val GROUPS: List<Pair<String, IntRange>> = listOf(
            "≤10 張" to 1..3,
            "10–50 張" to 4..8,
            "50–100 張" to 9..9,
            "100–400 張" to 10..11,
            "400–1000 張" to 12..14,
            ">1000 張" to 15..15,
        )

        /** 頁面裡 `<input name="SYNCHRONIZER_TOKEN" value="…">` 的值（屬性順序不拘）；沒有或是空的回 null。 */
        fun parseToken(page: String): String? =
            Jsoup.parse(page).selectFirst("input[name=SYNCHRONIZER_TOKEN]")?.attr("value")?.trim()?.takeIf { it.isNotEmpty() }

        /** 查詢頁的隱藏欄位 `firDate`（最新的一週，POST 要原樣帶回去）；沒有回 null。 */
        fun parseFirstDate(page: String): String? =
            Jsoup.parse(page).selectFirst("input[name=firDate]")?.attr("value")?.trim()?.takeIf { it.length == 8 && it.all(Char::isDigit) }

        private fun origin(url: String): String = java.net.URI(url).let { "${it.scheme}://${it.authority}" }

        /** 查詢頁 `<select name="scaDate">` 裡的 option，新到舊。 */
        fun parseDates(page: String): List<String> {
            val doc = Jsoup.parse(page)
            return doc.select("select[name=scaDate] option").map { it.attr("value").trim() }.filter { it.length == 8 && it.all(Char::isDigit) }
        }

        /**
         * 結果頁的表格：每列「序、持股分級、人數、股數、占集保庫存數比例」。合計列的「序」不是數字；「差異數調整」略過。
         * 先找表格，找不到才看「查無此資料」（頁面其他地方可能也有這幾個字）。
         * 沒有分級表也不是查無：token 或 session 失效（回空表），丟 [HoldersExpiredException]。
         */
        fun parseWeek(page: String, ymd: String): HolderWeek? {
            val doc = Jsoup.parse(page)
            val levels = mutableListOf<HolderLevel>()
            var total: Pair<Int, Long>? = null
            for (tr in doc.select("tr")) {
                // 只看這一列自己的格子（外面若還包著排版用的表格，不會把整張表當成一列）
                val cells = tr.children().filter { it.tagName() == "td" || it.tagName() == "th" }.map { it.text().trim() }
                if (cells.size < 5) continue
                val holders = cells[2].replace(",", "").toIntOrNull() ?: continue
                val shares = cells[3].replace(",", "").toLongOrNull() ?: continue
                val pct = cells[4].replace("%", "").replace(",", "").toDoubleOrNull() ?: continue
                val level = cells[0].toIntOrNull()
                when {
                    cells[1].contains("合") && cells[1].contains("計") -> total = holders to shares
                    cells[1].contains("差異數") -> Unit
                    level != null && level in 1..15 -> levels += HolderLevel(level, cells[1], holders, shares, pct)
                }
            }
            if (levels.isEmpty()) {
                if ("查無此資料" in doc.text()) return null
                throw HoldersExpiredException("集保：回應裡沒有分級表（token 或 session 失效，過一陣子再試）")
            }
            if (levels.size != 15) throw HoldersException("集保：分級只有 ${levels.size} 級，頁面可能改版了")
            val byLevel = levels.associateBy { it.level }
            val groups = GROUPS.map { (label, range) ->
                val picked = range.mapNotNull { byLevel[it] }
                HolderGroup(label, picked.sumOf { it.holders }, picked.sumOf { it.shares }, picked.sumOf { it.pct })
            }
            val t = total ?: (levels.sumOf { it.holders } to levels.sumOf { it.shares })
            return HolderWeek("${ymd.substring(0, 4)}-${ymd.substring(4, 6)}-${ymd.substring(6, 8)}", t.first, t.second, levels, groups)
        }
    }
}
