package com.twtrubiks.stockdesk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.twtrubiks.stockdesk.core.govbank.GovBankMarket
import com.twtrubiks.stockdesk.core.govbank.GovBankVia
import com.twtrubiks.stockdesk.core.govbank.RankRow
import com.twtrubiks.stockdesk.core.holders.HolderWeek

// 抓回來的資料怎麼畫：融資融券、股東持股分級、八大官股的表格，與共用的小零件（漲跌色、等寬數字、表頭、標籤）。

/** 台股慣例：漲紅跌綠；0 或沒有資料用次要文字色。深色模式用亮一點的紅綠。 */
@Composable
fun changeColor(v: Double?): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when {
        v == null || v == 0.0 -> MaterialTheme.colorScheme.onSurfaceVariant
        v > 0 -> if (dark) Color(0xFFFF6B6B) else Color(0xFFD32F2F)
        else -> if (dark) Color(0xFF4CC38A) else Color(0xFF1B873F)
    }
}

/** 數字用等寬字（tnum），上下列的位數才對得齊。 */
@Composable
fun numStyle(base: TextStyle = MaterialTheme.typography.bodyMedium): TextStyle = base.copy(fontFeatureSettings = "tnum")

/** 「2026-11-06」→「11/06」。 */
fun shortDate(iso: String): String = if (iso.length == 10) "${iso.substring(5, 7)}/${iso.substring(8, 10)}" else iso

/** 小標籤（上市／上櫃、快取）。 */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text,
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** 外框圓角的表格容器，表頭有底色。 */
@Composable
private fun TableBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
    ) { content() }
}

@Composable
private fun HeaderRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float, align: TextAlign = TextAlign.End) {
    Text(text, Modifier.weight(weight), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align, maxLines = 1)
}

/** 一格數字：上面是值，下面一行小字是增減（漲紅跌綠）。 */
@Composable
private fun RowScope.ValueCell(value: String, change: String? = null, changeValue: Double? = null, weight: Float, bold: Boolean = false) {
    Column(Modifier.weight(weight), horizontalAlignment = Alignment.End) {
        Text(value, style = numStyle(MaterialTheme.typography.bodyMedium).copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal), maxLines = 1)
        if (change != null) Text(change, style = numStyle(MaterialTheme.typography.labelSmall), color = changeColor(changeValue), maxLines = 1)
    }
}

@Composable
private fun RowScope.DateCell(iso: String, weight: Float) {
    Text(shortDate(iso), Modifier.weight(weight), style = numStyle(MaterialTheme.typography.labelLarge), color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun SectionCaption(title: String, unit: String, tag: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (tag != null) {
            Tag(tag)
            Spacer(Modifier.width(6.dp))
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- 融資融券 -----------------------------------------------------------------------

/** 融資融券表的一列：平常照比例分寬度；[wide]（欄位超出螢幕）時日期固定在左邊，右邊的數字欄左右拖，每一列共用 [scroll]。 */
@Composable
private fun MarginLine(wide: Boolean, scroll: ScrollState, modifier: Modifier, date: @Composable (Modifier) -> Unit, cells: @Composable RowScope.() -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (wide) {
            date(Modifier.width(48.dp))
            Row(Modifier.weight(1f).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically, content = cells)
        } else {
            date(Modifier.weight(0.8f))
            cells()
        }
    }
}

/** 一欄的寬度：[wide] 時用固定寬度（可以左右拖），不然照比例。 */
private fun RowScope.col(wide: Boolean, weight: Float, width: Dp): Modifier = if (wide) Modifier.width(width) else Modifier.weight(weight)

@Composable
private fun HeadText(text: String, modifier: Modifier, align: TextAlign = TextAlign.End) {
    Text(text, modifier, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align, maxLines = 1)
}

/** 一格數字：上面是值，下面一行小字（增減，或官股的金額）。[valueColor] 沒給就用一般文字色。 */
@Composable
private fun NumCell(modifier: Modifier, value: String, sub: String? = null, subValue: Double? = null, bold: Boolean = false, valueColor: Color = Color.Unspecified) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(value, style = numStyle(MaterialTheme.typography.bodyMedium).copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal), color = valueColor, maxLines = 1)
        if (sub != null) Text(sub, style = numStyle(MaterialTheme.typography.labelSmall), color = changeColor(subValue), maxLines = 1)
    }
}

/**
 * 融資融券＋八大官股併成一張表。新到舊，一列一天：日期｜融資餘額（增減）｜融券餘額（增減）｜官股（買賣超張數，底下一行小字是金額，
 * 各看各的正負上色）；[showRatios]（設定頁打勾）才多出 資使用率｜券資比 兩欄，接在最後面。六欄手機放不下：日期固定在左邊，
 * 右邊的數字欄左右拖。官股比融資融券早公布：最上面可能有幾列只有官股（融資融券的欄位是「-」）；那天官股沒有資料就寫「-」。
 * 這次沒抓官股（[MarginResult.hasGov] 是 false）就沒有官股那一欄。
 * 兩邊各自的錯誤寫在表格底下，一邊失敗另一邊照樣顯示。最底下一行點了 [onOpenGov]（開官股資料來源那一家的網頁）。
 */
