package com.twtrubiks.stockdesk.core

import com.twtrubiks.stockdesk.core.holders.HoldersEvent
import com.twtrubiks.stockdesk.core.holders.HoldersSource
import com.twtrubiks.stockdesk.core.http.HttpResponse
import com.twtrubiks.stockdesk.core.http.Pacer
import com.twtrubiks.stockdesk.core.store.CacheStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/**
 * 股東持股分級的抓取流程（不連網）：假的集保照真的規則發 token（每發換新、用舊的就回空表），
 * 快取是真的 SQLite，節流走 runTest 的虛擬時間。
 */
class HoldersSourceTest {
    private val weeks = listOf("20261106", "20261030", "20261023", "20261016", "20261009")
    private val cache = CacheStore(JdbcSql())
    private var today = LocalDate.of(2026, 11, 10)

    /** 假的集保：只認最新發出去的那個 token。 */
    private inner class Tdcc {
        var current = ""
        private var issued = 0
        var available = weeks

        /** (代號, 週) → 15 級；沒有的就是「查無此資料」。 */
        val data = mutableMapOf<Pair<String, String>, List<Samples.Level>>()

        fun issue(): String {
            issued++
            current = "token-$issued"
            return current
        }

        fun install(http: FakeHttp) {
            http.on({ it.method == "GET" && "qryStock" in it.url }) { r -> HttpResponse(200, Samples.tdccForm(issue(), available), r.url) }
            http.on({ it.method == "POST" && "qryStock" in it.url }) { r -> post(r) }
        }

        fun post(r: FakeHttp.Request): HttpResponse {
            val valid = r.form["SYNCHRONIZER_TOKEN"] == current
            val next = issue()
            val ymd = r.form.getValue("scaDate")
            val levels = data[r.form.getValue("stockNo") to ymd]
            val body = when {
                !valid -> Samples.tdccExpired(next, available)
                levels == null -> Samples.tdccNotFound(next, available)
                else -> Samples.tdccWeek(next, available, ymd, levels)
            }
            return HttpResponse(200, body, r.url)
        }
    }

    private val tdcc = Tdcc().apply {
        // 2330：每週大戶比例差 0.5
        weeks.forEachIndexed { i, ymd -> data["2330" to ymd] = Samples.tdccLevels(bigPct = 72.0 - i * 0.5) }
    }

    private fun TestScope.http() = FakeHttp { testScheduler.currentTime }.also { tdcc.install(it) }

    private fun TestScope.source(http: FakeHttp) = HoldersSource(
        http,
        cache,
        pacer = Pacer(Pacer.TDCC_MS, now = { testScheduler.currentTime }),
        base = "https://tdcc.test/portal/zh/smWeb/qryStock",
        today = { today },
    )

    private suspend fun HoldersSource.fetch(code: String, n: Int = 3) = history(code, n).toList()

    private fun List<HoldersEvent>.done() = last() as? HoldersEvent.Done ?: throw AssertionError("最後一個不是 Done：${last()}")

    private fun List<HoldersEvent>.failed() = last() as? HoldersEvent.Failed ?: throw AssertionError("最後一個不是 Failed：${last()}")

    private fun FakeHttp.posts() = requests.filter { it.method == "POST" }

    private fun FakeHttp.gets() = requests.filter { it.method == "GET" }

    @Test
    fun newestFirstWithChainedTokens() = runTest {
        val http = http()
        val events = source(http).fetch("2330", 3)

        val done = events.done()
        assertEquals(listOf("2026-11-06", "2026-10-30", "2026-10-23"), done.weeks.map { it.date })
        assertEquals(listOf(72.0, 71.5, 71.0), done.weeks.map { it.bigPct })
        val progress = events.filterIsInstance<HoldersEvent.Progress>()
        assertEquals(listOf(1, 2, 3), progress.map { it.done })
        assertEquals(LocalDate.of(2026, 11, 6), progress.first().date)
        assertTrue(progress.none { it.cached })

        // 先 GET 查詢頁，再一週一發 POST；每一發帶的是上一個回應給的 token
        assertEquals(listOf("GET", "POST", "POST", "POST"), http.requests.map { it.method })
        assertEquals(listOf("token-1", "token-2", "token-3"), http.posts().map { it.form["SYNCHRONIZER_TOKEN"] })
    }

    @Test
    fun formFieldsAreTheDocumentedOnes() = runTest {
        val http = http()
        source(http).fetch("2330", 1)
        val form = http.posts().single().form
        assertEquals("/portal/zh/smWeb/qryStock", form["SYNCHRONIZER_URI"])
        assertEquals("submit", form["method"])
        assertEquals("20261106", form["firDate"]) // 查詢頁隱藏欄位的值（最新的一週）
        assertEquals("20261106", form["scaDate"])
        assertEquals("StockNo", form["sqlMethod"])
        assertEquals("2330", form["stockNo"])
        assertEquals("", form["stockName"])
        assertTrue(http.requests.all { it.headers["User-Agent"]!!.startsWith("Mozilla/5.0") })
        // 像瀏覽器送出表單一樣帶 Referer、Origin
        assertEquals("https://tdcc.test/portal/zh/smWeb/qryStock", http.posts().single().headers["Referer"])
        assertEquals("https://tdcc.test", http.posts().single().headers["Origin"])
    }

