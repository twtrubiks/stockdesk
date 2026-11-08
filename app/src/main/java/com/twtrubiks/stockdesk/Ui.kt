package com.twtrubiks.stockdesk

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.twtrubiks.stockdesk.core.http.Taipei
import com.twtrubiks.stockdesk.core.indices.IndexDay
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** 抓取進度：一行字 ＋ 進度條（total 不知道時是不確定的那種）。 */
@Composable
fun JobProgress(state: JobState.Running, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(state.message, style = MaterialTheme.typography.bodySmall)
        if (state.total > 0) LinearProgressIndicator(progress = { state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun ErrorText(message: String, modifier: Modifier = Modifier) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = modifier)
}

// 數字格式（docs/spec.md）：張數加千分位、增減帶正負號、百分比兩位小數（權重四位）、null 顯示「-」。
// 一律用 Locale.US：手機語言設成德文、法文時千分位不會變成「.」或空白。
// 不要用 Locale.ROOT：JVM 上有千分位，Android 上沒有（CI 截圖抓到的；DeviceTest.numberFormatOnDevice 擋著）

/** 數字加千分位；null 顯示「-」。 */
fun fmt(n: Long?): String = n?.let { "%,d".format(Locale.US, it) } ?: "-"

/** 帶正負號的增減。 */
fun fmtSigned(n: Long?): String = n?.let { "%+,d".format(Locale.US, it) } ?: "-"

/** 帶正負號的百分點增減（大戶比例的變化），不帶 %。 */
fun fmtSignedPct(d: Double?, digits: Int = 2): String = d?.let { "%+.${digits}f".format(Locale.US, it) } ?: "-"

fun fmtPct(d: Double?, digits: Int = 2): String = d?.let { "%.${digits}f%%".format(Locale.US, it) } ?: "-"

/** 指數點數：千分位、兩位小數（「51,285.73」）。 */
fun fmtPrice(d: Double): String = "%,.2f".format(Locale.US, d)

/** 指數的漲跌：「▲ 23.13 (0.31%)」「▼ 237.33 (0.46%)」，平盤沒有箭頭。顏色另外給（漲紅跌綠）。 */
fun fmtIndexChange(change: Double, pct: Double): String {
    val arrow = when {
        "%.2f".format(Locale.US, abs(change)) == "0.00" -> ""
        change > 0 -> "▲ "
        else -> "▼ "
    }
    return "$arrow${fmtPrice(abs(change))} (${"%.2f".format(Locale.US, abs(pct))}%)"
}

private val MONTH_DAY = DateTimeFormatter.ofPattern("MM/dd")
private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 指數卡片底下那一行：收盤了是「11/06 收盤」，還在盤中（抓的時候）是「11/09 盤中 10:35」。
 * 日期是交易所當地那一天的盤（美股 11/06 的盤），時間是最新一筆的台北時間。
 */
fun indexStatus(day: IndexDay): String {
    val date = day.sessionDate.format(MONTH_DAY)
    if (day.closed) return "$date 收盤"
    val at = Instant.ofEpochSecond(day.times.lastOrNull() ?: day.marketTime).atZone(Taipei.ZONE).format(HOUR_MINUTE)
    return "$date 盤中 $at"
}