@Composable
fun MarginTable(result: MarginResult, showRatios: Boolean, govTag: String, onOpenGov: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (result.rows.isNotEmpty()) {
            val wide = showRatios && result.hasGov
            val scroll = rememberScrollState()
            SectionCaption("近 ${result.rows.size} 個交易日", if (wide) "單位：張・往左拖看後面" else "單位：張", tag = result.market?.label)
            TableBox {
                MarginLine(
                    wide, scroll,
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 10.dp, vertical = 6.dp),
                    date = { HeadText("日期", it, TextAlign.Start) },
                ) {
                    HeadText("融資餘額", col(wide, 1.25f, 84.dp))
                    HeadText("融券餘額", col(wide, 1.1f, 76.dp))
                    if (result.hasGov) HeadText("官股", col(wide, 1.2f, 88.dp))
                    if (showRatios) {
                        HeadText("資使用率", col(wide, 1f, 72.dp))
                        HeadText("券資比", col(wide, 0.95f, 68.dp))
                    }
                }
                result.rows.forEachIndexed { i, day ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    val r = day.margin
                    val g = day.gov
                    MarginLine(
                        wide, scroll,
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                        date = { Text(shortDate(day.date), it, style = numStyle(MaterialTheme.typography.labelLarge), color = MaterialTheme.colorScheme.onSurface, maxLines = 1) },
                    ) {
                        NumCell(col(wide, 1.25f, 84.dp), fmt(r?.finBal), r?.let { fmtSigned(it.finChg) }, r?.finChg?.toDouble(), bold = true)
                        NumCell(col(wide, 1.1f, 76.dp), fmt(r?.shortBal), r?.let { fmtSigned(it.shortChg) }, r?.shortChg?.toDouble(), bold = true)
                        if (result.hasGov) {
                            NumCell(
                                col(wide, 1.2f, 88.dp),
                                if (g == null) "-" else fmtSigned(g.netLots),
                                g?.let { fmtAmount(it.netAmount) },
                                g?.netAmount,
                                bold = true,
                                valueColor = changeColor(g?.netLots?.toDouble()),
                            )
                        }
                        if (showRatios) {
                            NumCell(col(wide, 1f, 72.dp), fmtPct(r?.finUse))
                            NumCell(col(wide, 0.95f, 68.dp), fmtPct(r?.ratio))
                        }
                    }
                }
            }
        }
        result.marginError?.let { ErrorBox("融資融券：$it") }
        result.govNote?.let { ErrorBox("八大官股：$it") }
        if (result.hasGov) {
            val source = if (result.govVia == GovBankVia.HISTOCK) "HiStock" else "blave"
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp).clickable(onClickLabel = "開官股網頁", onClick = onOpenGov).padding(vertical = 6.dp).testTag(govTag),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "官股是八大官股買進 − 賣出（$source，非官方）・看網頁",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// --- 股東持股分級 -------------------------------------------------------------------

/** 六個級距的顏色：同一個主色、由淡到濃（>1000 張的大戶最濃）。 */
@Composable
private fun groupColors(): List<Color> {
    val p = MaterialTheme.colorScheme.primary
    return listOf(0.16f, 0.28f, 0.40f, 0.55f, 0.75f, 1f).map { p.copy(alpha = it) }
}

/** 級距的列名（張）。 */
private val GROUP_LABELS = listOf("≤10", "10–50", "50–100", "100–400", "400–1000", ">1000")

/** 一週六個級距佔比的長條（加起來 100%）。 */
@Composable
private fun StackedBar(week: HolderWeek, colors: List<Color>, modifier: Modifier = Modifier) {
    val total = week.groups.sumOf { it.pct }.takeIf { it > 0 } ?: 1.0
    Row(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        week.groups.forEachIndexed { i, g ->
            val f = (g.pct / total).toFloat()
            if (f > 0f) Box(Modifier.weight(f).fillMaxHeight().background(colors[i]))
        }
    }
}

/**
 * 上面一張新到舊，一列一週：日期｜股東人數（增減）｜大戶 >1000 張比例（增減，百分點），每週下面一條六段的佔比長條。
 * 下面一張一列一個級距（色點對應長條的顏色）：最新一週｜前一週｜增減（百分點）。手機寬度放不下六個級距當欄，所以轉過來。
 */
