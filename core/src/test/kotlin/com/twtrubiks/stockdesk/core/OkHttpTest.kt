package com.twtrubiks.stockdesk.core

import com.twtrubiks.stockdesk.core.http.OkHttp
import com.twtrubiks.stockdesk.core.margin.MarginEvent
import com.twtrubiks.stockdesk.core.margin.MarginSource
import com.twtrubiks.stockdesk.core.model.Market
import com.twtrubiks.stockdesk.core.store.CacheStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/** 真的 [OkHttp] 對本機的 MockWebServer：表單編碼、標頭、4xx 不丟例外、斷線丟 IOException。 */
class OkHttpTest {
    private val server = MockWebServer()
    private val http = OkHttp(OkHttpClient())

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    @Test
    fun getSendsHeadersAndReturnsText() = runBlocking {
        server.enqueue(MockResponse(200, Headers.headersOf("Content-Type", "application/json; charset=utf-8"), """{"stat":"OK","名稱":"台積電"}"""))
        val r = http.get(server.url("/a?x=1").toString(), mapOf("User-Agent" to "test-agent", "Accept" to "application/json"))
        assertEquals(200, r.code)
        assertTrue(r.ok)
        assertTrue(r.body, "台積電" in r.body)
        assertTrue(r.finalUrl.endsWith("/a?x=1"))
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("test-agent", recorded.headers["User-Agent"])
        assertEquals("application/json", recorded.headers["Accept"])
    }

    @Test
    fun postFormIsUrlEncodedUtf8() = runBlocking {
        server.enqueue(MockResponse(200, Headers.headersOf(), "ok"))
        http.postForm(server.url("/portal/zh/smWeb/qryStock").toString(), linkedMapOf("stockNo" to "2330", "stockName" to "台積電", "SYNCHRONIZER_URI" to "/portal/zh/smWeb/qryStock"))
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.headers["Content-Type"]!!.startsWith("application/x-www-form-urlencoded"))
        assertEquals(
            "stockNo=2330&stockName=%E5%8F%B0%E7%A9%8D%E9%9B%BB&SYNCHRONIZER_URI=%2Fportal%2Fzh%2FsmWeb%2FqryStock",
            recorded.body!!.utf8(),
        )
    }

    @Test
    fun httpErrorsAreNotExceptions() = runBlocking {
        server.enqueue(MockResponse(503, Headers.headersOf(), "busy"))
        val r = http.get(server.url("/").toString())
        assertEquals(503, r.code)
        assertEquals(false, r.ok)
    }

    @Test
    fun droppedConnectionIsIOException() {
        // OkHttp 連線失敗會自己重試一次，多排幾個
        repeat(3) { server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build()) }
        assertThrows(IOException::class.java) { runBlocking { http.get(server.url("/").toString()) } }
    }

    @Test
    fun tpexDateWithSlashesArrivesIntact() = runBlocking {
        // 端到端：MarginSource ＋ 真的 OkHttp，櫃買日期參數裡的斜線不能被弄壞
        val date = LocalDate.of(2026, 11, 6)
        server.enqueue(MockResponse(200, Headers.headersOf(), Samples.tpexMarginDay(date, listOf(Samples.MarginRow("6515", finPrev = 90, finBal = 100, finLimit = 400)))))
        val cache = CacheStore(JdbcSql()).apply { putMarket("6515", Market.TPEX) }
        val base = server.url("/").toString().trimEnd('/')
        val source = MarginSource(http, cache, twseBase = base, tpexBase = base, today = { date })
        val done = source.history("6515", 1, setOf("6515")).toList().last() as MarginEvent.Done
        assertEquals(100L, done.rows.single().finBal)
        val recorded = server.takeRequest()
        assertEquals("/www/zh-tw/margin/balance", recorded.url.encodedPath)
        assertEquals("2026/11/06", recorded.url.queryParameter("date"))
        assertEquals("json", recorded.url.queryParameter("response"))
    }
}
