package com.twtrubiks.stockdesk.core

import com.twtrubiks.stockdesk.core.indices.IndexSpec
import com.twtrubiks.stockdesk.core.indices.IndicesException
import com.twtrubiks.stockdesk.core.indices.IndicesSource
import com.twtrubiks.stockdesk.core.http.Pacer
import com.twtrubiks.stockdesk.core.store.CacheStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/** 國際指數：一發拿全部、5 分鐘內進頁面讀快取、重新整理、失敗顯示舊的、某個指數沒點時沿用舊的（Yahoo 的回應用 [Samples] 造）。 */
class IndicesSourceTest {
    private val url = "https://yahoo.test/v7/finance/spark"
    private val cache = CacheStore(JdbcSql())
    private var now = LocalDateTime.of(2026, 11, 9, 10, 0).toInstant(ZoneOffset.ofHours(8)).toEpochMilli()
    private val specs = listOf(IndexSpec("^TWII", "台股加權"), IndexSpec("^DJI", "道瓊工業"))
    private val friday = LocalDate.of(2026, 11, 6)
    private val monday = LocalDate.of(2026, 11, 9)

    private fun twii(date: LocalDate, pct: Double, upTo: LocalTime? = null) =
        Samples.sparkDay("^TWII", "Asia/Taipei", date, LocalTime.of(9, 0), LocalTime.of(13, 30), 41_000.0, pct, upTo)

    private fun dji(date: LocalDate, pct: Double) =
        Samples.sparkDay("^DJI", "America/New_York", date, LocalTime.of(9, 30), LocalTime.of(16, 0), 51_000.0, pct)

    private val http = FakeHttp().apply { on("finance/spark", body = Samples.yahooSpark(listOf(twii(friday, 1.0), dji(friday, -0.5)))) }

    private fun source(http: FakeHttp = this.http) = IndicesSource(http, cache, url = url, pacer = Pacer(0), now = { now }, specs = specs)

    @Test
    fun oneRequestForAllIndices() = runTest {
        val r = source().load()
        assertFalse(r.cached)
        assertNull(r.warning)
        assertEquals("2026-11-09 10:00", r.fetchedAt)
        assertEquals(listOf("^TWII", "^DJI"), r.days.map { it.symbol })
        assertEquals(listOf("台股加權", "道瓊工業"), r.days.map { it.name })

        val req = http.requests.single()
        assertEquals("%5ETWII%2C%5EDJI", req.param("symbols"))
        assertEquals("1d", req.param("range"))
        assertEquals("5m", req.param("interval"))
        // 沒有 User-Agent 的話 Yahoo 回 429
        assertTrue(req.headers["User-Agent"]!!.startsWith("Mozilla/5.0"))

        val twii = r.days.first()
        assertEquals(41_000.0, twii.prevClose, 0.0)
        assertEquals(41_410.0, twii.price, 0.001)
        assertEquals(1.0, twii.changePct, 0.001)
        assertEquals(friday, twii.sessionDate)
        assertTrue(twii.closed)
        assertEquals(55, twii.times.size)
    }

    @Test
    fun enteringAgainWithinFiveMinutesReadsCache() = runTest {
        source().load()
        now += 4 * 60_000
        val again = source().load()
        assertTrue(again.cached)
        assertEquals("2026-11-09 10:00", again.fetchedAt)
        assertEquals(1, http.requests.size)

        // 超過 5 分鐘，進頁面就重抓
        now += 2 * 60_000
        assertFalse(source().load().cached)
        assertEquals(2, http.requests.size)
    }

    @Test
    fun refreshAlwaysFetches() = runTest {
        source().load()
        now += 30_000
        val r = source().load(force = true)
        assertFalse(r.cached)
        assertEquals("2026-11-09 10:00", r.fetchedAt)
        assertEquals(2, http.requests.size)
    }

