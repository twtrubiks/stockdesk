package com.twtrubiks.stockdesk

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/** 底部的四個分頁。順序就是畫面上的順序；goodinfo 是打開 App 的第一頁。 */
enum class Tab(val label: String, val tag: String, val icon: Int) {
    GOODINFO("goodinfo", "tab_goodinfo", R.drawable.ic_table),
    FORUM("社群", "tab_forum", R.drawable.ic_forum),
    // 國際指數、權重、官股三頁（MarketScreen 上面的切換鈕）
    MARKET("大盤", "tab_market", R.drawable.ic_market),
    WATCHLIST("自選股", "tab_watchlist", R.drawable.ic_list),
}

/**
 * 蓋在分頁上面的頁：內建瀏覽器（goodinfo、討論區、八大官股、Yahoo 國際指數）、設定（右上角齒輪）。只會有一層，不用 stack。
 * 轉螢幕要留著，所以是 `rememberSaveable` 的字串。
 */
sealed interface Overlay {
    val key: String

    /** 內建瀏覽器頁開 [url]。 */
    data class Browser(val url: String) : Overlay {
        override val key get() = "browser:$url"
    }

    data object Settings : Overlay {
        override val key = "settings"
    }

    companion object {
        fun parse(key: String?): Overlay? = when {
            key == null -> null
            key == "settings" -> Settings
            key.startsWith("browser:") -> Browser(key.removePrefix("browser:"))
            else -> null
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun StockDeskApp() {
    val container = LocalContext.current.container
    var tab by rememberSaveable { mutableStateOf(Tab.GOODINFO) }
    // 大盤分頁選到哪一頁放在這裡：切到別的分頁再回來還是同一頁
    var marketPage by rememberSaveable { mutableStateOf(MarketPage.INDICES) }
    var overlayKey by rememberSaveable { mutableStateOf<String?>(null) }
    val overlay = Overlay.parse(overlayKey)

    // 內建瀏覽器的引擎與 uBO 先在背景準備好，點進瀏覽器頁時不用再等引擎啟動、uBO 讀過濾清單
    LaunchedEffect(Unit) { container.warmUpBrowser() }

    // 關掉瀏覽器頁時把它的分頁（GeckoSession）也關掉；分頁放在 AppContainer，轉螢幕不會重新載入
    fun closeOverlay() {
        (overlay as? Overlay.Browser)?.let { container.closeTab(it.key) }
        overlayKey = null
    }

    StockDeskTheme(container.theme.value) {
        Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            if (overlay != null) {
                when (overlay) {
                    // 瀏覽器頁自己處理返回鍵（網頁還有上一頁就先回上一頁）
                    is Overlay.Browser -> BrowserScreen(tabKey = overlay.key, url = overlay.url, onClose = ::closeOverlay)
                    Overlay.Settings -> {
                        BackHandler(onBack = ::closeOverlay)
                        SettingsScreen(onBack = ::closeOverlay)
                    }
                }
                return@Surface
            }
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(painterResource(t.icon), contentDescription = null) },
                                label = { Text(t.label) },
                                modifier = Modifier.testTag(t.tag),
                            )
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.padding(padding)) {
                    val openSettings = { overlayKey = Overlay.Settings.key }
                    val openUrl = { url: String -> overlayKey = Overlay.Browser(url).key }
                    when (tab) {
                        Tab.GOODINFO -> GoodinfoScreen(onSettings = openSettings, onOpen = openUrl)
                        Tab.FORUM -> ForumScreen(onSettings = openSettings, onOpen = openUrl)
                        Tab.MARKET -> MarketScreen(page = marketPage, onPage = { marketPage = it }, onSettings = openSettings, onOpen = openUrl)
                        Tab.WATCHLIST -> WatchlistScreen(onSettings = openSettings)
                    }
                }
            }
        }
    }
}
