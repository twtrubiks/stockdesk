package com.twtrubiks.stockdesk.core

import com.twtrubiks.stockdesk.core.http.HttpResponse
import com.twtrubiks.stockdesk.core.store.CacheStore
import com.twtrubiks.stockdesk.core.weights.WeightsException
import com.twtrubiks.stockdesk.core.weights.WeightsSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** 權重：一天只自動抓一次、手動更新、重抓失敗時顯示舊的（不連網，期交所的頁面用 [Samples] 造）。 */
class WeightsSourceTest {
    private val url = "https://taifex.test/cht/9/futuresQADetail"
    private val cache = CacheStore(JdbcSql())
    private var today = LocalDate.of(2026, 11, 10)
    private var now = LocalDateTime.of(2026, 11, 10, 9, 30).toInstant(ZoneOffset.ofHours(8)).toEpochMilli()
    private val page = Samples.taifexWeights(LocalDate.of(2026, 10, 31), Samples.constituents())
    private val http = FakeHttp().apply { on("futuresQADetail", body = page) }

    private fun source() = WeightsSource(http, cache, url = url, today = { today }, now = { now })

    @Test
    fun firstLoadFetchesAndStores() = runTest {
        val w = source().load()
        assertFalse(w.cached)
        assertNull(w.warning)
        assertEquals("2026-10-31", w.dataDate)
        assertEquals("2026-11-10 09:30", w.fetchedAt)
        assertEquals(1_070, w.rows.size)
        assertEquals(listOf(url), http.requests.map { it.url })
        assertTrue(http.requests.single().headers["User-Agent"]!!.startsWith("Mozilla/5.0"))

        assertEquals(w.rows, cache.weights())
        assertEquals("2026-10-31", cache.meta(CacheStore.META_WEIGHTS_DATE))
        assertEquals("2026-11-10 09:30", cache.meta(CacheStore.META_WEIGHTS_FETCHED))
    }

    @Test
    fun sameDayReadsCacheWithoutNetwork() = runTest {
        source().load()
        now += 8 * 3_600_000 // 同一天晚一點、App 重開
        val w = source().load()
        assertTrue(w.cached)
        assertEquals("2026-11-10 09:30", w.fetchedAt)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun nextDayFetchesAgain() = runTest {
        source().load()
        today = today.plusDays(1)
        now += 24 * 3_600_000
        val w = source().load()
        assertFalse(w.cached)
        assertEquals("2026-11-11 09:30", w.fetchedAt)
        assertEquals(2, http.requests.size)
    }

    @Test
    fun manualRefreshAlwaysFetches() = runTest {
        source().load()
        assertFalse(source().load(force = true).cached)
        assertEquals(2, http.requests.size)
    }

    @Test
    fun failedRefreshShowsOldDataWithWarning() = runTest {
        source().load()
        http.on("futuresQADetail", code = 500, body = "error")
        val w = source().load(force = true)
        assertTrue(w.cached)
        assertEquals("2026-10-31", w.dataDate)
        assertEquals(1_070, w.rows.size)
        assertTrue(w.warning!!, "重抓失敗" in w.warning!! && "HTTP 500" in w.warning!!)
    }

    @Test
    fun brokenPageNeverReplacesGoodData() = runTest {
        source().load()
        // 期交所改版只剩一半的表：當失敗，快取保持原樣
        http.on("futuresQADetail", body = Samples.taifexWeights(LocalDate.of(2026, 11, 30), Samples.constituents(400)))
        today = today.plusDays(1)
        val w = source().load()
        assertTrue(w.warning!!, "只解析出 400 筆" in w.warning!!)
        assertEquals(1_070, cache.weights().size)
        assertEquals("2026-10-31", cache.meta(CacheStore.META_WEIGHTS_DATE))
    }

    @Test
    fun noResponseAndNoOldDataIsAnError() = runTest {
        http.down("futuresQADetail")
        val e = runCatching { source().load() }.exceptionOrNull()
        assertTrue("應該是 WeightsException：$e", e is WeightsException)
        assertTrue(e!!.message!!, "timeout" in e.message!!)
    }

    @Test
    fun topNHasCumulativeWeight() = runTest {
        val w = source().load()
        val top = w.top(3)
        assertEquals(listOf("2330", "2317", "2454"), top.map { it.first.code })
        assertEquals(top[0].first.weight + top[1].first.weight + top[2].first.weight, top.last().second, 1e-9)
        assertEquals(1_070, w.top(5_000).size)
        assertEquals(w.rows.sumOf { it.weight }, w.top(5_000).last().second, 1e-9)
    }
}