    @Test
    fun duringTheSessionTheLineStopsHalfway() = runTest {
        http.on("finance/spark", body = Samples.yahooSpark(listOf(twii(monday, 2.0, upTo = LocalTime.of(10, 0)), dji(friday, -0.5))))
        val twii = source().load().days.first()
        assertFalse(twii.closed)
        assertEquals(monday, twii.sessionDate)
        // 09:00～10:00 共 13 點，x 軸還是到 13:30
        assertEquals(13, twii.times.size)
        assertTrue(twii.times.last() < twii.sessionEnd)
        assertEquals(4 * 3600 + 1800L, twii.sessionEnd - twii.sessionStart)
    }

    @Test
    fun nullClosesAreSkipped() = runTest {
        http.on("finance/spark", body = Samples.yahooSpark(listOf(twii(friday, 1.0), dji(friday, -0.5)), nullEvery = 5))
        val twii = source().load().days.first()
        // 55 點裡第 5、10…50 點是 null（最後一點不塞），跳過 10 點
        assertEquals(45, twii.times.size)
        assertEquals(twii.times.size, twii.values.size)
        assertEquals(41_410.0, twii.price, 0.001)
    }

    @Test
    fun indexWithoutPointsKeepsItsPreviousDay() = runTest {
        source().load()
        // 星期一早上 08:50：台股還沒開盤、Yahoo 只回 meta；道瓊回上週五的盤（數字不同）
        now += 6 * 60_000
        http.on("finance/spark", body = Samples.yahooSpark(listOf(twii(monday, 0.0), dji(friday, -0.7)), noPoints = setOf("^TWII")))
        val r = source().load(force = true)
        assertFalse(r.cached)
        assertEquals(listOf("^TWII", "^DJI"), r.days.map { it.symbol })
        assertEquals(friday, r.days[0].sessionDate)
        assertEquals(1.0, r.days[0].changePct, 0.001)
        assertEquals(-0.7, r.days[1].changePct, 0.001)
    }

    @Test
    fun failureShowsTheLastDataWithAWarning() = runTest {
        source().load()
        now += 10 * 60_000
        http.down("finance/spark")
        val r = source().load()
        assertTrue(r.cached)
        assertEquals("2026-11-09 10:00", r.fetchedAt)
        assertTrue(r.warning!!, r.warning!!.startsWith("重抓失敗"))
        assertEquals(2, r.days.size)
    }

    @Test
    fun failureWithoutCacheThrows() = runTest {
        val e = assertThrows(IndicesException::class.java) {
            kotlinx.coroutines.runBlocking { source(FakeHttp().apply { on("finance/spark", code = 429, body = "Too Many Requests") }).load() }
        }
        assertTrue(e.message!!, "429" in e.message!!)

        val empty = assertThrows(IndicesException::class.java) {
            kotlinx.coroutines.runBlocking {
                source(FakeHttp().apply { on("finance/spark", body = Samples.yahooSpark(listOf(twii(monday, 0.0)), noPoints = setOf("^TWII"))) }).load()
            }
        }
        assertTrue(empty.message!!, "沒有任何指數" in empty.message!!)
    }

    @Test
    fun brokenCacheIsIgnored() = runTest {
        cache.putMeta(IndicesSource.META_DAYS, "{not json")
        cache.putMeta(IndicesSource.META_FETCHED, now.toString())
        val r = source().load()
        assertFalse(r.cached)
        assertEquals(2, r.days.size)
    }

    @Test
    fun repeatedRefreshIsPaced() = runTest {
        val clocked = FakeHttp { testScheduler.currentTime }.apply { on("finance/spark", body = Samples.yahooSpark(listOf(twii(friday, 1.0), dji(friday, -0.5)))) }
        val source = IndicesSource(clocked, cache, url = url, pacer = Pacer(Pacer.YAHOO_MS, now = { testScheduler.currentTime }), now = { now }, specs = specs)
        source.load(force = true)
        source.load(force = true)
        val (a, b) = clocked.requests.map { it.at }
        assertTrue("間隔 ${b - a} ms", b - a >= Pacer.YAHOO_MS)
    }
}
