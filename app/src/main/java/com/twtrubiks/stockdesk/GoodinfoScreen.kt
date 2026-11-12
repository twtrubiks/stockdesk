package com.twtrubiks.stockdesk

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.twtrubiks.stockdesk.core.links.Links
import com.twtrubiks.stockdesk.core.model.StockRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * goodinfo 分頁：自選股每一檔一張卡，兩列（融資融券、股東持股分級），每列兩顆按鈕：
 * 「goodinfo」在內建的瀏覽器頁開 goodinfo 對應頁（代號自動帶入）、「抓」向官方來源抓原始資料、結果展開在那檔底下。
 * 天數／週數在設定頁選（3／5／10 天、2／4／6 週）。一次只跑一個抓取，跑的時候所有「抓」都停用。
 * 標題列的「全部抓取」照順序把每一檔的兩種資料都抓一遍（[FetchJobs.fetchAll]），進度與「取消」在標題列底下。
 * 點卡片的代號名稱那一列收合／展開，標題列有「全部展開」「全部收合」；收起來的代號記在 [AppContainer.goodinfoCollapsed]，
 * 下次打開 App 一樣。收起來時抓到的結果還在（[FetchJobs] 裡），展開就看得到；收著的那檔正在抓時，代號旁邊轉圈。
 * 空清單時提示到「自選股」分頁加。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoodinfoScreen(onSettings: () -> Unit, onOpen: (url: String) -> Unit) {
    val c = LocalContext.current.container
    val jobs = c.jobs
    // 讀資料庫在 IO，不在組畫面時讀
    val stocks by produceState<List<StockRef>?>(null, c.watchlist) { value = withContext(Dispatchers.IO) { c.watchlist.all() } }
    val codes = stocks.orEmpty().map { it.code }
    val collapsed = c.goodinfoCollapsed.value

    // 已經不在自選股裡的代號順手清掉，不然刪掉再加回來還會是收著的
    fun setCollapsed(next: Set<String>) = c.goodinfoCollapsed.set(next.intersect(codes.toSet()))

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("goodinfo 快速連結", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            actions = {
                if (codes.isNotEmpty()) {
                    IconButton(onClick = { jobs.fetchAll() }, enabled = !jobs.busy, modifier = Modifier.testTag("fetch_all")) {
                        Icon(painterResource(R.drawable.ic_download), contentDescription = "全部抓取")
                    }
                    IconButton(onClick = { setCollapsed(emptySet()) }, enabled = codes.any { it in collapsed }, modifier = Modifier.testTag("expand_all")) {
                        Icon(painterResource(R.drawable.ic_unfold_more), contentDescription = "全部展開")
                    }
                    IconButton(onClick = { setCollapsed(codes.toSet()) }, enabled = codes.any { it !in collapsed }, modifier = Modifier.testTag("collapse_all")) {
                        Icon(painterResource(R.drawable.ic_unfold_less), contentDescription = "全部收合")
                    }
                }
                IconButton(onClick = onSettings, Modifier.testTag("settings_button")) { Icon(painterResource(R.drawable.ic_settings), contentDescription = "設定") }
            },
        )
        val list = stocks ?: return@Column
        if (list.isEmpty()) {
            Text("還沒有自選股。到「自選股」分頁加幾檔，這裡就會列出來。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        when (val all = jobs.all) {
            is JobState.Running -> Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                JobProgress(all.copy(message = "全部抓取 ${all.message}"), Modifier.weight(1f).testTag("fetch_all_progress"))
                TextButton(onClick = { jobs.cancel() }, modifier = Modifier.testTag("fetch_all_cancel")) { Text("取消") }
            }
            is JobState.Failed -> ErrorBox("全部抓取：${all.message}", Modifier.padding(horizontal = 12.dp))
            else -> Unit
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
            items(list, key = { it.code }) { stock ->
                val expanded = stock.code !in collapsed
                val margin = jobs.margin[stock.code] ?: JobState.Idle
                val holders = jobs.holders[stock.code] ?: JobState.Idle
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    StockHeader(
                        stock = stock,
                        expanded = expanded,
                        running = margin is JobState.Running || holders is JobState.Running,
                        onToggle = { setCollapsed(if (expanded) collapsed + stock.code else collapsed - stock.code) },
                    )
                    AnimatedVisibility(visible = expanded) {
                        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                            ToolRow(
                                label = "融資融券",
                                tag = "fetch_margin_${stock.code}",
                                busy = jobs.busy,
                                onOpen = { onOpen(Links.goodinfoMargin(stock.code)) },
                                onFetch = { jobs.fetchMargin(stock.code) },
                            )
                            when (margin) {
                                is JobState.Running -> JobProgress(margin)
                                is JobState.Failed -> ErrorBox(margin.message)
                                is JobState.Ready -> MarginTable(margin.value)
                                JobState.Idle -> Unit
                            }
                            HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            ToolRow(
                                label = "股東持股分級",
                                tag = "fetch_holders_${stock.code}",
                                busy = jobs.busy,
                                onOpen = { onOpen(Links.goodinfoHolders(stock.code)) },
                                onFetch = { jobs.fetchHolders(stock.code) },
                            )
                            when (holders) {
                                is JobState.Running -> JobProgress(holders)
                                is JobState.Failed -> ErrorBox(holders.message)
                                is JobState.Ready -> HoldersTable(holders.value)
                                JobState.Idle -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 卡片最上面的代號名稱：整列點下去收合／展開，右邊的箭頭跟著轉；收著的那檔正在抓時轉圈。 */
@Composable
private fun StockHeader(stock: StockRef, expanded: Boolean, running: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClickLabel = if (expanded) "收合" else "展開", onClick = onToggle)
            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
            .heightIn(min = 40.dp)
            .testTag("card_header_${stock.code}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stock.code, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.width(8.dp))
        Text(
            stock.name.orEmpty(),
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (running && !expanded) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            painterResource(R.drawable.ic_arrow_down),
            contentDescription = null,
            Modifier.padding(8.dp).rotate(rotation),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 一個區塊的標題列：名稱、「goodinfo」（內建瀏覽器開 goodinfo 對應頁）、「抓」（向官方來源抓）。 */
@Composable
private fun ToolRow(label: String, tag: String, busy: Boolean, onOpen: () -> Unit, onFetch: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(36.dp)) {
            Text("goodinfo", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
            Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, Modifier.size(16.dp))
        }
        Button(onClick = onFetch, enabled = !busy, contentPadding = PaddingValues(horizontal = 18.dp), modifier = Modifier.height(36.dp).testTag(tag)) {
            Text("抓", style = MaterialTheme.typography.labelLarge)
        }
    }
}
