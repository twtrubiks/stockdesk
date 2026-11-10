package com.twtrubiks.stockdesk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twtrubiks.stockdesk.core.Samples
import com.twtrubiks.stockdesk.core.govbank.GovBankDay
import com.twtrubiks.stockdesk.core.govbank.GovBankSource
import com.twtrubiks.stockdesk.core.holders.HoldersSource
import com.twtrubiks.stockdesk.core.margin.MarginDay
import com.twtrubiks.stockdesk.core.model.Market
import com.twtrubiks.stockdesk.core.names.StockNames
import com.twtrubiks.stockdesk.core.store.CacheStore
import com.twtrubiks.stockdesk.core.store.CacheStore.WeightRow
import com.twtrubiks.stockdesk.core.store.WatchlistStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.Locale

/**
 * 只有真機才測得到的事：Android 9 內建的 SQLite（3.22）跑得動 store 的**每一條** SQL
 * （JVM 測試用的 sqlite-jdbc 版本新很多，UPSERT、window function、RETURNING 之類的新語法擋不下來）。
 * 也順便驗 [AndroidSql] 的型別轉換（INTEGER → Long、REAL → Double、NULL）、Android 上的數字格式（千分位），
 * 以及 core 解析用的 regex 在 Android（ICU）上編得過、結果跟 JVM 一樣（ICU 不接受沒跳脫的 `}` 之類的，JVM 測試擋不下來）。
 * `record.sh` 在錄影前先跑、不錄影。用另一個檔名，不碰 App 自己的資料庫。
 */
