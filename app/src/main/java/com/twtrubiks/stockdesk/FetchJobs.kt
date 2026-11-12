package com.twtrubiks.stockdesk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import com.twtrubiks.stockdesk.core.chips.ChipDay
import com.twtrubiks.stockdesk.core.chips.ChipDays
import com.twtrubiks.stockdesk.core.govbank.GovBank
import com.twtrubiks.stockdesk.core.govbank.GovBankDay
import com.twtrubiks.stockdesk.core.govbank.GovBankVia
import com.twtrubiks.stockdesk.core.holders.HolderWeek
import com.twtrubiks.stockdesk.core.holders.HoldersEvent
import com.twtrubiks.stockdesk.core.margin.MarginDay
import com.twtrubiks.stockdesk.core.margin.MarginEvent
import com.twtrubiks.stockdesk.core.model.Market
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.random.Random

/** 一個抓取的狀態，畫面直接讀。 */
sealed interface JobState<out T> {
    data object Idle : JobState<Nothing>
    data class Running(val message: String, val done: Int = 0, val total: Int = 0) : JobState<Nothing>
    data class Ready<T>(val value: T, val cached: Boolean = false) : JobState<T>
    data class Failed(val message: String) : JobState<Nothing>
}

/**
 * goodinfo 卡片「融資融券・官股」抓完的結果：兩個來源照日期併成一張表（[ChipDays.merge]），各自的錯誤分開寫，一邊失敗另一邊照樣顯示。
 * [market] 是上市／上櫃（融資融券沒抓到就不知道）；[marginError] 是融資融券沒抓到的原因；
 * [hasGov] 是這次有沒有抓八大官股（「全部抓取」在來源停了以後不抓），[govNote] 是它沒抓到的原因，[govVia] 是它的資料從哪一家來。
 */
data class MarginResult(
    val market: Market?,
    val rows: List<ChipDay>,
    val marginError: String? = null,
    val hasGov: Boolean = false,
    val govNote: String? = null,
    val govVia: GovBankVia = GovBankVia.BLAVE,
)

/**
 * 正在跑與跑完的抓取都放在這裡（[AppContainer.scope]），不放在組合裡：切分頁、轉螢幕不會中斷、結果不會不見。
 * **一次只跑一個對外的抓取**（按鈕在跑的時候都鎖住），對官方來源溫和一點。融資融券、持股分級、八大官股的快取由 source 自己寫。
 */
class FetchJobs(private val c: AppContainer) {
    val margin = mutableStateMapOf<String, JobState<MarginResult>>()
    val holders = mutableStateMapOf<String, JobState<List<HolderWeek>>>()

    /** 八大官股的全市場（大盤的官股頁）。個股的在 [margin] 裡，跟融資融券一起抓。 */
    var govBank by mutableStateOf<JobState<GovBank>>(JobState.Idle)
        private set

    /** 最近一次全市場抓完的結果（抓取中也留著）。 */
    var govBankResult by mutableStateOf<GovBank?>(null)
        private set

    /** 每抓完一檔的八大官股就加一：自選股分頁看到它變了就重讀快取（每檔底下那一行）。 */
    var govStockVersion by mutableIntStateOf(0)
        private set

    var busy by mutableStateOf(false)
        private set
    private var current: Job? = null

    /** goodinfo 分頁的「全部抓取」：整份自選股的融資融券與持股分級。Failed 是有來源沒回應、後面沒抓完。 */
    var all by mutableStateOf<JobState<Unit>>(JobState.Idle)
        private set

    /** 一檔的融資融券＋八大官股（goodinfo 卡片上同一顆「抓」）。 */
    fun fetchMargin(code: String) = run(code, margin) {
        collectChips(code, c.watchlist.all().map { it.code }.toSet())
    }

    fun fetchHolders(code: String) = run(code, holders) { collectHolders(code) }

    /**
     * 一檔一步抓完的結果。[net]：有連網（不是全部讀快取）；[stop]：來源沒回應的訊息，後面不要再打它
     * （[collectChips] 的 [stop] 是融資融券的、[govStop] 是八大官股的）。
     */
    private class Step(val net: Boolean, val stop: String?, val govStop: String? = null)

