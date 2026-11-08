package com.twtrubiks.stockdesk.core.names

import com.twtrubiks.stockdesk.core.model.StockRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 代號 → 名稱對照表，打包在 `core/src/main/resources/stock_names.json`（約 3,100 檔、80 KB）。
 * 來源是證交所與櫃買的公開端點（`tools/stock_names/fetch_stock_names.py` 產生，新股或新 ETF 掛牌才需要重跑）。
 * 檔案不在或壞掉就是空表：只是沒名稱，不會讓 App 壞掉。
 */
class StockNames(private val names: Map<String, String>) {
    operator fun get(code: String): String? = names[code]

    val size: Int get() = names.size

    private val sorted: List<StockRef> by lazy { names.entries.sortedBy { it.key }.map { StockRef(it.key, it.value) } }

    /**
     * 自選股輸入框的候選：[query] 全是英數字就當代號前綴（「23」→ 2301、2303、2330…，至少 [MIN_CODE_CHARS] 個字才開始列，
     * 一個字會中幾百檔沒意義）；含其他字元就當名稱包含（「台」→ 台泥、台積電…；「積電」也找得到台積電）。
     * 照代號由小到大，最多 [limit] 筆。空字串、只有空白回空清單。
     */
    fun search(query: String, limit: Int = DEFAULT_LIMIT): List<StockRef> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val byCode = q.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }
        if (byCode) {
            if (q.length < MIN_CODE_CHARS) return emptyList()
            val prefix = q.uppercase()
            return sorted.asSequence().filter { it.code.startsWith(prefix) }.take(limit).toList()
        }
        return sorted.asSequence().filter { it.name?.contains(q) == true }.take(limit).toList()
    }

    companion object {
        private const val RESOURCE = "/stock_names.json"
        const val DEFAULT_LIMIT = 10
        const val MIN_CODE_CHARS = 2

        fun load(): StockNames = runCatching {
            val text = StockNames::class.java.getResourceAsStream(RESOURCE)?.bufferedReader()?.readText() ?: return@runCatching empty()
            parse(text)
        }.getOrElse { empty() }

        fun parse(json: String): StockNames {
            val root = Json.parseToJsonElement(json).jsonObject
            val names = root["names"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            return StockNames(names)
        }

        fun empty() = StockNames(emptyMap())
    }
}