    @Test
    fun cachedWeeksAreNotFetchedAgain() = runTest {
        val http = http()
        source(http).fetch("2330", 2)

        // App 重開（新的 source）：查詢頁要重拿（才知道有哪幾週），但抓過的週不再 POST
        val again = source(http).fetch("2330", 2)
        assertEquals(listOf(72.0, 71.5), again.done().weeks.map { it.bigPct })
        assertTrue(again.filterIsInstance<HoldersEvent.Progress>().all { it.cached })
        assertEquals(2, http.posts().size)

        // 多看一週：只 POST 沒抓過的那一週
        source(http).fetch("2330", 3).done()
        assertEquals(listOf("20261106", "20261030", "20261023"), http.posts().map { it.form["scaDate"] })
    }

    @Test
    fun noSuchStock() = runTest {
        val http = http()
        val failed = source(http).fetch("9999").failed()
        assertTrue(failed.message, "集保查無 9999" in failed.message)
        assertEquals("第一週就查無：不再查下一週", 1, http.posts().size)
    }

    @Test
    fun newStockWithShortHistoryReturnsWhatThereIs() = runTest {
        tdcc.data["7777" to weeks[0]] = Samples.tdccLevels()
        val done = source(http()).fetch("7777", 3).done()
        assertEquals(listOf("2026-11-06"), done.weeks.map { it.date })
    }

    @Test
    fun moreWeeksThanAvailable() = runTest {
        val done = source(http()).fetch("2330", 10).done()
        assertEquals(weeks.size, done.weeks.size)
    }

    @Test
    fun noResponseKeepsPartialAndStartsOverNextTime() = runTest {
        val http = http()
        http.on({ it.method == "POST" && it.form["scaDate"] == "20261030" }) { throw IOException("timeout") }
        val source = source(http)
        val failed = source.fetch("2330", 3).failed()
        assertTrue(failed.message, "集保沒回應" in failed.message)
        assertEquals(listOf("2026-11-06"), failed.partial.map { it.date })

        // 對方恢復之後：同一個 source 要從查詢頁重拿 token（舊的那個不知道有沒有被用掉），不能一直用壞掉的
        http.on({ it.method == "POST" && it.form["scaDate"] == "20261030" }) { r -> tdcc.post(r) }
        val done = source.fetch("2330", 3).done()
        assertEquals(3, done.weeks.size)
        assertEquals(2, http.gets().size)
    }

    @Test
    fun expiredTokenStartsOverFromTheFormOnce() = runTest {
        val http = http()
        val source = source(http)
        source.fetch("2330", 1).done()
        // 有人插隊用掉了 token（或 session 閒置太久過期）：下一發回空表 → 重拿查詢頁、同一週再試一次
        tdcc.issue()
        val done = source.fetch("2330", 2).done()
        assertEquals(listOf("2026-11-06", "2026-10-30"), done.weeks.map { it.date })
        assertEquals(listOf("GET", "POST", "POST", "GET", "POST"), http.requests.map { it.method })
        assertEquals(listOf("20261106", "20261030", "20261030"), http.posts().map { it.form["scaDate"] })
    }

    @Test
    fun stillExpiredAfterRetryFails() = runTest {
        val http = http()
        // 每一發都回空表（例如 cookie 沒帶上）：重試一次就停，不要一直打
        http.on({ it.method == "POST" && "qryStock" in it.url }) { r -> HttpResponse(200, Samples.tdccExpired(tdcc.issue(), weeks), r.url) }
        val failed = source(http).fetch("2330", 3).failed()
        assertTrue(failed.message, "token 或 session 失效" in failed.message)
        assertEquals(listOf("GET", "POST", "GET", "POST"), http.requests.map { it.method })
    }

    @Test
    fun resultPageWithoutTokenGetsTheFormBeforeTheNextWeek() = runTest {
        val http = http()
        // 結果頁沒帶新 token：下一週之前重拿查詢頁，不要拿空 token 去打
        http.on({ it.method == "POST" && "qryStock" in it.url }) { r -> tdcc.post(r).let { it.copy(body = it.body.replace(Regex("""<input type="hidden" name="SYNCHRONIZER_TOKEN"[^>]*>"""), "")) } }
        val done = source(http).fetch("2330", 2).done()
        assertEquals(2, done.weeks.size)
        assertEquals(listOf("GET", "POST", "GET", "POST"), http.requests.map { it.method })
        assertTrue(http.posts().all { it.form["SYNCHRONIZER_TOKEN"]!!.isNotEmpty() })
    }

    @Test
    fun formIsFetchedAgainOnANewDay() = runTest {
        val http = http()
        val source = source(http)
        source.fetch("2330", 1).done()
        source.fetch("2330", 1).done()
        assertEquals("同一天用同一份查詢頁", 1, http.gets().size)

        // 過了一週，集保多公布了一週：App 沒重開也要看得到
        today = today.plusDays(7)
        tdcc.available = listOf("20261113") + weeks
        tdcc.data["2330" to "20261113"] = Samples.tdccLevels(bigPct = 72.5)
        val done = source.fetch("2330", 1).done()
        assertEquals(2, http.gets().size)
        assertEquals("2026-11-13", done.weeks.single().date)
    }

    @Test
    fun httpErrorOnForm() = runTest {
        val http = http().apply { on({ it.method == "GET" }) { r -> HttpResponse(500, "oops", r.url) } }
        assertEquals("集保：HTTP 500", source(http).fetch("2330").failed().message)
    }

    @Test
    fun requestsArePaced() = runTest {
        val http = http()
        source(http).fetch("2330", 5).done()
        val times = http.requests.map { it.at }
        assertEquals(0L, times.first())
        times.zipWithNext { a, b -> assertTrue("集保兩發只隔 ${b - a} ms", b - a >= Pacer.TDCC_MS) }
    }
}
