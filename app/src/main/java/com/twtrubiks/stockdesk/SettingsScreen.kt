package com.twtrubiks.stockdesk

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 設定：深色模式；融資融券天數（3／5／10）與要不要顯示資使用率、券資比、持股分級週數（2／4／6）；內建瀏覽器的 uBlock Origin 狀態（只顯示，不能關）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val c = LocalContext.current.container

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("設定") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "返回") } },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("深色模式", style = MaterialTheme.typography.titleSmall)
            Choices(ThemeMode.entries.map { it.label to (c.theme.value == it) }) { c.theme.set(ThemeMode.entries[it]) }

            Text("融資融券：交易日數", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleSmall)
            val days = listOf(3, 5, 10)
            Choices(days.map { "$it 天" to (c.marginDays.value == it) }) { c.marginDays.set(days[it]) }
            Row(
                Modifier.fillMaxWidth().toggleable(c.marginRatios.value, role = Role.Checkbox) { c.marginRatios.set(it) }.testTag("margin_ratios"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = c.marginRatios.value, onCheckedChange = null, Modifier.padding(vertical = 12.dp))
                Text("顯示資使用率、券資比（表格變寬，左右拖著看）", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
            }

            Text("股東持股分級：週數", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleSmall)
            val weeks = listOf(2, 4, 6)
            Choices(weeks.map { "$it 週" to (c.holdersWeeks.value == it) }) { c.holdersWeeks.set(weeks[it]) }

            // uBO 的版本與上次檢查更新的時間：看得到它有沒有自己更新（一天最多問一次 AMO）。
            // 只讀已經建好的，不為了顯示把瀏覽器引擎開起來
            Text("內建瀏覽器的擋廣告", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleSmall)
            val ublock = c.ublockOrNull
            val checkedAt = ublock?.checkedAt?.takeIf { it > 0 }
            Text(
                when {
                    ublock == null -> "uBlock Origin：瀏覽器還沒啟動"
                    ublock.version == null -> "uBlock Origin 還沒裝好（第一次要從 Mozilla 附加元件網站下載）"
                    else -> "uBlock Origin ${ublock.version}"
                },
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            ublock?.pendingVersion?.let {
                Text("$it 已經下載好，下次打開 App 時換上", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                checkedAt?.let { "上次檢查更新：${CHECKED_AT_FORMAT.format(Instant.ofEpochMilli(it))}" } ?: "還沒檢查過更新",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val CHECKED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

@Composable
private fun Choices(items: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { i, (label, on) -> FilterChip(selected = on, onClick = { onPick(i) }, label = { Text(label) }) }
    }
}
