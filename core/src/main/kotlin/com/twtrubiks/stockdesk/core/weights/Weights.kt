package com.twtrubiks.stockdesk.core.weights

import com.twtrubiks.stockdesk.core.http.Http
import com.twtrubiks.stockdesk.core.http.Taipei
import com.twtrubiks.stockdesk.core.http.USER_AGENT
import com.twtrubiks.stockdesk.core.store.CacheStore
import com.twtrubiks.stockdesk.core.store.CacheStore.WeightRow
import org.jsoup.Jsoup
import java.io.IOException
import java.time.LocalDate

class WeightsException(message: String) : Exception(message)

/** 權重頁要顯示的：資料日期（月底快照）、抓取時間、整張表、是不是從快取來的、重抓失敗時的提示。 */
data class Weights(
    val dataDate: String,
    val fetchedAt: String,
    val rows: List<WeightRow>,
    val cached: Boolean,
    val warning: String? = null,
) {
    /** 前 n 名，多帶累計比重。 */
    fun top(n: Int): List<Pair<WeightRow, Double>> {
        var cum = 0.0
        return rows.take(n).map { row -> cum += row.weight; row to cum }
    }
}

/**
 * 加權指數成分股權重（市值佔大盤比重），來源是期交所的網頁 `cht/9/futuresQADetail`
 * （標題「臺灣證券交易所發行量加權股價指數成分股暨市值比重」）：
 *
 * - 整張表直接渲染在 HTML 裡，一個 GET 就拿得到；期交所與證交所的 OpenAPI 都沒有同一份資料
 * - 頁尾註明「每月月底更新一次」，只有最新一期、沒有歷史；只收上市股（含創新板）
 * - 頁面約 500 KB、沒有 Cache-Control：**一天只自動抓一次**（台北日期），「手動更新」才重抓
 * - 解析結果不合理（筆數 < 500、排行不連續、比重加總不在 99%～101%）一律當失敗，避免期交所改版後顯示殘缺的表；
 *   重抓失敗但快取裡有舊資料時照樣顯示舊的，附 warning
 *
 * `url` 可以換（測試接 MockWebServer）。
 */
class WeightsSource(
    private val http: Http,
    private val cache: CacheStore,
    private val url: String = "https://www.taifex.com.tw/cht/9/futuresQADetail",
    private val today: () -> LocalDate = { Taipei.today() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** [force] 是「手動更新」；否則今天抓過就讀快取。 */
    suspend fun load(force: Boolean = false): Weights {
        val cachedRows = cache.weights()
        val fetchedAt = cache.meta(CacheStore.META_WEIGHTS_FETCHED)
        val dataDate = cache.meta(CacheStore.META_WEIGHTS_DATE)
        val fresh = fetchedAt != null && fetchedAt.startsWith(today().toString())
        if (!force && fresh && cachedRows.isNotEmpty() && dataDate != null) return Weights(dataDate, fetchedAt, cachedRows, cached = true)

        return try {
            val r = http.get(url, mapOf("User-Agent" to USER_AGENT))
            if (!r.ok) throw WeightsException("期交所：HTTP ${r.code}")
            val (date, rows) = parse(r.body)
            val stamp = Taipei.stamp(now())
            cache.putWeights(rows, date, stamp)
            Weights(date, stamp, rows, cached = false)
        } catch (e: Exception) {
            if (e !is WeightsException && e !is IOException) throw e
            if (cachedRows.isNotEmpty() && dataDate != null && fetchedAt != null) {
                Weights(dataDate, fetchedAt, cachedRows, cached = true, warning = "重抓失敗（${e.message}），顯示上次的資料")
            } else {
                throw WeightsException(e.message ?: "抓取失敗")
            }
        }
    }

    companion object {
        const val MIN_ROWS = 500
        private val WEIGHT_SUM = 99.0..101.0
        private val DATE = Regex("資料日期[：:]\\s*(\\d{4})/(\\d{1,2})/(\\d{1,2})")
        private val CODE = Regex("\\d{4,6}[A-Z]?")

        /**
         * 表格是左右兩欄排版（1～535 在左、536～ 在右），每筆 4 格：排行／代號／名稱／比重。
         * 排行超過千會帶千分位（「1,068」），名稱可能帶符號（「國巨*」、「正瀚-創」），照原樣保留。
         * 做法：把所有 `<td>` 攤平、每 4 格看一次，像「排行、代號、名稱、xx%」的才收。**用 fixture 確認**（`fixtures/taifex-weights.html`）。
         */
        fun parse(html: String): Pair<String, List<WeightRow>> {
            val m = DATE.find(html) ?: throw WeightsException("期交所：找不到「資料日期」，頁面可能改版了")
            val date = LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()).toString()
            val cells = Jsoup.parse(html).select("td").map { it.text().trim() }
            val rows = mutableListOf<WeightRow>()
            var i = 0
            while (i + 3 < cells.size) {
                val rank = cells[i].replace(",", "").toIntOrNull()
                val code = cells[i + 1]
                val weight = cells[i + 3]
                if (rank != null && CODE.matches(code) && weight.endsWith("%") && weight.dropLast(1).toDoubleOrNull() != null) {
                    rows += WeightRow(rank, code, cells[i + 2], weight.dropLast(1).toDouble())
                    i += 4
                } else {
                    i++
                }
            }
            rows.sortBy { it.rank }
            if (rows.size < MIN_ROWS) throw WeightsException("只解析出 ${rows.size} 筆（預期 $MIN_ROWS 筆以上），期交所可能改版了")
            if (rows.map { it.rank } != (1..rows.size).toList()) throw WeightsException("排行不連續，期交所可能改版了")
            val sum = rows.sumOf { it.weight }
            if (sum !in WEIGHT_SUM) throw WeightsException("比重加總 ${"%.2f".format(sum)}% 不合理，期交所可能改版了")
            return date to rows
        }
    }
}
