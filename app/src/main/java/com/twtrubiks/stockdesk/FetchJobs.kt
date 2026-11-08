package com.twtrubiks.stockdesk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.twtrubiks.stockdesk.core.holders.HolderWeek
import com.twtrubiks.stockdesk.core.holders.HoldersEvent
import com.twtrubiks.stockdesk.core.margin.MarginDay
import com.twtrubiks.stockdesk.core.margin.MarginEvent
import com.twtrubiks.stockdesk.core.model.Market
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** 一個抓取的狀態，畫面直接讀。 */
sealed interface JobState<out T> {
    data object Idle : JobState<Nothing>
    data class Running(val message: String, val done: Int = 0, val total: Int = 0) : JobState<Nothing>
    data class Ready<T>(val value: T, val cached: Boolean = false) : JobState<T>
    data class Failed(val message: String) : JobState<Nothing>
}

data class MarginResult(val market: Market, val rows: List<MarginDay>)

/**
 * 正在跑與跑完的抓取都放在這裡（[AppContainer.scope]），不放在組合裡：切分頁、轉螢幕不會中斷、結果不會不見。
 * **一次只跑一個對外的抓取**（按鈕在跑的時候都鎖住），對官方來源溫和一點。融資融券、持股分級的快取由 source 自己寫。
 */
class FetchJobs(private val c: AppContainer) {
    val margin = mutableStateMapOf<String, JobState<MarginResult>>()
    val holders = mutableStateMapOf<String, JobState<List<HolderWeek>>>()

    var busy by mutableStateOf(false)
        private set
    private var current: Job? = null

    fun fetchMargin(code: String) = run(code, margin) {
        val watch = c.watchlist.all().map { it.code }.toSet()
        c.margin.history(code, c.marginDays.value, watch).onEach { e ->
            margin[code] = when (e) {
                is MarginEvent.Progress -> JobState.Running("第 ${e.done}/${e.total} 個交易日 ${e.date}${if (e.cached) "（快取）" else ""}", e.done, e.total)
                is MarginEvent.Done -> JobState.Ready(MarginResult(e.market, e.rows))
                is MarginEvent.Failed -> JobState.Failed(e.message)
            }
        }.collect()
    }

    fun fetchHolders(code: String) = run(code, holders) {
        c.holders.history(code, c.holdersWeeks.value).onEach { e ->
            holders[code] = when (e) {
                is HoldersEvent.Progress -> JobState.Running("第 ${e.done}/${e.total} 週 ${e.date}${if (e.cached) "（快取）" else ""}", e.done, e.total)
                is HoldersEvent.Done -> JobState.Ready(e.weeks)
                is HoldersEvent.Failed -> JobState.Failed(e.message)
            }
        }.collect()
    }

    fun cancel() {
        current?.cancel()
        busy = false
    }

    private fun <T> run(code: String, states: MutableMap<String, JobState<T>>, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        states[code] = JobState.Running("開始抓…")
        current = c.scope.launch {
            try {
                block()
            } catch (e: Exception) {
                states[code] = JobState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }
}
