package com.twtrubiks.stockdesk

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.twtrubiks.stockdesk.core.margin.MarginSource
import com.twtrubiks.stockdesk.core.margin.MarketTotals
import com.twtrubiks.stockdesk.core.model.Market
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 大盤分頁的「資券」：整個市場的信用交易統計，上市（證交所）、上櫃（櫃買）各一塊（[MarginTotalsBox]）：
 * 最新一天的「資減 6.94 億」（融資金額今日餘額 − 前日餘額）、「券減 14,415 張」（融券張數），與最近幾個交易日（設定頁選 3／5／10，預設 3）。
 *
 * 進頁面時：快取裡有的那幾天不問，缺的才一天一發（[FetchJobs.fetchMarginTotals]，force = false）；平日晚上 9 點以後才問今天。
 * 大盤標題列的重新整理（[refresh] 加一）不管幾點都問今天。抓取在 [FetchJobs] 裡跑：切到別的分頁不會中斷，
 * 一次只跑一個（別的抓取在跑時進頁面不抓，只顯示快取）。goodinfo 抓個股的融資融券時，同一份回應裡的統計也會順手存起來。
 */
@Composable
fun MarginTotalsPage(refresh: Int) {
    val c = LocalContext.current.container
    val jobs = c.jobs
    val state = jobs.marginTotals
    val result = jobs.marginTotalsResult

    val days = c.marginTotalsDays.value

    LaunchedEffect(refresh) { jobs.fetchMarginTotals(force = refresh > 0) }
    // 設定頁改了天數：缺的那幾天補抓（快取裡有的不問）。第一次進來上面那個已經在跑，這個不會重複抓
    LaunchedEffect(days) { jobs.fetchMarginTotals(force = false) }

    // 要顯示的：這次抓的結果，還沒抓完（或別的抓取佔著）就是快取裡的。上次的結果可能是照舊的天數抓的，多的不顯示
    val shown by produceState<List<MarketTotals>?>(null, result, days) {
        value = result?.map { it.copy(days = it.days.take(days)) } ?: withContext(Dispatchers.IO) { MarginSource.cachedTotals(c.cache, days) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        when (state) {
            is JobState.Running -> JobProgress(state)
            is JobState.Failed -> ErrorBox(state.message)
            else -> Unit
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "每個交易日晚上約 9 點更新",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state is JobState.Ready && state.cached) {
                Spacer(Modifier.width(6.dp))
                Tag("快取")
            }
        }
        shown?.forEach { totals ->
            MarginTotalsBox(totals)
            val name = if (totals.market == Market.TWSE) "上市" else "上櫃"
            val note = totals.note
            when {
                note != null && totals.days.isEmpty() -> ErrorBox("$name：$note")
                note != null -> Text(
                    "重抓失敗（$note），顯示上次的資料",
                    Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
                totals.days.isEmpty() && state !is JobState.Running -> Text(
                    "$name：還沒有資料，按右上角的重新整理",
                    Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(
            "資增減是融資金額的今日餘額減前日餘額（億，兩位小數、無條件捨去），券增減是融券餘額的張數。" +
                "資料來自臺灣證券交易所、證券櫃檯買賣中心。",
            Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
