package com.twtrubiks.stockdesk.core

import com.twtrubiks.stockdesk.core.http.Pacer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PacerTest {
    @Test
    fun firstRequestDoesNotWait() = runTest {
        var clock = 1_000L
        val pacer = Pacer(2_000, now = { clock }, random = Random(0))
        assertEquals(0L, pacer.await())
        clock += 100
        // 第二發離上一發不到 2 秒：要等到滿 2 秒（加上 0～25% 隨機）
        val waited = pacer.await()
        assertTrue("等了 $waited", waited in 1_900L..2_400L)
    }

    @Test
    fun noWaitWhenEnoughTimePassed() = runTest {
        var clock = 0L
        val pacer = Pacer(1_000, now = { clock })
        pacer.await()
        // 隨機最多加 25%：隔 1.25 秒以上一定不用等
        clock += 1_250
        assertEquals(0L, pacer.await())
    }

    @Test
    fun jitterStaysWithinQuarterOfInterval() = runTest {
        // 用 runTest 的虛擬時間：delay 不真的睡，兩發之間實際的間隔就是 delay 的長度
        val pacer = Pacer(2_000, now = { testScheduler.currentTime }, random = Random(42))
        pacer.await()
        val gaps = (1..200).map {
            val before = testScheduler.currentTime
            pacer.await()
            testScheduler.currentTime - before
        }
        assertTrue("最短 ${gaps.min()}", gaps.min() >= 2_000)
        assertTrue("最長 ${gaps.max()}", gaps.max() < 2_500)
        assertTrue("隨機要真的有變化", gaps.toSet().size > 10)
    }

    @Test
    fun hostIntervalsAreTheDocumentedOnes() {
        // 節流是硬規則（docs/data-sources.md）：不要改小
        assertTrue(Pacer.TWSE_MS >= 2_000)
        assertTrue(Pacer.TPEX_MS >= 1_000)
        assertTrue(Pacer.TDCC_MS >= 1_000)
    }
}
