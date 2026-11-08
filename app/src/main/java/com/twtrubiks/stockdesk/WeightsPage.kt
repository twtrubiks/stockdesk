package com.twtrubiks.stockdesk

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.twtrubiks.stockdesk.core.store.CacheStore
import com.twtrubiks.stockdesk.core.weights.Weights
import com.twtrubiks.stockdesk.core.weights.WeightsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 大盤分頁的「權重」：加權指數成分股市值比重前 N 名（10／30／50／100），每列排行、名稱與代號、比重、累計，底下是相對第一名的細長條。
 * 進頁面時讀快取（今天抓過就不連網）；大盤標題列的重新整理強制重抓（[refresh] 加一，抓的時候那顆變成轉圈）。重抓失敗但有舊資料就顯示舊的＋提示。
 */
@Composable
fun WeightsPage(refresh: Int, onLoading: (Boolean) -> Unit) {
    val c = LocalContext.current.container
    var top by rememberSaveable { mutableIntStateOf(30) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var weights by remember { mutableStateOf<Weights?>(null) }

    LaunchedEffect(refresh) {
        onLoading(true)
        error = null
        try {
            weights = withContext(Dispatchers.IO) { c.weights.load(force = refresh > 0) }
        } catch (e: WeightsException) {
            error = e.message
        } catch (e: IOException) {
            error = "期交所沒回應（${e.message ?: e.javaClass.simpleName}）"
        } finally {
            onLoading(false)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 窄螢幕放不下四個 chip 時可以左右滑，不會擠爆
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(10, 30, 50, 100).forEach { n -> FilterChip(selected = top == n, onClick = { top = n }, label = { Text("前 $n") }) }
        }
        error?.let { ErrorBox(it, Modifier.padding(horizontal = 12.dp)) }
        val w = weights ?: return@Column
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("資料日期 ${w.dataDate.replace('-', '/')}", style = MaterialTheme.typography.labelLarge)
            Text("・每月月底更新", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${w.fetchedAt.drop(5).replace('-', '/')} 抓取", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (w.cached) {
                Spacer(Modifier.width(6.dp))
                Tag("快取")
            }
        }
        w.warning?.let {
            Text(
                it,
                Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val rows = w.top(top)
        val max = rows.firstOrNull()?.first?.weight ?: 1.0
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            itemsIndexed(rows, key = { _, it -> it.first.rank }) { i, (row, cum) ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                WeightRowItem(row, cum, (row.weight / max).toFloat())
            }
        }
    }
}

/** 一列：排行｜名稱（代號）｜比重、累計。背景是相對第一名的長條。 */
@Composable
private fun WeightRowItem(row: CacheStore.WeightRow, cum: Double, fraction: Float) {
    val top3 = row.rank <= 3
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${row.rank}",
                Modifier.width(34.dp),
                style = numStyle(MaterialTheme.typography.labelLarge).copy(fontWeight = if (top3) FontWeight.SemiBold else FontWeight.Normal),
                color = if (top3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(row.code, style = numStyle(MaterialTheme.typography.labelSmall), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(fmtPct(row.weight, 4), style = numStyle(MaterialTheme.typography.bodyMedium).copy(fontWeight = FontWeight.SemiBold))
                Text("累計 ${fmtPct(cum)}", style = numStyle(MaterialTheme.typography.labelSmall), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // 比重的細長條（以第一名為滿），對齊名稱那一欄
        Row(Modifier.padding(top = 6.dp)) {
            Spacer(Modifier.width(34.dp))
            Box(
                Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0.005f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = if (top3) 0.9f else 0.55f)),
                )
            }
        }
    }
}
