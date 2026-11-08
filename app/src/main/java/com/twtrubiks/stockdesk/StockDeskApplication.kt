package com.twtrubiks.stockdesk

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Looper
import android.util.Log
import com.twtrubiks.stockdesk.core.holders.HoldersSource
import com.twtrubiks.stockdesk.core.http.Http
import com.twtrubiks.stockdesk.core.http.OkHttp
import com.twtrubiks.stockdesk.core.indices.IndicesSource
import com.twtrubiks.stockdesk.core.margin.MarginSource
import com.twtrubiks.stockdesk.core.names.StockNames
import com.twtrubiks.stockdesk.core.store.CacheStore
import com.twtrubiks.stockdesk.core.store.WatchlistStore
import com.twtrubiks.stockdesk.core.weights.WeightsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import kotlin.coroutines.resume

const val TAG = "StockDesk"

/**
 * 啟動時什麼都不做：不碰網路、不開資料庫（第一次用到才建）。
 * GeckoView 另外開的網頁行程也會跑這個 Application，所以 `onCreate` 裡不能碰 [container]。
 */
class StockDeskApplication : Application() {
    private var testEndpoints: Endpoints? = null
    private val lazyContainer = lazy { AppContainer(this, testEndpoints ?: Endpoints()) }
    val container: AppContainer by lazyContainer

    /**
     * 只給模擬器上的測試用（`DemoRecordingTest`）：在第一次用到 [container] 之前，把資料來源換成 MockWebServer 的網址。
     * 測試跟 App 在同一個 process，`am instrument` 每次都是新的 process，所以換得到。正式執行從來不呼叫。
     */
    fun useEndpointsForTest(endpoints: Endpoints) {
        check(!lazyContainer.isInitialized()) { "AppContainer 已經建好了：要在第一次用到之前換" }
        testEndpoints = endpoints
    }
}

val Context.container: AppContainer get() = (applicationContext as StockDeskApplication).container

/**
 * 整個 App 共用的東西。資料庫、網路一律在背景執行緒，不在組畫面時讀資料庫。
 * 資料來源的 base URL 可以換（`Endpoints`）：模擬器上的 DemoRecordingTest 用 MockWebServer 假裝官方來源。
 */
class AppContainer(private val context: Context, val endpoints: Endpoints = Endpoints()) {
    private val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 深色模式。 */
    val theme = EnumSetting(settings, "theme", ThemeMode.entries, ThemeMode.SYSTEM)

    /** 融資融券預設看幾天、持股分級看幾週。 */
    val marginDays = IntSetting(settings, "margin_days", 3)
    val holdersWeeks = IntSetting(settings, "holders_weeks", 2)

    /** goodinfo 分頁收起來的卡片（代號）。記住到下次打開 App；新加的自選股不在裡面，所以是展開的。 */
    val goodinfoCollapsed = StringSetSetting(settings, "goodinfo_collapsed")

    val names: StockNames by lazy { StockNames.load() }

    private val sql by lazy { AndroidSql(context) }
    val watchlist: WatchlistStore by lazy { WatchlistStore(sql, names) }
    val cache: CacheStore by lazy { CacheStore(sql) }

    /** 整個 App 一個 OkHttpClient，第一次要抓資料時才建。 */
    val http: Http by lazy { OkHttp(OkHttpClient()) }

    val margin by lazy { MarginSource(http, cache, twseBase = endpoints.twse, tpexBase = endpoints.tpex) }
    val holders by lazy { HoldersSource(http, cache, base = endpoints.tdcc) }
    val weights by lazy { WeightsSource(http, cache, url = endpoints.taifex) }
    val indices by lazy { IndicesSource(http, cache, url = endpoints.yahoo) }

    /** 抓取都在這個 scope 跑，不綁在畫面上：切到別的分頁、轉螢幕都不會中斷。裡面 `launch` 的東西例外一定要接住。 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val jobs by lazy { FetchJobs(this) }

    /**
     * 內建瀏覽器的引擎，一個行程只能有一個，第一次打開瀏覽器時才建立（要在主執行緒）。
     * 網頁的深淺（`prefers-color-scheme`）跟著 App 的深色模式，切換時馬上換。
     */
    val gecko: GeckoRuntime by lazy {
        val runtimeSettings = GeckoRuntimeSettings.Builder().preferredColorScheme(theme.value.gecko).build()
        GeckoRuntime.create(context, runtimeSettings).also { runtime ->
            theme.onChange = { runtime.settings.preferredColorScheme = it.gecko }
        }
    }

    private val lazyUblock = lazy { Ublock(gecko, context.getSharedPreferences("browser", Context.MODE_PRIVATE)) }

    /** uBlock Origin（內建瀏覽器的擋廣告）。第一次用到會把 [gecko] 一起建起來。 */
    val ublock: Ublock by lazyUblock

    /** 已經建好的 [ublock]，還沒建（還沒打開過瀏覽器）就是 null：設定頁顯示狀態用，不要為了顯示把引擎開起來。 */
    val ublockOrNull: Ublock? get() = if (lazyUblock.isInitialized()) ublock else null

    /**
     * 先把瀏覽器的引擎開起來、uBO 準備好（第一次會從 AMO 安裝），點進瀏覽器頁時不用再等好幾秒。
     * 等畫面出來 2 秒、主執行緒也閒下來才做，不拖慢 App 啟動；代價是 App 開著時多佔一些記憶體，記憶體很少的手機不做。
     */
    suspend fun warmUpBrowser() {
        if (context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true) return
        delay(2_000)
        suspendCancellableCoroutine { cont ->
            Looper.myQueue().addIdleHandler {
                if (cont.isActive) cont.resume(Unit)
                false
            }
        }
        Log.i(TAG, "先把內建瀏覽器的引擎開起來")
        ublock.start()
    }

    /** 開著的瀏覽器分頁，key 是蓋頁的 key（一次只有一層蓋頁，所以最多一個）；關掉那一頁時 [closeTab]。 */
    private val tabs = mutableMapOf<String, BrowserTab>()

    fun tab(key: String, initialUrl: () -> String?): BrowserTab = tabs.getOrPut(key) { BrowserTab(context, gecko, ublock, initialUrl()) }

    fun closeTab(key: String) {
        tabs.remove(key)?.close()
    }
}

private val ThemeMode.gecko: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
        ThemeMode.LIGHT -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
        ThemeMode.DARK -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
    }

/** 資料來源的網址，測試時換成 MockWebServer 的。 */
data class Endpoints(
    val twse: String = "https://www.twse.com.tw",
    val tpex: String = "https://www.tpex.org.tw",
    val tdcc: String = "https://www.tdcc.com.tw/portal/zh/smWeb/qryStock",
    val taifex: String = "https://www.taifex.com.tw/cht/9/futuresQADetail",
    val yahoo: String = "https://query1.finance.yahoo.com/v7/finance/spark",
)
