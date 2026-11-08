package com.twtrubiks.stockdesk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.twtrubiks.stockdesk.core.links.Links
import com.twtrubiks.stockdesk.core.links.PublicBankMarket

/**
 * 大盤分頁的「官股」：八大官股券商（台銀、土銀、合庫、第一金、華南永昌、彰銀、兆豐銀、台企銀）每日買賣超的明細表。
 * 表格是玩股網的網頁，一個市場範圍一列，點一列在內建瀏覽器頁開；跟社群一樣只開網頁、不抓、不登入。
 * 這是全市場的統計，跟自選股無關。
 */
@Composable
fun PublicBankPage(onOpen: (url: String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(
            "八大官股券商每日買賣超的明細（玩股網的網頁，在 App 內開）。選市場範圍：",
            Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        PublicBankMarket.entries.forEach { market ->
            Card(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("public_bank_${market.name.lowercase()}")
                    .clickable { onOpen(Links.publicBank(market)) },
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(market.label, style = MaterialTheme.typography.titleMedium)
                        Text("八大官股每日明細", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null)
                }
            }
        }
    }
}
