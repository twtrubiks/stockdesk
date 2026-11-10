package com.twtrubiks.stockdesk

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import com.twtrubiks.stockdesk.core.govbank.GovBankDay
import com.twtrubiks.stockdesk.core.indices.IndexDay
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** 數字格式（docs/spec.md「整體」）：跟手機的語言設定無關。 */
class FormatTest {
    private val original = Locale.getDefault()

    @After
    fun restore() = Locale.setDefault(original)

    @Test
    fun thousandsSignsAndPercentages() {
        assertEquals("1,234,567", fmt(1_234_567))
        assertEquals("0", fmt(0))
        assertEquals("+1,200", fmtSigned(1_200))
        assertEquals("-35", fmtSigned(-35))
        assertEquals("+0", fmtSigned(0))
        assertEquals("12.35%", fmtPct(12.345_6))
        assertEquals("40.1235%", fmtPct(40.123_46, 4))
        assertEquals("+0.50", fmtSignedPct(0.5))
        assertEquals("-1.25", fmtSignedPct(-1.254))
    }

    @Test
    fun nullIsADash() {
        assertEquals("-", fmt(null))
        assertEquals("-", fmtSigned(null))
        assertEquals("-", fmtPct(null))
        assertEquals("-", fmtSignedPct(null))
    }

    @Test
    fun phoneLanguageDoesNotChangeTheFormat() {
        for (locale in listOf(Locale.GERMANY, Locale.FRANCE, Locale.forLanguageTag("ar-EG"), Locale.TRADITIONAL_CHINESE)) {
            Locale.setDefault(locale)
            assertEquals(locale.toString(), "1,234,567", fmt(1_234_567))
            assertEquals(locale.toString(), "12.35%", fmtPct(12.345_6))
            assertEquals(locale.toString(), "51,285.73", fmtPrice(51_285.73))
        }
    }

    @Test
    fun indexPriceAndChange() {
        assertEquals("51,285.73", fmtPrice(51_285.73))
        assertEquals("5,059.69", fmtPrice(5_059.69))
        assertEquals("▼ 237.33 (0.46%)", fmtIndexChange(-237.33, -0.4606))
        assertEquals("▲ 23.13 (0.31%)", fmtIndexChange(23.13, 0.3145))
        assertEquals("0.00 (0.00%)", fmtIndexChange(0.001, 0.0))
    }

    @Test
    fun govBankAmountsAndLine() {
        assertEquals("+21.32 億", fmtAmount(2_131_618_285.0))
        assertEquals("-1.00 億", fmtAmount(-100_000_000.0))
        assertEquals("+9,240 萬", fmtAmount(92_400_000.0))
        assertEquals("-50 萬", fmtAmount(-500_000.0))
        assertEquals("0 萬", fmtAmount(4_000.0))
        assertEquals("買超", govBankSide(1.0))
        assertEquals("賣超", govBankSide(-1.0))
        assertEquals("持平", govBankSide(0.0))
        // 台積電 2026-11-09（blave 的真實回應加起來）
        val day = GovBankDay("2026-11-09", buy = 2_863_879, sell = 1_908_988, buyAmount = 6_686_000_000.0, sellAmount = 4_554_381_715.0)
        assertEquals("官股 11/09 買超 +21.32 億・+955 張", govBankLine(day))
        assertEquals("官股 11/09 賣超 -21.32 億・-955 張", govBankLine(day.copy(buy = day.sell, sell = day.buy, buyAmount = day.sellAmount, sellAmount = day.buyAmount)))
        Locale.setDefault(Locale.GERMANY)
        assertEquals("+9,240 萬", fmtAmount(92_400_000.0))
        assertEquals("+21.32 億", fmtAmount(2_131_618_285.0))
    }

    @Test
    fun indexStatusLine() {
        // 道瓊 11/06 的盤：台北時間 11/06 22:30 開盤、11/07 05:00 收盤
        val start = LocalDateTime.of(2026, 11, 6, 9, 30).atZone(ZoneId.of("America/New_York")).toEpochSecond()
        val end = start + 6 * 3600 + 1800
        fun dji(upTo: Long) = IndexDay("^DJI", "道瓊工業", "America/New_York", 1.0, 1.0, start, end, upTo, listOf(start, upTo), listOf(1.0, 1.0))
        assertEquals("11/06 收盤", indexStatus(dji(end)))
        // 開盤後一小時抓的：最新一筆是台北時間 23:30，日期還是交易所當地的 11/06
        assertEquals("11/06 盤中 23:30", indexStatus(dji(start + 3600)))
    }
}
