package com.twtrubiks.stockdesk.core.store

import com.twtrubiks.stockdesk.core.model.StockCode
import com.twtrubiks.stockdesk.core.model.StockRef
import com.twtrubiks.stockdesk.core.names.StockNames
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DuplicateStockException(val existing: StockRef) : Exception("${existing.code} 已經在自選股裡了")

class InvalidStockCodeException(val raw: String) : Exception("「$raw」不是股票代號（4～6 碼英數字）")

/**
 * 自選股清單：goodinfo、社群兩個分頁都照這份清單列。使用者自己加減、排順序；可以匯出匯入 JSON（換手機用）。
 * 純 Kotlin ＋ SQL：手機上接 `AndroidSql`，JVM 測試接 sqlite-jdbc。
 *
 * 資料表 `watchlist`：`code` 主鍵、`name` 可 NULL（查不到名稱就只顯示代號）、`position` 排序（0 在最上面）。
 */
class WatchlistStore(private val sql: Sql, private val names: StockNames) {
    init {
        sql.execute(
            """
            CREATE TABLE IF NOT EXISTS watchlist (
                code TEXT PRIMARY KEY,
                name TEXT,
                position INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    fun all(): List<StockRef> = sql.query("SELECT code, name FROM watchlist ORDER BY position, rowid").map(::toRef)

    fun find(code: String): StockRef? = sql.query("SELECT code, name FROM watchlist WHERE code = ?", code).firstOrNull()?.let(::toRef)

    /**
     * 加一檔到最下面。代號不合法丟 [InvalidStockCodeException]，已經在清單裡丟 [DuplicateStockException]。
     * [name] 沒給就查對照表；查不到就 NULL，之後使用者可以用 [rename] 自己填。
     */
    fun add(rawCode: String, name: String? = null): StockRef = sql.transaction {
        val code = StockCode.normalize(rawCode) ?: throw InvalidStockCodeException(rawCode)
        find(code)?.let { throw DuplicateStockException(it) }
        val resolved = name?.trim()?.takeIf { it.isNotEmpty() } ?: names[code]
        val position = (sql.query("SELECT MAX(position) AS p FROM watchlist").firstOrNull()?.longOrNull("p") ?: -1L) + 1
        sql.execute("INSERT INTO watchlist (code, name, position) VALUES (?, ?, ?)", code, resolved, position)
        StockRef(code, resolved)
    }

    fun remove(code: String) {
        sql.execute("DELETE FROM watchlist WHERE code = ?", code)
    }

    /** 改名稱；空字串就是清掉（只顯示代號）。 */
    fun rename(code: String, name: String?) {
        sql.execute("UPDATE watchlist SET name = ? WHERE code = ?", name?.trim()?.takeIf { it.isNotEmpty() }, code)
    }

    /** 整份重新排序：[codes] 是新的順序（畫面上拖完的結果）。不在 [codes] 裡的維持原本相對順序排在後面。 */
    fun reorder(codes: List<String>) = sql.transaction {
        val rest = all().map { it.code }.filter { it !in codes }
        (codes + rest).forEachIndexed { i, code -> sql.execute("UPDATE watchlist SET position = ? WHERE code = ?", i, code) }
    }

    /** 往上或往下移一格（沒有拖曳排序時的替代做法）。 */
    fun move(code: String, delta: Int) {
        val codes = all().map { it.code }.toMutableList()
        val from = codes.indexOf(code)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, codes.lastIndex)
        if (to == from) return
        codes.add(to, codes.removeAt(from))
        reorder(codes)
    }

    // --- 匯出／匯入（JSON）---------------------------------------------------------

    @Serializable
    data class Export(val version: Int = 1, val stocks: List<Entry>) {
        @Serializable
        data class Entry(val code: String, val name: String? = null)
    }

    fun export(): String = json.encodeToString(Export(stocks = all().map { Export.Entry(it.code, it.name) }))

    /**
     * 讀回匯出的檔案。已經在清單裡的跳過（不覆蓋名稱），不合法的代號跳過；回傳實際加了幾檔。
     * 檔案格式不對丟 [kotlinx.serialization.SerializationException]。
     */
    fun import(text: String): Int = sql.transaction {
        val data = json.decodeFromString<Export>(text)
        var added = 0
        for (entry in data.stocks) {
            val code = StockCode.normalize(entry.code) ?: continue
            if (find(code) != null) continue
            add(code, entry.name)
            added++
        }
        added
    }

    private fun toRef(row: Row) = StockRef(row.string("code"), row.stringOrNull("name"))

    private companion object {
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
