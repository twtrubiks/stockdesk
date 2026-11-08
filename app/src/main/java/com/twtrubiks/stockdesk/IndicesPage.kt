package com.twtrubiks.stockdesk

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twtrubiks.stockdesk.core.indices.IndexDay
import com.twtrubiks.stockdesk.core.indices.Indices
import com.twtrubiks.stockdesk.core.indices.IndicesException
import com.twtrubiks.stockdesk.core.links.Links
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 大盤分頁的「國際指數」：各國股市指數最近一個交易日的走勢，兩欄卡片（寬螢幕自動多欄）。
 * 每張卡：名稱、點數、漲跌（漲紅跌綠）、走勢圖（昨收是虛線，高於昨收塗紅、低於塗綠）、哪一天的盤／收盤了沒。
 * 進頁面時上次抓取超過 5 分鐘才抓（[com.twtrubiks.stockdesk.core.indices.IndicesSource.FRESH_MS]），其他時候按標題列的重新整理（[refresh] 加一）。
 * 沒有自動更新。資料是 Yahoo 的（非官方），抓不到時底下有「在 Yahoo 股市看」開網頁版。
 */
@Composable
fun IndicesPage(refresh: Int, onLoading: (Boolean) -> Unit, onOpen: (url: String) -> Unit) {
    val c = LocalContext.current.container
    var data by remember { mutableStateOf<Indices?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh) {
        onLoading(true)
        error = null
        try {
            data = withContext(Dispatchers.IO) { c.indices.load(force = refresh > 0) }
        } catch (e: IndicesException) {
            error = e.message
        } finally {
            onLoading(false)
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                error?.let { ErrorBox(it) }
                data?.let { d ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${d.fetchedAt.drop(5).replace('-', '/')} 抓取（台北時間）",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (d.cached) Tag("快取")
                    }
                    d.warning?.let {
                        Text(it, Modifier.padding(horizontal = 4.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        items(data?.days.orEmpty(), key = { it.symbol }) { IndexCard(it) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "資料來自 Yahoo 股市（非官方），5 分鐘一點；不會自動更新",
                    Modifier.weight(1f).padding(start = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onOpen(Links.YAHOO_WORLD_INDICES) }, Modifier.testTag("indices_yahoo")) { Text("在 Yahoo 股市看") }
            }
        }
    }
}

/** 一張指數卡。 */
@Composable
private fun IndexCard(day: IndexDay) {
    val color = changeColor(day.change)
    Card(
        Modifier.fillMaxWidth().testTag("index_${day.symbol.removePrefix("^")}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
            Text(day.name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fmtPrice(day.price), style = numStyle(MaterialTheme.typography.titleLarge).copy(fontWeight = FontWeight.SemiBold), color = color, maxLines = 1)
            Text(fmtIndexChange(day.change, day.changePct), style = numStyle(MaterialTheme.typography.labelLarge), color = color, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            IntradayChart(day, Modifier.fillMaxWidth().height(64.dp))
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(indexStatus(day), style = numStyle(MaterialTheme.typography.labelSmall), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

/**
 * 當天走勢：x 軸是整個盤（開盤到收盤，盤中時線只畫到一半），y 軸涵蓋走勢與昨收。
 * 昨收是虛線；線與昨收之間的面積，在昨收以上塗紅、以下塗綠（漸層，越靠近昨收越淡），線本身也照上下換色。最新一點畫一個點。
 */
@Composable
fun IntradayChart(day: IndexDay, modifier: Modifier = Modifier) {
    val up = changeColor(1.0)
    val down = changeColor(-1.0)
    val last = changeColor(day.change)
    val baseColor = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        if (day.times.isEmpty()) return@Canvas
        val lo = minOf(day.values.min(), day.prevClose)
        val hi = maxOf(day.values.max(), day.prevClose)
        // 一整天幾乎沒動時不要把小抖動放大成滿版
        val pad = maxOf(hi - lo, day.prevClose * 0.002) * 0.1
        val yMin = lo - pad
        val yMax = hi + pad
        val span = (day.sessionEnd - day.sessionStart).coerceAtLeast(1)
        fun x(t: Long) = ((t - day.sessionStart).toFloat() / span).coerceIn(0f, 1f) * size.width
        fun y(v: Double) = ((yMax - v) / (yMax - yMin)).toFloat() * size.height
        val baseY = y(day.prevClose)

        val line = Path()
        val area = Path()
        day.times.forEachIndexed { i, t ->
            val px = x(t)
            val py = y(day.values[i])
            if (i == 0) {
                line.moveTo(px, py)
                area.moveTo(px, baseY)
            }
            line.lineTo(px, py)
            area.lineTo(px, py)
        }
        area.lineTo(x(day.times.last()), baseY)
        area.close()

        val stroke = Stroke(width = 1.5.dp.toPx())
        clipRect(bottom = baseY) {
            drawPath(area, Brush.verticalGradient(listOf(up.copy(alpha = 0.35f), up.copy(alpha = 0.05f)), startY = 0f, endY = baseY.coerceAtLeast(1f)))
            drawPath(line, up, style = stroke)
        }
        clipRect(top = baseY) {
            drawPath(area, Brush.verticalGradient(listOf(down.copy(alpha = 0.05f), down.copy(alpha = 0.35f)), startY = baseY, endY = size.height))
            drawPath(line, down, style = stroke)
        }
        val dash = 3.dp.toPx()
        drawLine(baseColor, Offset(0f, baseY), Offset(size.width, baseY), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)))
        drawCircle(last, radius = 2.5.dp.toPx(), center = Offset(x(day.times.last()), y(day.values.last())))
    }
}