@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sql = AndroidSql(context, DB)
    private val day = LocalDate.of(2026, 11, 6)

    @After
    fun cleanUp() {
        sql.close()
        context.deleteDatabase(DB)
    }

    @Test
    fun watchlistSqlRunsOnDeviceSqlite() {
        val watchlist = WatchlistStore(sql, StockNames(mapOf("2330" to "台積電")))
        watchlist.add("2330")
        watchlist.add("6515")
        watchlist.add("9999", "自己填的")
        watchlist.move("6515", -1)
        assertEquals(listOf("6515", "2330 台積電", "9999 自己填的"), watchlist.all().map { it.label })
        watchlist.rename("6515", "穎崴")
        watchlist.rename("9999", null)
        watchlist.reorder(listOf("9999"))
        assertEquals(listOf("9999", "6515 穎崴", "2330 台積電"), watchlist.all().map { it.label })
        assertNull(watchlist.find("9999")!!.name)

        val exported = watchlist.export()
        watchlist.remove("2330")
        assertEquals(listOf("9999", "6515"), watchlist.all().map { it.code })
        assertEquals(1, watchlist.import(exported))
        assertEquals(listOf("9999", "6515", "2330"), watchlist.all().map { it.code })

        // App 重開：同一個檔案再建一次 store，清單還在
        assertEquals(3, WatchlistStore(sql, StockNames.empty()).all().size)
    }

    @Test
    fun cacheSqlRunsOnDeviceSqlite() {
        val cache = CacheStore(sql)
        cache.putMeta("k", "v")
        cache.putMeta("k", "v2")
        assertEquals("v2", cache.meta("k"))
        cache.putMarket("2330", Market.TWSE)
        cache.putMarket("6515", Market.TPEX)
        assertEquals(Market.TWSE, cache.market("2330"))
        assertEquals(Market.TPEX, cache.market("6515"))

        val row = MarginDay(day.toString(), 1, 2, 3, 990, 1_000, 4_000, 25.0, 4, 5, 6, 105, 100, 4_000, 7)
        cache.putMarginDay(Market.TWSE, day.minusDays(1), closed = true, rows = emptyMap())
        cache.putMarginDay(Market.TWSE, day, closed = false, rows = mapOf("2330" to row, "2317" to row.copy(finUse = null)))
        assertEquals(true, cache.marginDayState(Market.TWSE, day.minusDays(1)))
        assertEquals(false, cache.marginDayState(Market.TWSE, day))
        assertNull(cache.marginDayState(Market.TPEX, day))
        assertEquals(row, cache.marginRow(Market.TWSE, day, "2330"))
        assertNull(cache.marginRow(Market.TWSE, day, "2317")!!.finUse)

        val week = HoldersSource.parseWeek(Samples.tdccWeek("t", listOf("20261106"), "20261106"), "20261106")!!
        cache.putHoldersWeek("2330", week)
        cache.putHoldersWeek("2330", week)
        assertEquals(week, cache.holdersWeek("2330", day))

        cache.putWeights(listOf(WeightRow(1, "2330", "台積電", 40.5), WeightRow(2, "2317", "鴻海", 5.25)), "2026-10-31", "2026-11-10 09:30")
        cache.putWeights(listOf(WeightRow(1, "2330", "台積電", 41.0)), "2026-11-30", "2026-12-01 09:30")
        assertEquals(listOf(WeightRow(1, "2330", "台積電", 41.0)), cache.weights())
        assertEquals("2026-11-30", cache.meta(CacheStore.META_WEIGHTS_DATE))

        val gov = GovBankDay("2026-11-06", 1_000_000, 400_000, 1_500_000_000.0, 600_000_000.0)
        cache.putGovBankDays("2330", listOf(gov, gov.copy(date = "2026-11-05"), gov.copy(date = "2026-08-01")), keepSince = LocalDate.of(2026, 7, 1))
        cache.putGovBankDays("2330", listOf(gov.copy(buy = 2_000_000)), keepSince = LocalDate.of(2026, 9, 1))
        assertEquals(listOf("2026-11-06", "2026-11-05"), cache.govBankDays("2330", 10).map { it.date })
        assertEquals(2_000_000L, cache.govBankDays("2330", 1).single().buy)
        assertEquals(900_000_000.0, cache.govBankDays("2330", 1).single().netAmount, 0.0)
    }

    @Test
    fun failedTransactionRollsBack() {
        val watchlist = WatchlistStore(sql, StockNames.empty())
        watchlist.add("2330")
        // 巢狀的 transaction（add 自己也開一個）：外層失敗，裡面已經寫的也要撤掉
        runCatching {
            sql.transaction {
                watchlist.add("1101")
                error("中途失敗")
            }
        }
        assertEquals(listOf("2330"), watchlist.all().map { it.code })
    }

    @Test
    fun govBankParsersRunOnAndroidRegex() {
        // GovBankSource 的 companion 一載入就編譯所有 regex：編不過整個 App 一進自選股分頁就閃退
        val stock = GovBankSource.parseHistockStock(
            Samples.histockStockGovBank("3131", "弘塑", listOf(Samples.StockDaySample(day, -14, -2_156), Samples.StockDaySample(day.plusDays(3), 1, 291))),
            "3131",
        )
        assertEquals(listOf("2026-11-09", "2026-11-06"), stock.map { it.date })
        assertEquals(listOf(1L, -14L), stock.map { it.netLots })
        assertEquals(emptyList<GovBankDay>(), GovBankSource.parseHistockStock(Samples.histockStockGovBank("2330", "台積電", emptyList()), "9999"))

        val market = GovBankSource.parseHistock(
            Samples.histockBroker8(listOf(day to 102.562), topBuy = listOf(Samples.RankSample("2330", "台積電", 213_162))),
        )
        assertEquals(102.562e8, market.latest!!.netAmount, 1.0)
        assertEquals("2330", market.topBuy.single().code)

        val blave = GovBankSource.parseBlave(Samples.blaveGovBank("2330", mapOf(day to Samples.govBankFlows(3_000, 1_000, price = 1_000.0))))
        assertEquals(2_000L, blave.single().netLots)
    }

    @Test
    fun numberFormatOnDevice() {
        // Android 的 java.util.Formatter 跟 JVM 不完全一樣（Locale.ROOT 在 Android 上不加千分位）：JVM 的 FormatTest 擋不下來
        val original = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.GERMANY, Locale.FRANCE, Locale.TRADITIONAL_CHINESE)) {
                Locale.setDefault(locale)
                assertEquals(locale.toString(), "24,720", fmt(24_720))
                assertEquals(locale.toString(), "+1,200", fmtSigned(1_200))
                assertEquals(locale.toString(), "11.44%", fmtPct(11.44))
                assertEquals(locale.toString(), "40.1235%", fmtPct(40.123_46, 4))
                assertEquals(locale.toString(), "-0.13", fmtSignedPct(-0.13))
                assertEquals(locale.toString(), "+21.32 億", fmtAmount(2_131_618_285.0))
                assertEquals(locale.toString(), "-9,240 萬", fmtAmount(-92_400_000.0))
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    private companion object {
        const val DB = "device-test.db"
    }
}
