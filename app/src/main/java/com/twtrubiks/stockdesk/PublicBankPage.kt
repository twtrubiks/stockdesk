package com.twtrubiks.stockdesk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.twtrubiks.stockdesk.core.govbank.GovBank
import com.twtrubiks.stockdesk.core.govbank.GovBankSource
import com.twtrubiks.stockdesk.core.govbank.GovBankVia
import com.twtrubiks.stockdesk.core.govbank.StockGovBank
import com.twtrubiks.stockdesk.core.links.Links
import com.twtrubiks.stockdesk.core.links.PublicBankMarket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 大盤分頁的「官股」：八大官股（八家公股銀行旗下的券商：臺銀、土銀、合庫、第一、華南、彰銀、兆豐、台企銀）的買賣超。
 *
 * - 全市場（HiStock）：最新一天是買超還是賣超、多少；前幾天的總額；買超、賣超前 5 名（[GovBankMarketBox]）
 * - 自選股（blave；blave 沒有的上櫃股是 HiStock 的個股頁）：每一檔最新一天的張數與金額、自選股合計（[GovBankTable]）。
 *   點一檔在內建瀏覽器頁開那一檔的網頁（資料從哪一家來就開哪一家，[govBankLink]）
 * - 最底下：網頁版（HiStock、玩股網的三個市場範圍），在內建瀏覽器頁開
 *
 * 進頁面時：該公布的那天抓過就只讀快取（[FetchJobs.fetchGovBank]，force = false）；大盤標題列的重新整理（[refresh] 加一）全部重抓。
 * 抓取在 [FetchJobs] 裡跑：切到別的分頁不會中斷，一次只跑一個（別的抓取在跑時進頁面不抓，只顯示快取）。
 * 兩個來源都不是官方的（見 docs/data-sources.md）。
 */
@Composable
fun PublicBankPage(refresh: Int, onOpen: (url: String) -> Unit) {
    val c = LocalContext.current.container
    val jobs = c.jobs
    val state = jobs.govBank
    val result = jobs.govBankResult

    LaunchedEffect(refresh) { jobs.fetchGovBank(force = refresh > 0) }

    // 要顯示的：這次抓的結果（自選股跟抓的時候一樣的話），不然是快取裡的（還沒抓完、別的抓取佔著、自選股剛改過）
    val shown by produceState<GovBank?>(null, result) {
        value = withContext(Dispatchers.IO) {
            val stocks = c.watchlist.all()
            if (result != null && result.stocks.map { it.stock.code } == stocks.map { it.code }) result
            else GovBankSource.cached(c.cache, stocks)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        when (state) {
            is JobState.Running -> JobProgress(state)
            is JobState.Failed -> ErrorBox(state.message)
            else -> Unit
        }
        shown?.let { data ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "每個交易日晚上約 6 點半更新",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                data.fetchedAt?.let {
                    Text("${it.drop(5).replace('-', '/')} 抓取", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (data.cached && data.fetchedAt != null) {
                    Spacer(Modifier.width(6.dp))
                    Tag("快取")
                }
            }
            data.warning?.let { Note(it) }
            data.market?.let { GovBankMarketBox(it) }
            data.marketNote?.let { if (data.market == null) ErrorBox(it) else Note(it) }
            Spacer(Modifier.size(8.dp))
            if (data.stocks.isEmpty()) {
                Text(
                    "還沒有自選股。到「自選股」分頁加幾檔，這裡就會列出八大官股在每一檔的買賣超。",
                    Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                GovBankTable(data) { s -> onOpen(govBankLink(s)) }
            }
        }

        Text(
            "網頁版（在 App 內開）",
            Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WebLink("HiStock", "八大官股買賣超排名", "gov_bank_histock") { onOpen(Links.HISTOCK_GOV_BANK) }
        PublicBankMarket.entries.forEach { market ->
            WebLink("玩股網・${market.label}", "八大官股每日明細", "public_bank_${market.name.lowercase()}") { onOpen(Links.publicBank(market)) }
        }
        Text(
            "數字是八家公股券商所有分點的買進減賣出（張數與金額的正負可能不一樣，買超賣超看金額）。" +
                "個股來自 blave（blave 只有上市股票，上櫃股改用 HiStock 的個股頁，標「HiStock」）、全市場來自 HiStock，都不是官方資料。" +
                "點自選股的一檔開那一檔的網頁（blave 或 HiStock）。",
            Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 自選股的一檔點下去開的網頁：資料從哪一家來就開哪一家（上櫃股 blave 的網頁是空的）。 */
fun govBankLink(s: StockGovBank): String = when (s.via) {
    GovBankVia.BLAVE -> Links.blaveGovBank(s.stock.code)
    GovBankVia.HISTOCK -> Links.histockGovBank(s.stock.code)
}

/** 黃字提示（重抓失敗但有舊資料、抓到一半停下來）。 */
@Composable
private fun Note(text: String) {
    Text(text, Modifier.padding(horizontal = 4.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
}

/** 網頁版的一列：點了在內建瀏覽器頁開。 */
@Composable
private fun WebLink(title: String, subtitle: String, tag: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).testTag(tag).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null)
        }
    }
}
