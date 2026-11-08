package com.twtrubiks.stockdesk.core

import com.twtrubiks.stockdesk.core.holders.HoldersEvent
import com.twtrubiks.stockdesk.core.holders.HoldersSource
import com.twtrubiks.stockdesk.core.http.Http
import com.twtrubiks.stockdesk.core.http.OkHttp
import com.twtrubiks.stockdesk.core.http.Pacer
import com.twtrubiks.stockdesk.core.store.CacheStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * 集保的 token 綁在 session cookie 上：真的 [OkHttp] 對 MockWebServer 上的 [FakeTdcc]，cookie 有沒有帶到才測得出來
 * （[HoldersSourceTest] 的 FakeHttp 不經過 OkHttp，擋不下這個錯）。
 */
class HoldersOverOkHttpTest {
    private val server = MockWebServer()
    private val weeks = listOf("20261106", "20261030", "20261023")
    private val tdcc = FakeTdcc(weeks) { code, ymd -> if (code == "2330") Samples.tdccLevels(bigPct = 72.0 - weeks.indexOf(ymd) * 0.5) else null }

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val cookie = request.headers["Cookie"]
                val reply = if (request.method == "GET") tdcc.get(cookie) else tdcc.post(cookie, FakeTdcc.parseForm(request.body?.utf8().orEmpty()))
                val headers = Headers.Builder().add("Content-Type", "text/html; charset=utf-8")
                reply.setCookie?.let { headers.add("Set-Cookie", it) }
                return MockResponse(200, headers.build(), reply.body)
            }
        }
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun source(http: Http = OkHttp(OkHttpClient())) = HoldersSource(
        http,
        CacheStore(JdbcSql()),
        pacer = Pacer(0),
        base = server.url("/portal/zh/smWeb/qryStock").toString(),
        today = { LocalDate.of(2026, 11, 10) },
    )

    private fun HoldersSource.fetch(code: String, n: Int) = runBlocking { history(code, n).toList() }

    @Test
    fun sessionCookieIsSentBack() {
        val events = source().fetch("2330", 3)
        val done = events.last() as HoldersEvent.Done
        assertEquals(listOf(72.0, 71.5, 71.0), done.weeks.map { it.bigPct })
        assertEquals(0, tdcc.rejected)
        // 第一發 GET 沒有 cookie，之後每一發都帶同一個 session
        val cookies = (0 until server.requestCount).map { server.takeRequest().headers["Cookie"] }
        assertEquals(null, cookies.first())
        assertTrue(cookies.toString(), cookies.drop(1).all { it == "JSESSIONID=S1" })
    }

    @Test
    fun withoutCookiesEveryPostIsRejected() {
        // 修正前的樣子：每一發都是新的 client、不帶 cookie。假伺服器要擋得下來，這個測試才有意義
        val noCookies = object : Http {
            override suspend fun get(url: String, headers: Map<String, String>) = OkHttp(OkHttpClient()).get(url, headers)
            override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>) =
                OkHttp(OkHttpClient()).postForm(url, form, headers)
        }
        val failed = source(noCookies).fetch("2330", 1).last() as HoldersEvent.Failed
        assertTrue(failed.message, "token 或 session 失效" in failed.message)
        assertEquals(2, tdcc.rejected)
    }

    @Test
    fun expiredSessionStartsOverWithANewOne() {
        val source = source()
        assertEquals(1, (source.fetch("2330", 1).last() as HoldersEvent.Done).weeks.size)
        // 閒置太久，伺服器的 session 過期了：重拿查詢頁（拿到新的 session cookie）再試一次
        tdcc.expireSessions()
        val done = source.fetch("2330", 2).last() as HoldersEvent.Done
        assertEquals(listOf("2026-11-06", "2026-10-30"), done.weeks.map { it.date })
        assertEquals(1, tdcc.rejected)
    }

    @Test
    fun unknownStockOverTheWire() {
        val failed = source().fetch("9999", 2).last() as HoldersEvent.Failed
        assertTrue(failed.message, "集保查無 9999" in failed.message)
        assertEquals(0, tdcc.rejected)
    }
}
