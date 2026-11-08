package com.twtrubiks.stockdesk.core.store

import com.twtrubiks.stockdesk.core.holders.HolderWeek
import com.twtrubiks.stockdesk.core.margin.MarginDay
import com.twtrubiks.stockdesk.core.model.Market
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * 抓回來的資料落地在 SQLite，離線也看得到上一次的結果。每種資料的快取單位跟來源一樣：
 *
 * - 融資融券：來源一次回「一天、全市場」，但只存自選股的那幾列（整份一天約 0.4 MB，60 天就 24 MB，手機上不留）。
 *   `margin_days(market, date, closed)` 記這一天抓過了沒、是不是休市；`margin_rows(market, date, code, ...)` 是每檔那天的數字。
 *   某天抓過、但某檔沒有列（後來才加進自選股）→ 那天要重抓一次。
 * - 股東持股分級：來源一次回「一檔、一週」，`holders_weeks(code, date, json)` 整筆存 JSON；公布後不會再變，永久有效。
 * - 權重：整張表（約 1,000 列）存 `weights(rank, code, name, weight)`，另記資料日期與抓取時間；一天只自動抓一次。
 *
 * 「今天沒資料」可能只是還沒公布，不記成休市：由 source 在記憶體裡記 10 分鐘（見 `MarginSource.TODAY_EMPTY_TTL`）。
 */
class CacheStore(private val sql: Sql) {
    init {
        sql.execute(
            """
            CREATE TABLE IF NOT EXISTS margin_days (
                market TEXT NOT NULL,
                date TEXT NOT NULL,
                closed INTEGER NOT NULL,
                PRIMARY KEY (market, date)
            )
            """.trimIndent(),
        )
        sql.execute(
            """
            CREATE TABLE IF NOT EXISTS margin_rows (
                market TEXT NOT NULL,
                date TEXT NOT NULL,
                code TEXT NOT NULL,
                json TEXT NOT NULL,
                PRIMARY KEY (market, date, code)
            )
            """.trimIndent(),
        )
        sql.execute(
            """
            CREATE TABLE IF NOT EXISTS holders_weeks (
                code TEXT NOT NULL,
                date TEXT NOT NULL,
                json TEXT NOT NULL,
                PRIMARY KEY (code, date)
            )
            """.trimIndent(),
        )
        sql.execute(
            """
            CREATE TABLE IF NOT EXISTS weights (
                rank INTEGER PRIMARY KEY,
                code TEXT NOT NULL,
                name TEXT NOT NULL,
                weight REAL NOT NULL
            )
            """.trimIndent(),
        )
        sql.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        // 每檔自選股在哪個市場（第一次抓融資融券時判斷出來的），之後不用再試兩邊
        sql.execute("CREATE TABLE IF NOT EXISTS markets (code TEXT PRIMARY KEY, market TEXT NOT NULL)")
    }

    // --- 融資融券 -----------------------------------------------------------------

    /** 這一天抓過了嗎？null = 沒抓過；true = 休市；false = 有資料（列在 margin_rows）。 */
    fun marginDayState(market: Market, date: LocalDate): Boolean? =
        sql.query("SELECT closed FROM margin_days WHERE market = ? AND date = ?", market.name, date.toString())
            .firstOrNull()?.bool("closed")

    fun marginRow(market: Market, date: LocalDate, code: String): MarginDay? =
        sql.query("SELECT json FROM margin_rows WHERE market = ? AND date = ? AND code = ?", market.name, date.toString(), code)
            .firstOrNull()?.let { json.decodeFromString<MarginDay>(it.string("json")) }

    /** 存一天的結果：[rows] 是自選股裡有出現的那幾檔；空的 map 配 closed=false 表示有開市但自選股都沒有（理論上不會）。 */
    fun putMarginDay(market: Market, date: LocalDate, closed: Boolean, rows: Map<String, MarginDay>) = sql.transaction {
        sql.execute("INSERT OR REPLACE INTO margin_days (market, date, closed) VALUES (?, ?, ?)", market.name, date.toString(), if (closed) 1 else 0)
        rows.forEach { (code, row) ->
            sql.execute(
                "INSERT OR REPLACE INTO margin_rows (market, date, code, json) VALUES (?, ?, ?, ?)",
                market.name, date.toString(), code, json.encodeToString(row),
            )
        }
    }

    fun market(code: String): Market? =
        sql.query("SELECT market FROM markets WHERE code = ?", code).firstOrNull()?.let { Market.valueOf(it.string("market")) }

    fun putMarket(code: String, market: Market) {
        sql.execute("INSERT OR REPLACE INTO markets (code, market) VALUES (?, ?)", code, market.name)
    }

    // --- 股東持股分級 --------------------------------------------------------------

    fun holdersWeek(code: String, date: LocalDate): HolderWeek? =
        sql.query("SELECT json FROM holders_weeks WHERE code = ? AND date = ?", code, date.toString())
            .firstOrNull()?.let { json.decodeFromString<HolderWeek>(it.string("json")) }

    fun putHoldersWeek(code: String, week: HolderWeek) {
        sql.execute("INSERT OR REPLACE INTO holders_weeks (code, date, json) VALUES (?, ?, ?)", code, week.date, json.encodeToString(week))
    }

    // --- 權重 ------------------------------------------------------------------------

    data class WeightRow(val rank: Int, val code: String, val name: String, val weight: Double)

    fun weights(): List<WeightRow> =
        sql.query("SELECT rank, code, name, weight FROM weights ORDER BY rank").map {
            WeightRow(it.int("rank"), it.string("code"), it.string("name"), it.double("weight"))
        }

    fun putWeights(rows: List<WeightRow>, dataDate: String, fetchedAt: String) = sql.transaction {
        sql.execute("DELETE FROM weights")
        rows.forEach { sql.execute("INSERT INTO weights (rank, code, name, weight) VALUES (?, ?, ?, ?)", it.rank, it.code, it.name, it.weight) }
        putMeta(META_WEIGHTS_DATE, dataDate)
        putMeta(META_WEIGHTS_FETCHED, fetchedAt)
    }

    // --- meta ------------------------------------------------------------------------

    fun meta(key: String): String? = sql.query("SELECT value FROM meta WHERE key = ?", key).firstOrNull()?.string("value")

    fun putMeta(key: String, value: String) {
        sql.execute("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", key, value)
    }

    companion object {
        const val META_WEIGHTS_DATE = "weights.dataDate"
        const val META_WEIGHTS_FETCHED = "weights.fetchedAt"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
