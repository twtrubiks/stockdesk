package com.twtrubiks.stockdesk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twtrubiks.stockdesk.core.govbank.GovBank
import com.twtrubiks.stockdesk.core.govbank.GovBankMarket
import com.twtrubiks.stockdesk.core.govbank.GovBankVia
import com.twtrubiks.stockdesk.core.govbank.StockGovBank
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

/** 新到舊，一列一天：日期｜融資餘額（增減）｜融券餘額（增減）｜資使用率｜券資比。 */
@Composable
fun MarginTable(result: MarginResult) {
    Column(Modifier.fillMaxWidth()) {
        SectionCaption("近 ${result.rows.size} 個交易日", "單位：張", tag = result.market.label)
        TableBox {
            HeaderRow {
                HeaderCell("日期", 0.8f, TextAlign.Start)
                HeaderCell("融資餘額", 1.25f)
                HeaderCell("融券餘額", 1.1f)
                HeaderCell("資使用率", 1f)
                HeaderCell("券資比", 0.95f)
            }
            result.rows.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    DateCell(r.date, 0.8f)
                    ValueCell(fmt(r.finBal), fmtSigned(r.finChg), r.finChg.toDouble(), 1.25f, bold = true)
                    ValueCell(fmt(r.shortBal), fmtSigned(r.shortChg), r.shortChg.toDouble(), 1.1f, bold = true)
                    ValueCell(fmtPct(r.finUse), weight = 1f)
                    ValueCell(fmtPct(r.ratio), weight = 0.95f)
                }
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

/**
 * 自選股的八大官股：一列一檔，個股｜張｜金額（漲紅跌綠，各看各的正負），最底下是自選股合計（那天有資料的檔）。
 * 點一列 [onOpen] 那一檔（官股頁在內建瀏覽器頁開那一檔的網頁）。最新一天跟合計那天不一樣的檔（那天沒成交、還沒更新）在代號旁邊標日期，
 * 資料來自 HiStock 的（blave 沒有的上櫃股）標「HiStock」；這次沒抓到的原因（兩邊都沒有這一檔）在底下一行小字。
 */
@Composable
fun GovBankTable(data: GovBank, onOpen: (StockGovBank) -> Unit) {
    val total = data.watchlistTotal()
    Column(Modifier.fillMaxWidth()) {
        SectionCaption("自選股${total?.let { "・" + shortDate(it.date) } ?: ""}", "買進 − 賣出")
        TableBox {
            HeaderRow {
                HeaderCell("個股", 1.6f, TextAlign.Start)
                HeaderCell("張", 1f)
                HeaderCell("金額", 1.2f)
            }
            data.stocks.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                val d = s.latest
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(s) }.padding(horizontal = 10.dp, vertical = 8.dp).testTag("gov_bank_${s.stock.code}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1.6f)) {
                        Text(s.stock.name ?: s.stock.code, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(
                            s.stock.code.takeIf { s.stock.name != null },
                            d?.date?.takeIf { it != total?.date }?.let(::shortDate),
                            "HiStock".takeIf { s.via == GovBankVia.HISTOCK },
                        ).joinToString("・")
                        if (sub.isNotEmpty()) {
                            Text(sub, style = numStyle(MaterialTheme.typography.labelSmall), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        s.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary) }
                    }
                    Text(
                        if (d == null) "-" else fmtSigned(d.netLots),
                        Modifier.weight(1f),
                        style = numStyle(MaterialTheme.typography.bodyMedium),
                        color = changeColor(d?.netLots?.toDouble()),
                        textAlign = TextAlign.End,
                        maxLines = 1,
                    )
                    Text(
                        if (d == null) "-" else fmtAmount(d.netAmount),
                        Modifier.weight(1.2f),
                        style = numStyle(MaterialTheme.typography.bodyMedium).copy(fontWeight = FontWeight.SemiBold),
                        color = changeColor(d?.netAmount),
                        textAlign = TextAlign.End,
                        maxLines = 1,
                    )
                }
            }
            if (total != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 10.dp, vertical = 8.dp).testTag("gov_bank_total"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("合計（${total.count} 檔）", Modifier.weight(1.6f), style = MaterialTheme.typography.labelLarge)
                    Text(
                        fmtSigned(total.netLots),
                        Modifier.weight(1f),
                        style = numStyle(MaterialTheme.typography.bodyMedium),
                        color = changeColor(total.netLots.toDouble()),
                        textAlign = TextAlign.End,
                        maxLines = 1,
                    )
                    Text(
                        fmtAmount(total.netAmount),
                        Modifier.weight(1.2f),
                        style = numStyle(MaterialTheme.typography.bodyMedium).copy(fontWeight = FontWeight.SemiBold),
                        color = changeColor(total.netAmount),
                        textAlign = TextAlign.End,
                        maxLines = 1,
                    )
                }
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
