package com.twtrubiks.stockdesk.core.http

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * 節流：「同一台主機兩次請求之間至少隔 [intervalMs] 毫秒」（另加 0～25% 隨機），不是每次請求後都睡，
 * 所以最後一發回來就直接回結果、不白等。讀快取不經過這裡。
 * 每個主機一個 Pacer（證交所、櫃買、集保、期交所各自算）。
 * 證交所刻意放慢到 2 秒以上：以約 1.2 秒間隔連打幾十次之後會完全不回應（首頁照常），疑似限流。
 */
class Pacer(
    private val intervalMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    private val mutex = Mutex()

    /** 上一發的時間；null 是還沒發過（第一發不用等）。 */
    private var lastAt: Long? = null

    /** 等到可以發下一個請求為止。回傳實際等了幾毫秒（測試用）。 */
    suspend fun await(): Long = mutex.withLock {
        val last = lastAt
        val jitter = (intervalMs * random.nextDouble(0.0, 0.25)).toLong()
        val wait = if (last == null) 0L else (last + intervalMs + jitter - now()).coerceAtLeast(0)
        if (wait > 0) delay(wait)
        lastAt = now()
        wait
    }

    companion object {
        const val TWSE_MS = 2_000L
        const val TPEX_MS = 1_000L
        const val TDCC_MS = 1_000L

        /** Yahoo（國際指數）：一次重新整理只打一發，這是連按重新整理時的下限。 */
        const val YAHOO_MS = 2_000L
    }
}