@Composable
fun HoldersTable(weeks: List<HolderWeek>) {
    val colors = groupColors()
    Column(Modifier.fillMaxWidth()) {
        SectionCaption("近 ${weeks.size} 週", "集保・每週")
        TableBox {
            HeaderRow {
                HeaderCell("日期", 0.8f, TextAlign.Start)
                HeaderCell("股東人數", 1.4f)
                HeaderCell("大戶 >1000 張", 1.4f)
            }
            weeks.forEachIndexed { i, w ->
                val prev = weeks.getOrNull(i + 1)
                val holdersChg = prev?.let { (w.holders - it.holders).toLong() }
                val bigChg = prev?.let { w.bigPct - it.bigPct }
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DateCell(w.date, 0.8f)
                        ValueCell(fmt(w.holders.toLong()), if (prev == null) null else fmtSigned(holdersChg), holdersChg?.toDouble(), 1.4f, bold = true)
                        ValueCell(fmtPct(w.bigPct), if (prev == null) null else fmtSignedPct(bigChg), bigChg, 1.4f, bold = true)
                    }
                    StackedBar(w, colors, Modifier.padding(top = 6.dp))
                }
            }
        }

        Text(
            "各級持股比例（%）",
            Modifier.padding(top = 10.dp, bottom = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val latest = weeks.first()
        val prev = weeks.getOrNull(1)
        TableBox {
            HeaderRow {
                HeaderCell("持股（張）", 1.3f, TextAlign.Start)
                HeaderCell(shortDate(latest.date), 1f)
                if (prev != null) {
                    HeaderCell(shortDate(prev.date), 1f)
                    HeaderCell("增減", 0.9f)
                }
            }
            latest.groups.forEachIndexed { gi, g ->
                val big = gi == latest.groups.lastIndex
                val before = prev?.groups?.getOrNull(gi)?.pct
                val chg = before?.let { g.pct - it }
                if (gi > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1.3f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(colors[gi]))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            GROUP_LABELS.getOrElse(gi) { g.label },
                            style = numStyle(MaterialTheme.typography.bodyMedium).copy(fontWeight = if (big) FontWeight.SemiBold else FontWeight.Normal),
                            maxLines = 1,
                        )
                    }
                    PctCell(g.pct, 1f, bold = big)
                    if (prev != null) {
                        PctCell(before, 1f, muted = true)
                        Text(
                            if (chg == null) "–" else fmtSignedPct(chg),
                            Modifier.weight(0.9f),
                            style = numStyle(MaterialTheme.typography.bodySmall),
                            color = changeColor(chg),
                            textAlign = TextAlign.End,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.PctCell(pct: Double?, weight: Float, bold: Boolean = false, muted: Boolean = false) {
    Text(
        if (pct == null) "–" else "%.2f".format(java.util.Locale.US, pct),
        Modifier.weight(weight),
        style = numStyle(MaterialTheme.typography.bodyMedium).copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.End,
        maxLines = 1,
    )
}

// --- 八大官股 -----------------------------------------------------------------------

/**
 * 全市場的八大官股（HiStock）：最新一天買超或賣超多少（大字，漲紅跌綠）、前 4 天的總額（舊到新）、
 * 最新一天買超與賣超的前 5 名。
 */
@Composable
fun GovBankMarketBox(market: GovBankMarket, modifier: Modifier = Modifier) {
    val latest = market.latest ?: return
    Column(modifier.fillMaxWidth()) {
        SectionCaption("全市場・${shortDate(latest.date)}", "HiStock")
        TableBox {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).testTag("gov_bank_market")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("八大官股", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${govBankSide(latest.netAmount)} ${fmtAmount(latest.netAmount)}",
                        style = numStyle(MaterialTheme.typography.titleLarge).copy(fontWeight = FontWeight.SemiBold),
                        color = changeColor(latest.netAmount),
                        maxLines = 1,
                    )
                }
                val before = market.days.drop(1).take(4).reversed()
                if (before.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        before.forEach { d ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                Text(shortDate(d.date), style = numStyle(MaterialTheme.typography.labelSmall), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(fmtAmount(d.netAmount), style = numStyle(MaterialTheme.typography.labelSmall), color = changeColor(d.netAmount), maxLines = 1)
                            }
                        }
                    }
                }
                if (market.topBuy.isNotEmpty() || market.topSell.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RankColumn("買超前 5", market.topBuy.take(5), Modifier.weight(1f))
                        RankColumn("賣超前 5", market.topSell.take(5), Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RankColumn(title: String, rows: List<RankRow>, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(fmtAmount(r.netAmount), style = numStyle(MaterialTheme.typography.bodySmall), color = changeColor(r.netAmount), maxLines = 1)
            }
        }
    }
}

/** 抓失敗的訊息：有底色的提示框，比一行紅字好讀。 */
@Composable
fun ErrorBox(message: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().padding(top = 6.dp), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Text(message, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}
