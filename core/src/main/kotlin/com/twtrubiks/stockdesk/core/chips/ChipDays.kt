package com.twtrubiks.stockdesk.core.chips

import com.twtrubiks.stockdesk.core.govbank.GovBankDay
import com.twtrubiks.stockdesk.core.margin.MarginDay

/** goodinfo 卡片「融資融券・官股」表的一列：同一天的融資融券與八大官股，哪一邊那天沒有就是 null。[date] 是 ISO（2026-11-06）。 */
data class ChipDay(val date: String, val margin: MarginDay?, val gov: GovBankDay?)

object ChipDays {
    /**
     * 把融資融券與八大官股照日期併成一張表（新到舊）。兩邊公布的時間不一樣（官股約 18:30，融資融券更晚），所以：
     * - 有融資融券時：一列一個融資融券的交易日，官股對得上日期的填進去；官股比融資融券新的那幾天（融資融券還沒公布）排在最上面，
     *   那幾列沒有融資融券
     * - 沒有融資融券（抓失敗、停止信用交易）：官股最近 [days] 天
     *
     * [gov] 要比 [days] 多帶幾天，融資融券最舊的那幾天才對得到。兩邊都要是新到舊。
     */
    fun merge(margin: List<MarginDay>, gov: List<GovBankDay>, days: Int): List<ChipDay> {
        if (margin.isEmpty()) return gov.take(days).map { ChipDay(it.date, null, it) }
        val byDate = gov.associateBy { it.date }
        val newest = margin.first().date
        val ahead = gov.filter { it.date > newest }.map { ChipDay(it.date, null, it) }
        return ahead + margin.map { ChipDay(it.date, it, byDate[it.date]) }
    }
}
