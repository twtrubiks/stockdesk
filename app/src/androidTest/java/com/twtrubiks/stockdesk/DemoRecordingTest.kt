package com.twtrubiks.stockdesk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 錄影用的展示流程，CI 只錄這個 class，`scripts/ci/record.sh` 會在執行期間錄下整個螢幕。
 * 不連外：資料來源換成模擬器上的 [FakeSources]（MockWebServer，回 [com.twtrubiks.stockdesk.core.Samples] 造的假回應）。
 * 走自選股（加兩檔）→ goodinfo（2330 的融資融券與持股分級按「抓」，全部收合再點 2330 展開）→ 社群（點一檔開內建瀏覽器頁，沒網路只看得到標題列與錯誤頁，再關掉）
 * → 大盤（國際指數 → 權重（進頁面自動抓）→ 官股，點一列開內建瀏覽器頁再關掉）→ 設定。全長控制在 150 秒內。
 */
@RunWith(AndroidJUnit4::class)
class DemoRecordingTest : UiTest() {
    private val fake = FakeSources()

    @Before
    fun useFakeSources() {
        // 要在第一次用到 container 之前換：之後 App 的「抓」都打到模擬器上的 MockWebServer
        app.useEndpointsForTest(fake.start())
    }

    @After
    fun stopFakeSources() {
        println("FakeSources 收到的請求：${fake.requests}（集保回空表 ${fake.tdccRejected} 次）")
        fake.close()
    }

    @Test
    fun demo() {
        container.watchlist.all().forEach { container.watchlist.remove(it.code) }
        container.goodinfoCollapsed.set(emptySet())
        launchApp()
        waitFor(res("tab_goodinfo"), timeout = 30_000)
        pause(1500)
        screenshot("01-goodinfo-empty")

        find(res("tab_watchlist")).click()
        waitFor(res("code_input"))
        pause(1000)
        find(res("code_input")).text = "2330"
        find(res("add_button")).click()
        pause(1200)
        find(res("code_input")).text = "6515"
        find(res("add_button")).click()
        pause(1200)
        hideKeyboard()
        screenshot("02-watchlist")

        find(res("tab_goodinfo")).click()
        waitFor(By.text("台積電"))
        pause(1500)
        screenshot("03-goodinfo")

        // 融資融券：假的證交所，近 3 個交易日（每發隔 2 秒以上，週末多打兩天休市）
        find(res("fetch_margin_2330")).click()
        waitFor(By.text("融資餘額"), timeout = 40_000)
        pause(1500)
        screenshot("04-margin")

        // 股東持股分級：假的集保（token 綁 session cookie，沒帶 cookie 就回空表），查詢頁 ＋ 近 2 週
        find(res("fetch_holders_2330").enabled(true)).click()
        waitFor(By.text("各級持股比例（%）"), timeout = 30_000)
        pause(2000)
        screenshot("05-holders")

        // 全部收合：只剩代號名稱；點 2330 那列再展開，剛剛抓的結果還在
        find(res("collapse_all")).click()
        waitGone(res("fetch_margin_2330"))
        pause(1500)
        screenshot("06-goodinfo-collapsed")
        find(res("card_header_2330")).click()
        waitFor(By.text("融資餘額"))
        pause(1500)

        find(res("tab_forum")).click()
        waitFor(res("forum_2330"))
        pause(1500)
        screenshot("07-forum")
        find(res("forum_2330")).click()
        waitFor(res("browser_close"))
        pause(2000)
        screenshot("08-browser")
        find(res("browser_close")).click()
        waitFor(res("tab_forum"))
        pause(1000)

        // 大盤：預設是國際指數（假的 Yahoo，8 個指數，台股是盤中），再切到權重（今天沒抓過，進頁面就抓）、官股（只開網頁）
        find(res("tab_market")).click()
        waitFor(res("index_TWII"), timeout = 30_000)
        waitFor(res("index_DJI"))
        pause(2000)
        screenshot("09-indices")
        find(res("market_weights")).click()
        waitFor(By.text("台積電"), timeout = 30_000)
        pause(2000)
        screenshot("10-weights")
        find(res("market_public_bank")).click()
        waitFor(res("public_bank_all"))
        pause(1500)
        screenshot("11-public-bank")
        find(res("public_bank_all")).click()
        waitFor(res("browser_close"))
        pause(1500)
        find(res("browser_close")).click()
        // 關掉瀏覽器回來還是官股那一頁
        waitFor(res("public_bank_all"))

        find(res("settings_button")).click()
        waitFor(By.text("設定"))
        pause(1500)
        screenshot("12-settings")
        device.pressBack()
        waitFor(res("tab_goodinfo"))
        pause(1000)
    }
}
