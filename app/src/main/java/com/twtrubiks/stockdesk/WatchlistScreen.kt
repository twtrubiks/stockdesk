package com.twtrubiks.stockdesk

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.twtrubiks.stockdesk.core.govbank.GovBankDay
import com.twtrubiks.stockdesk.core.govbank.GovBankSource
import com.twtrubiks.stockdesk.core.model.StockCode
import com.twtrubiks.stockdesk.core.model.StockRef
import com.twtrubiks.stockdesk.core.store.DuplicateStockException
import com.twtrubiks.stockdesk.core.store.InvalidStockCodeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自選股分頁：加（一個輸入框，打代號前幾碼或名稱的字，底下列候選，點一筆就加入；直接打完整代號按「加入」也行）、
 * 點名稱改名（對照表查不到的新股在這裡自己填）、刪、上下移、匯出／匯入 JSON（系統的檔案畫面）。
 * goodinfo、社群兩個分頁都照這份清單列。清單一改，另外兩個分頁下次顯示時重讀。
 * 每一列名稱底下一行是八大官股最新一天的買賣超（快取裡有才顯示；在大盤的「官股」頁抓，抓完這裡跟著重讀）。
 *
 * 候選清單直接排在輸入框下面、不用彈出層：彈出層會搶走輸入框的焦點把鍵盤收掉，也會擋住旁邊的「加入」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(onSettings: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var stocks by remember { mutableStateOf<List<StockRef>?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<StockRef?>(null) }

    suspend fun reload() { stocks = withContext(Dispatchers.IO) { c.watchlist.all() } }
    androidx.compose.runtime.LaunchedEffect(Unit) { reload() }

    // 八大官股最新一天（只讀快取）：清單變了、官股頁抓完了就重讀
    var gov by remember { mutableStateOf<Map<String, GovBankDay>>(emptyMap()) }
    val govResult = c.jobs.govBankResult
    androidx.compose.runtime.LaunchedEffect(stocks, govResult) {
        val codes = stocks?.map { it.code } ?: return@LaunchedEffect
        gov = withContext(Dispatchers.IO) { GovBankSource.latest(c.cache, codes) }
    }

    // 候選：對照表在記憶體裡（約 3,100 檔），每敲一個字掃一遍是毫秒級的事；已經在清單裡的不列
    // 輸入是空的就不碰 c.names：它第一次用到才讀打包的 JSON，讓 reload()（IO）那邊先載
    val candidates = remember(input, stocks) {
        if (input.isBlank()) emptyList() else {
            val existing = stocks?.map { it.code }?.toSet() ?: emptySet()
            c.names.search(input).filter { it.code !in existing }
        }
    }

    fun add(code: String, name: String? = null) = scope.launch {
        message = try {
            val added = withContext(Dispatchers.IO) { c.watchlist.add(code, name) }
            input = ""
            reload()
            "已加入 ${added.label}"
        } catch (e: InvalidStockCodeException) {
            e.message
        } catch (e: DuplicateStockException) {
            e.message
        }
    }

    /** 按「加入」：輸入本身是代號就直接加（對照表沒有的新股也加得進去）；不是代號但候選只剩一筆就加那筆。 */
    fun addFromInput() {
        when {
            StockCode.isValid(input) -> add(input)
            candidates.size == 1 -> add(candidates[0].code, candidates[0].name)
            candidates.isEmpty() -> message = "找不到「${input.trim()}」，請輸入 4～6 碼的代號"
            else -> message = "請從底下的候選點一檔，或輸入完整代號"
        }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val text = c.watchlist.export()
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                    "匯入了 ${c.watchlist.import(text)} 檔"
                }.getOrElse { "這不是這個 App 匯出的檔案" }
            }
            reload()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("自選股") },
            actions = {
                TextButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("匯入") }
                TextButton(onClick = { exporter.launch("stockdesk-watchlist.json") }) { Text("匯出") }
                IconButton(onClick = onSettings, Modifier.testTag("settings_button")) { Icon(painterResource(R.drawable.ic_settings), contentDescription = "設定") }
            },
        )
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                input, { input = it; message = null }, Modifier.weight(1f).testTag("code_input"),
                label = { Text("代號或名稱") }, placeholder = { Text("23、2330、台積") }, singleLine = true,
            )
            Button(onClick = { addFromInput() }, enabled = input.isNotBlank(), modifier = Modifier.padding(top = 8.dp).testTag("add_button")) { Text("加入") }
        }
        if (candidates.isNotEmpty()) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("candidates")) {
                candidates.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider()
                    Text(
                        s.label,
                        Modifier.fillMaxWidth().clickable { add(s.code, s.name) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        message?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall) }
        val list = stocks ?: return@Column
        if (list.isEmpty()) Text("清單是空的。打代號前幾碼或名稱的字選一檔加入，或用右上角「匯入」讀回之前匯出的檔案。", Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.code }) { s ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val day = gov[s.code]
                    Column(Modifier.weight(1f).clickable { renaming = s }.padding(vertical = if (day == null) 12.dp else 6.dp)) {
                        Text(s.label, style = MaterialTheme.typography.bodyLarge)
                        if (day != null) {
                            Text(
                                govBankLine(day),
                                Modifier.testTag("gov_line_${s.code}"),
                                style = numStyle(MaterialTheme.typography.bodySmall),
                                color = changeColor(day.netAmount),
                            )
                        }
                    }
                    IconButton(onClick = { scope.launch { withContext(Dispatchers.IO) { c.watchlist.move(s.code, -1) }; reload() } }) { Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = "上移") }
                    IconButton(onClick = { scope.launch { withContext(Dispatchers.IO) { c.watchlist.move(s.code, +1) }; reload() } }) { Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = "下移") }
                    IconButton(onClick = { scope.launch { withContext(Dispatchers.IO) { c.watchlist.remove(s.code) }; reload() } }) { Icon(painterResource(R.drawable.ic_delete), contentDescription = "刪除") }
                }
            }
        }
    }

    renaming?.let { target ->
        RenameDialog(
            target,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                scope.launch { withContext(Dispatchers.IO) { c.watchlist.rename(target.code, name) }; reload() }
            },
        )
    }
}

/** 改名稱：清空就只顯示代號。對照表查不到名稱的新股在這裡自己填。 */
@Composable
private fun RenameDialog(target: StockRef, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var name by rememberSaveable(target.code) { mutableStateOf(target.name ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${target.code} 的名稱") },
        text = {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("rename_input"), label = { Text("名稱（清空只顯示代號）") }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.ifBlank { null }) }, Modifier.testTag("rename_confirm")) { Text("儲存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
