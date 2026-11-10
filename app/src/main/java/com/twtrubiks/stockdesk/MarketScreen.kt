package com.twtrubiks.stockdesk

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** 大盤分頁裡的三頁。順序就是切換鈕的順序；國際指數是預設。 */
enum class MarketPage(val label: String, val tag: String) {
    INDICES("國際指數", "market_indices"),
    WEIGHTS("權重", "market_weights"),
    PUBLIC_BANK("官股", "market_public_bank"),
}

/**
 * 大盤分頁：看整個市場的三頁，上面的切換鈕切換：國際指數（[IndicesPage]）、加權指數權重（[WeightsPage]）、
 * 八大官股買賣超（[PublicBankPage]，全市場與自選股）。選到哪一頁由 [StockDeskApp] 記著（切到別的分頁再回來、轉螢幕都還在）。
 * 標題列的重新整理三頁都有（抓的時候變成轉圈）。官股的抓取在 [FetchJobs]：別的抓取在跑時重新整理停用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(page: MarketPage, onPage: (MarketPage) -> Unit, onSettings: () -> Unit, onOpen: (url: String) -> Unit) {
    // 每一頁各自的「抓取中」：切頁時舊的那頁取消、新的那頁開始，兩邊的通知先後不一定，所以分開記
    val loading = remember { mutableStateMapOf<MarketPage, Boolean>() }
    val jobs = LocalContext.current.container.jobs
    // 按了幾次重新整理；換頁就歸零，進頁面時由那一頁自己決定要不要抓（權重一天一次、國際指數 5 分鐘）
    var refresh by remember(page) { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("大盤") },
            actions = {
                val spinning = if (page == MarketPage.PUBLIC_BANK) jobs.govBank is JobState.Running else loading[page] == true
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (spinning) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    } else {
                        val tag = when (page) {
                            MarketPage.INDICES -> "indices_refresh"
                            MarketPage.WEIGHTS -> "weights_refresh"
                            MarketPage.PUBLIC_BANK -> "gov_bank_refresh"
                        }
                        IconButton(onClick = { refresh++ }, enabled = page != MarketPage.PUBLIC_BANK || !jobs.busy, modifier = Modifier.testTag(tag)) {
                            Icon(painterResource(R.drawable.ic_refresh), contentDescription = "重新整理")
                        }
                    }
                }
                IconButton(onClick = onSettings, Modifier.testTag("settings_button")) { Icon(painterResource(R.drawable.ic_settings), contentDescription = "設定") }
            },
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            MarketPage.entries.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = page == p,
                    onClick = { onPage(p) },
                    shape = SegmentedButtonDefaults.itemShape(i, MarketPage.entries.size),
                    modifier = Modifier.testTag(p.tag),
                    icon = {},
                    label = { Text(p.label) },
                )
            }
        }
        when (page) {
            MarketPage.INDICES -> IndicesPage(refresh, onLoading = { loading[MarketPage.INDICES] = it }, onOpen = onOpen)
            MarketPage.WEIGHTS -> WeightsPage(refresh, onLoading = { loading[MarketPage.WEIGHTS] = it })
            MarketPage.PUBLIC_BANK -> PublicBankPage(refresh, onOpen)
        }
    }
}
