package com.twtrubiks.stockdesk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
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
 * 社群分頁：自選股每一檔一列，點一列就在內建的瀏覽器頁打開那一檔的討論區網頁。
 * 不抓 API、不用 token、不登入；網頁本身要登入才能做的事（發文、按讚）由網頁自己處理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForumScreen(onSettings: () -> Unit, onOpen: (url: String) -> Unit) {
    val c = LocalContext.current.container
    val stocks by produceState<List<StockRef>?>(null, c.watchlist) { value = withContext(Dispatchers.IO) { c.watchlist.all() } }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("社群討論區") },
            actions = { IconButton(onClick = onSettings, Modifier.testTag("settings_button")) { Icon(painterResource(R.drawable.ic_settings), contentDescription = "設定") } },
        )
        val list = stocks ?: return@Column
        if (list.isEmpty()) {
            Text("還沒有自選股。到「自選股」分頁加幾檔，這裡就會列出來。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
            items(list, key = { it.code }) { stock ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("forum_${stock.code}")
                        .clickable { onOpen(Links.forum(stock.code)) },
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stock.label, style = MaterialTheme.typography.titleMedium)
                            Text("個股討論區", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(painterResource(R.drawable.ic_forum), contentDescription = null)
                    }
                }
            }
        }
    }
}