    /**
     * 融資融券（證交所／櫃買）再八大官股（blave／HiStock，一發），結果併成一張表放進 [margin]。兩個來源各自成敗，
     * 不同主機、各有各的 Pacer。[doMargin]／[doGov] 是「全部抓取」在某個來源停了以後跳過它。
     */
    private suspend fun collectChips(code: String, watch: Set<String>, doMargin: Boolean = true, doGov: Boolean = true): Step {
        val days = c.marginDays.value
        var net = false
        var stop: String? = null
        var market: Market? = null
        var rows = emptyList<MarginDay>()
        var marginError: String? = null
        if (doMargin) {
            c.margin.history(code, days, watch).collect { e ->
                when (e) {
                    is MarginEvent.Progress -> {
                        margin[code] = JobState.Running("第 ${e.done}/${e.total} 個交易日 ${e.date}${if (e.cached) "（快取）" else ""}", e.done, e.total)
                        if (!e.cached) net = true
                    }
                    is MarginEvent.Done -> {
                        market = e.market
                        rows = e.rows
                    }
                    is MarginEvent.Failed -> {
                        rows = e.partial
                        marginError = e.message
                        net = true
                        if (e.stop) stop = e.message
                    }
                }
            }
        }
        var govNote: String? = null
        var govStop: String? = null
        var govVia = GovBankVia.BLAVE
        var govDays = emptyList<GovBankDay>()
        if (doGov) {
            margin[code] = JobState.Running("八大官股…")
            // 多帶幾天：官股比融資融券早公布，融資融券最舊的那幾天才對得到
            val gov = c.govBank.stock(code, days + GOV_EXTRA_DAYS)
            govDays = gov.days
            govVia = gov.via
            govNote = gov.stop ?: gov.note
            govStop = gov.stop
            if (!gov.cached) net = true
            govStockVersion++
        }
        margin[code] = JobState.Ready(MarginResult(market, ChipDays.merge(rows, govDays, days), marginError, doGov, govNote, govVia))
        return Step(net, stop, govStop)
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
     * 全部抓取：自選股照順序一檔一檔來，每檔先融資融券＋八大官股、再持股分級，不平行。
     * 每個主機自己的 [com.twtrubiks.stockdesk.core.http.Pacer] 照樣管請求之間的間隔；這裡另外在「上一步有連網」時
     * 多停一段隨機的時間（同一檔的兩步之間 [STEP_PAUSE_MS]、換下一檔 [STOCK_PAUSE_MS]），每次長短都不一樣；
     * 上一步全部讀快取就不停。某個來源沒回應（可能被限流）就不再打它，其他來源繼續；三個都停了就結束。
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
                var govStop: String? = null
                var holdersStop: String? = null
                var pause = false // 上一步有連網，下一步之前先停一下
                for ((i, stock) in stocks.withIndex()) {
                    if (marginStop != null && govStop != null && holdersStop != null) break
                    code = stock.code
                    val label = "${i + 1}/$total ${stock.code} ${stock.name.orEmpty()}".trim()
                    suspend fun step(name: String, range: LongRange, fetch: suspend () -> Step): Step {
                        if (pause) {
                            val ms = Random.nextLong(range.first, range.last + 1)
                            all = JobState.Running("$label・等 ${"%.1f".format(Locale.US, ms / 1000.0)} 秒", i, total)
                            delay(ms)
                        }
                        all = JobState.Running("$label・$name", i, total)
                        val result = fetch()
                        pause = result.net
                        return result
                    }
                    val chips = marginStop == null || govStop == null
                    if (chips) {
                        val doMargin = marginStop == null
                        val doGov = govStop == null
                        margin[stock.code] = JobState.Running("開始抓…")
                        val r = step("融資融券・官股", STOCK_PAUSE_MS) { collectChips(stock.code, watch, doMargin, doGov) }
                        marginStop = marginStop ?: r.stop
                        govStop = govStop ?: r.govStop
                    }
                    if (holdersStop == null) {
                        holders[stock.code] = JobState.Running("開始抓…")
                        // 這一檔的第一步沒跑（來源都停了）時，上一步是前一檔，照換檔的間隔停
                        holdersStop = step("股東持股分級", if (chips) STEP_PAUSE_MS else STOCK_PAUSE_MS) { collectHolders(stock.code) }.stop
                    }
                }
                val stops = listOfNotNull(
                    marginStop?.let { "融資融券：$it" },
                    govStop?.let { "八大官股：$it" },
                    holdersStop?.let { "股東持股分級：$it" },
                )
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
     * 八大官股的全市場（HiStock 一發）。[force] 是標題列的重新整理；否則該公布的那天已經抓過的就只讀快取
     * （官股頁一進來就呼叫，大部分時候不連網）。個股的不在這裡抓（goodinfo 卡片的「抓」與「全部抓取」）。
     */
    fun fetchGovBank(force: Boolean) {
        if (busy) return
        busy = true
        govBank = JobState.Running("全市場…")
        current = c.scope.launch {
            try {
                val r = c.govBank.market(force)
                govBankResult = r
                govBank = JobState.Ready(r, cached = r.cached)
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
        /** 八大官股比畫面上的天數多讀幾天（跟融資融券對日期用）。 */
        const val GOV_EXTRA_DAYS = 5

        /** 全部抓取：同一檔兩步之間多停的時間（毫秒，隨機）。 */
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
