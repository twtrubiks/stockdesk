package com.twtrubiks.stockdesk.core

import com.twtrubiks.stockdesk.core.indices.IndicesException
import com.twtrubiks.stockdesk.core.indices.IndicesSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Yahoo spark 的解析，吃 VM 裡錄的真實回應 `fixtures/yahoo-spark.json`（2026-11-08 週日錄的，每個指數都是 11/06 那天的盤）。
 * 數字對照當時 tw.stock.yahoo.com 國際指數頁上顯示的值。
 */
class IndicesParseTest {
    private val days = IndicesSource.parse(Fixtures.require("yahoo-spark.json")).associateBy { it.symbol }

    @Test
    fun allEightInTheConfiguredOrder() {
        val parsed = IndicesSource.parse(Fixtures.require("yahoo-spark.json"))
        // 回應裡的順序是 Yahoo 自己排的（^DJI、^N225…），結果照 DEFAULT 的順序
        assertEquals(IndicesSource.DEFAULT.map { it.symbol }, parsed.map { it.symbol })
        assertEquals(IndicesSource.DEFAULT.map { it.name }, parsed.map { it.name })
    }

    @Test
    fun dowMatchesTheYahooPage() {
        val dji = days.getValue("^DJI")
        assertEquals(51_285.73, dji.price, 0.001)
        assertEquals(51_523.06, dji.prevClose, 0.001)
        // 網頁上是「▼237.33（0.46%）」
        assertEquals(-237.33, dji.change, 0.005)
        assertEquals(-0.46, dji.changePct, 0.005)
        assertEquals("America/New_York", dji.zone)
        assertEquals(LocalDate.of(2026, 11, 6), dji.sessionDate)
        assertTrue(dji.closed)
        // 09:30～16:00 每 5 分鐘一點（含頭尾）
        assertEquals(79, dji.times.size)
        assertEquals(dji.times.size, dji.values.size)
        assertEquals(dji.sessionStart, dji.times.first())
        assertEquals(dji.sessionEnd, dji.times.last())
    }

    @Test
    fun asianMarketsUseTheirOwnSessionsAndPrevClose() {
        val n225 = days.getValue("^N225")
        assertEquals(57_434.77, n225.price, 0.001)
        assertEquals(-3_643.13, n225.change, 0.005)
        assertEquals(-5.96, n225.changePct, 0.005)
        assertEquals(LocalDate.of(2026, 11, 6), n225.sessionDate)

        // 韓國：昨收用 chartPreviousClose（5 分鐘線最後一根不是收盤價）
        val kospi = days.getValue("^KS11")
        assertEquals(5_383.8, kospi.prevClose, 0.001)
        assertEquals(5_059.69, kospi.price, 0.001)

        val twii = days.getValue("^TWII")
        assertEquals(39_701.64, twii.price, 0.001)
        assertEquals(41_574.72, twii.prevClose, 0.001)
        // 09:00～13:30 共 4.5 小時
        assertEquals(4 * 3600 + 1800L, twii.sessionEnd - twii.sessionStart)

        val hsi = days.getValue("^HSI")
        assertEquals(24_481.86, hsi.price, 0.001)
        assertEquals(-1.27, hsi.changePct, 0.005)
    }

    @Test
    fun notJsonOrWrongShapeFails() {
        assertThrows(IndicesException::class.java) { IndicesSource.parse("<html>Too Many Requests</html>") }
        assertThrows(IndicesException::class.java) { IndicesSource.parse("""{"chart":{"result":[]}}""") }
    }
}
