package com.twtrubiks.stockdesk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.twtrubiks.stockdesk.core.govbank.GovBank
import com.twtrubiks.stockdesk.core.govbank.GovBankEvent
import com.twtrubiks.stockdesk.core.holders.HolderWeek
import com.twtrubiks.stockdesk.core.holders.HoldersEvent
import com.twtrubiks.stockdesk.core.margin.MarginDay
import com.twtrubiks.stockdesk.core.margin.MarginEvent
import com.twtrubiks.stockdesk.core.model.Market
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.random.Random

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

    /** 八大官股（大盤的官股頁）：整份自選股一起抓，不分代號。 */
    var govBank by mutableStateOf<JobState<GovBank>>(JobState.Idle)
        private set

    /** 最近一次八大官股抓完的結果（抓取中也留著），官股頁與自選股分頁看到它變了就重讀。 */
    var govBankResult by mutableStateOf<GovBank?>(null)
        private set

    var busy by mutableStateOf(false)
        private set
    private var current: Job? = null

    /** goodinfo 分頁的「全部抓取」：整份自選股的融資融券與持股分級。Failed 是有來源沒回應、後面沒抓完。 */
    var all by mutableStateOf<JobState<Unit>>(JobState.Idle)
        private set

    fun fetchMargin(code: String) = run(code, margin) {
        collectMargin(code, c.watchlist.all().map { it.code }.toSet())
    }

    fun fetchHolders(code: String) = run(code, holders) { collectHolders(code) }

    /** 一檔一個來源抓完的結果。[net]：有連網（不是全部讀快取）；[stop]：來源沒回應的訊息，後面不要再打它。 */
    private class Step(val net: Boolean, val stop: String?)

    private suspend fun collectMargin(code: String, watch: Set<String>): Step {
        var net = false
        var stop: String? = null
        c.margin.history(code, c.marginDays.value, watch).collect { e ->
            margin[code] = when (e) {
                is MarginEvent.Progress -> JobState.Running("第 ${e.done}/${e.total} 個交易日 ${e.date}${if (e.cached) "（快取）" else ""}", e.done, e.total)
                is MarginEvent.Done -> JobState.Ready(MarginResult(e.market, e.rows))
                is MarginEvent.Failed -> JobState.Failed(e.message)
            }
            if (e is MarginEvent.Progress && !e.cached) net = true
            if (e is MarginEvent.Failed) {
                net = true
                if (e.stop) stop = e.message
            }
        }
        return Step(net, stop)
    }

    private suspend fun collectHolders(code: String): Step {
        var net = false
        var stop: String? = null
        c.holders.history(code, c.holdersWeeks.value).collect { e ->
            holders[code] = when (e) {
                is HoldersEvent.Progress -> JobState.Running("第 ${e.done}/${e.total} 週 ${e.date}${if (e.cached) "（快取）" else ""}", e.done, e.total)
                is HoldersEvent.Done -> JobState.Ready(e.weeks)
                is HoldersEvent.Failed -> JobState.Failed(e.message)
            }
            if (e is HoldersEvent.Progress && !e.cached) net = true
            if (e is HoldersEvent.Failed) {
                net = true
                if (e.stop) stop = e.message
            }
        }
        return Step(net, stop)
    }

    /**
     * 全部抓取：自選股照順序一檔一檔來，每檔先融資融券、再持股分級，不平行。
     * 每個主機自己的 [com.twtrubiks.stockdesk.core.http.Pacer] 照樣管請求之間的間隔；這裡另外在「上一步有連網」時
     * 多停一段隨機的時間（同一檔的兩個來源之間 [STEP_PAUSE_MS]、換下一檔 [STOCK_PAUSE_MS]），每次長短都不一樣；
     * 上一步全部讀快取就不停。某個來源沒回應（可能被限流）就不再打它，另一個來源繼續；兩個都停了就結束。
     */
    fun fetchAll() {
        if (busy) return
        busy = true
        all = JobState.Running("開始抓…")
        current = c.scope.launch {
            var code: String? = null
            try {
                val stocks = c.watchlist.all()
                val watch = stocks.map { it.code }.toSet()
                val total = stocks.size
                var marginStop: String? = null
                var holdersStop: String? = null
                var pause = false // 上一步有連網，下一步之前先停一下
                for ((i, stock) in stocks.withIndex()) {
                    if (marginStop != null && holdersStop != null) break
                    code = stock.code
                    val label = "${i + 1}/$total ${stock.code} ${stock.name.orEmpty()}".trim()
                    suspend fun step(name: String, range: LongRange, fetch: suspend () -> Step): String? {
                        if (pause) {
                            val ms = Random.nextLong(range.first, range.last + 1)
                            all = JobState.Running("$label・等 ${"%.1f".format(ms / 1000.0)} 秒", i, total)
                            delay(ms)
                        }
                        all = JobState.Running("$label・$name", i, total)
                        val result = fetch()
                        pause = result.net
                        return result.stop
                    }
                    if (marginStop == null) {
                        margin[stock.code] = JobState.Running("開始抓…")
                        marginStop = step("融資融券", STOCK_PAUSE_MS) { collectMargin(stock.code, watch) }
                    }
                    if (holdersStop == null) {
                        holders[stock.code] = JobState.Running("開始抓…")
                        // 這一檔的融資融券沒跑（來源停了）時，上一步是前一檔，照換檔的間隔停
                        holdersStop = step("股東持股分級", if (marginStop == null) STEP_PAUSE_MS else STOCK_PAUSE_MS) { collectHolders(stock.code) }
                    }
                }
                val stops = listOfNotNull(marginStop?.let { "融資融券：$it" }, holdersStop?.let { "股東持股分級：$it" })
                all = if (stops.isEmpty()) JobState.Ready(Unit) else JobState.Failed("沒有抓完，後面的檔沒抓。" + stops.joinToString("；"))
            } catch (e: CancellationException) {
                // 取消：正在跑的那一檔退回還沒抓的樣子（已經抓到的幾天／幾週在快取裡，再抓秒回）
                code?.let {
                    if (margin[it] is JobState.Running) margin.remove(it)
                    if (holders[it] is JobState.Running) holders.remove(it)
                }
                all = JobState.Idle
                throw e
            } catch (e: Exception) {
                code?.let {
                    if (margin[it] is JobState.Running) margin.remove(it)
                    if (holders[it] is JobState.Running) holders.remove(it)
                }
                all = JobState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    /**
     * 八大官股：先全市場（HiStock 一發），再自選股一檔一發（blave）。[force] 是標題列的重新整理；
     * 否則該公布的那天已經抓過的就只讀快取（官股頁一進來就呼叫，大部分時候不連網）。
     */
    fun fetchGovBank(force: Boolean) {
        if (busy) return
        busy = true
        govBank = JobState.Running("開始抓…")
        current = c.scope.launch {
            try {
                val stocks = c.watchlist.all()
                c.govBank.load(stocks, force).onEach { e ->
                    if (e is GovBankEvent.Done) govBankResult = e.result
                    govBank = when (e) {
                        is GovBankEvent.Progress -> JobState.Running("${e.done}/${e.total} ${e.label}${if (e.cached) "（快取）" else ""}", e.done, e.total)
                        is GovBankEvent.Done -> JobState.Ready(e.result, cached = e.result.cached)
                    }
                }.collect()
            } catch (e: Exception) {
                govBank = JobState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    /** 取消正在跑的抓取；[busy] 由那個抓取自己的 finally 放開。 */
    fun cancel() {
        current?.cancel()
    }

    private companion object {
        /** 全部抓取：同一檔兩個來源之間多停的時間（毫秒，隨機）。 */
        val STEP_PAUSE_MS = 500L..1_500L

        /** 全部抓取：換下一檔之前多停的時間（毫秒，隨機）。 */
        val STOCK_PAUSE_MS = 1_500L..4_500L
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
