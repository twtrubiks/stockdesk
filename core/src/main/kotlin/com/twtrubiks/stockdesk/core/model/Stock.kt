package com.twtrubiks.stockdesk.core.model

/** 自選股的一檔：代號一定有，名稱可能查不到（新股、沒在對照表裡），畫面就只顯示代號。 */
data class StockRef(val code: String, val name: String?) {
    /** 「2330 台積電」或只有「2330」。 */
    val label: String get() = if (name.isNullOrBlank()) code else "$code $name"
}

/** 上市（證交所）或上櫃（櫃買中心）。融資融券的資料來源不同，事先不知道是哪邊，第一次抓時才判斷出來。 */
enum class Market(val label: String) {
    TWSE("上市"),
    TPEX("上櫃"),
}

object StockCode {
    // 台股代號 4～6 碼英數字（ETF 可能帶 B／L／R 之類的字尾，例 00631L）
    private val PATTERN = Regex("^[0-9A-Za-z]{4,6}$")

    fun isValid(raw: String): Boolean = PATTERN.matches(raw.trim())

    /** 去頭尾空白、英文字母轉大寫；不合法回 null。 */
    fun normalize(raw: String): String? = raw.trim().uppercase().takeIf(::isValid)
}
