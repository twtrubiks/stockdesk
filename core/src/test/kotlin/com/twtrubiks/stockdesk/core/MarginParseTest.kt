package com.twtrubiks.stockdesk.core

import com.twtrubiks.stockdesk.core.margin.MarginException
import com.twtrubiks.stockdesk.core.margin.MarginSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 解析。名字有 `Real` 的吃錄好的真實回應（fixture 還沒錄之前會 skip）：欄位順序對不對只有它們說了算。
 * `twseActual20261106` 是 2026-11-08 對過的證交所真實回應（欄名與 2330 那一列原樣，其他列省略）。
 * 其他用 [Samples] 造的回應，測休市、千分位、挑對表、壞掉的回應。
 */
class MarginParseTest {
    private val date = LocalDate.of(2026, 11, 7)

    private val twseFields =
        """["代號","名稱","買進","賣出","現金償還","前日餘額","今日餘額","次一營業日限額","買進","賣出","現券償還","前日餘額","今日餘額","次一營業日限額","資券互抵","註記"]"""
    private val tsmc20261106 = """["2330","台積電","3,201","2,682","43","34,596","35,072","6,483,092","21","49","0","47","75","6,483,092","35"," "]"""

    private fun twse(fields: String, row: String) =
        """{"stat":"OK","date":"20261106","tables":[""" +
            """{"title":"115年11月06日 信用交易統計","fields":["項目","買進","賣出","現金(券)償還","前日餘額","今日餘額"],"data":[["融資(交易單位)","1","2","3","4","5"]]},""" +
            """{"title":"115年11月06日 融資融券彙總 (全部)","fields":$fields,"data":[$row]}]}"""

    @Test
    fun twseActual20261106() {
        // 手機上第一次抓就失敗（「找不到個股的表」）：個股那張的第一欄是「代號」，融券是先買進、後賣出
        val day = MarginSource.parseTwse(twse(twseFields, tsmc20261106), LocalDate.of(2026, 11, 6))!!.getValue("2330")
        assertEquals(listOf(3_201L, 2_682L, 43L, 34_596L, 35_072L, 6_483_092L), listOf(day.finBuy, day.finSell, day.finRedeem, day.finPrev, day.finBal, day.finLimit))
        assertEquals(listOf(21L, 49L, 0L, 47L, 75L, 6_483_092L), listOf(day.shortBuy, day.shortSell, day.shortRedeem, day.shortPrev, day.shortBal, day.shortLimit))
        assertEquals(35L, day.offset)
        assertEquals(476L, day.finChg)
        assertEquals(28L, day.shortChg)
        assertEquals(35_072 * 100.0 / 6_483_092, day.finUse!!, 1e-9)
    }

    @Test
    fun twseOldHeaderNameStillWorks() {
        val fields = twseFields.replace("\"代號\",\"名稱\"", "\"股票代號\",\"股票名稱\"")
        assertEquals(35_072L, MarginSource.parseTwse(twse(fields, tsmc20261106), date)!!.getValue("2330").finBal)
    }

    @Test
    fun twseColumnsAreFoundByNameWithinTheGroup() {
        // 融券那組欄位順序換了：照欄名找，數字還是讀得對
        val fields = twseFields.replace("\"買進\",\"賣出\",\"現券償還\"", "\"賣出\",\"買進\",\"現券償還\"")
        val row = tsmc20261106.replace("\"21\",\"49\"", "\"49\",\"21\"")
        val day = MarginSource.parseTwse(twse(fields, row), date)!!.getValue("2330")
        assertEquals(21L, day.shortBuy)
        assertEquals(49L, day.shortSell)
    }

    @Test
    fun wrongColumnOrderIsCaughtByTheBalances() {
        // 欄名說「賣出、買進」但資料其實是「買進、賣出」（就是原本寫錯的那種）：餘額對不起來，報錯並附上欄名
        val fields = twseFields.replace("\"買進\",\"賣出\",\"現券償還\"", "\"賣出\",\"買進\",\"現券償還\"")
        val e = assertThrows(MarginException::class.java) { MarginSource.parseTwse(twse(fields, tsmc20261106), date) }
        assertTrue(e.message!!, "對不起來" in e.message!! && "代號、名稱" in e.message!!)
    }

    @Test
    fun tpexWrongColumnOrderIsCaughtByTheBalances() {
        // 櫃買的欄位順序還沒對過真實回應：錯了要在手機上一抓就看得出來
        val good = Samples.tpexMarginDay(date, (1..10).map { Samples.MarginRow("65${10 + it}", finPrev = 1_000L * it, finBal = 1_000L * it + 37, finLimit = 9_000, shortPrev = 50, shortBal = 41) })
        assertEquals(10, MarginSource.parseTpex(good, date)!!.size)
        // 把每一列的「資買」「資賣」對調
        val swapped = good.replace(Regex("""\["(65\d\d)","(65\d\d)","([^"]*)","([^"]*)","([^"]*)"""")) { m ->
            val (code, name, prev, buy, sell) = m.destructured
            """["$code","$name","$prev","$sell","$buy""""
        }
        val e = assertThrows(MarginException::class.java) { MarginSource.parseTpex(swapped, date) }
        assertTrue(e.message!!, e.message!!.startsWith("櫃買：10/10 檔的餘額對不起來"))
    }

    @Test
    fun twseClosedDay() {
        assertNull(MarginSource.parseTwse("""{"stat":"很抱歉，沒有符合條件的資料"}""", date))
    }

    @Test
    fun tpexClosedDay() {
        assertNull(MarginSource.parseTpex("""{"stat":"ok","tables":[{"fields":[],"data":[]}]}""", date))
    }

    @Test
    fun twsePicksTheStockTableAndReadsThousands() {
        // 第一張「信用交易統計」不是個股；數字帶千分位
        val body = Samples.twseMarginDay(
            date,
            listOf(
                Samples.MarginRow(
                    "2330", "台積電", finPrev = 23_456, finBal = 24_001, finLimit = 6_483_000, shortPrev = 312, shortBal = 300, shortLimit = 6_483_000,
                    finBuy = 1_234, finSell = 650, finRedeem = 39, shortSell = 20, shortBuy = 30, shortRedeem = 2, offset = 15,
                ),
            ),
        )
        val day = MarginSource.parseTwse(body, date)!!.getValue("2330")
        assertEquals("2026-11-07", day.date)
        assertEquals(listOf(1_234L, 650L, 39L, 23_456L, 24_001L, 6_483_000L), listOf(day.finBuy, day.finSell, day.finRedeem, day.finPrev, day.finBal, day.finLimit))
        assertEquals(listOf(20L, 30L, 2L, 312L, 300L, 6_483_000L), listOf(day.shortSell, day.shortBuy, day.shortRedeem, day.shortPrev, day.shortBal, day.shortLimit))
        assertEquals(15L, day.offset)
        assertEquals(24_001 * 100.0 / 6_483_000, day.finUse!!, 1e-9)
    }

    @Test
    fun tpexReadsUsageFromTheEndpoint() {
        val body = Samples.tpexMarginDay(date, listOf(Samples.MarginRow("6515", "穎崴", finPrev = 1_000, finBal = 1_200, finLimit = 4_800, shortPrev = 90, shortBal = 60, shortLimit = 4_800)))
        val day = MarginSource.parseTpex(body, date)!!.getValue("6515")
        assertEquals(200L, day.finChg)
        assertEquals(-30L, day.shortChg)
        assertEquals(25.0, day.finUse!!, 0.001)
        assertEquals(4_800L, day.shortLimit)
        assertEquals(5.0, day.ratio!!, 0.001)
    }

    @Test
    fun zeroBalanceHasNoRatio() {
        val body = Samples.twseMarginDay(date, listOf(Samples.MarginRow("1101")))
        val day = MarginSource.parseTwse(body, date)!!.getValue("1101")
        assertNull(day.finUse)
        assertNull(day.ratio)
    }

    @Test
    fun brokenResponsesAreMarginExceptions() {
        assertThrows(MarginException::class.java) { MarginSource.parseTwse("<html>請稍後再試</html>", date) }
        assertThrows(MarginException::class.java) { MarginSource.parseTwse("""{"stat":"OK","tables":[{"fields":["項目"],"data":[]}]}""", date) }
        assertThrows(MarginException::class.java) { MarginSource.parseTwse("""{"stat":"OK","tables":[{"fields":["股票代號"],"data":[["2330","台積電"]]}]}""", date) }
        assertThrows(MarginException::class.java) { MarginSource.parseTpex("""{"stat":"error"}""", date) }
        assertThrows(MarginException::class.java) { MarginSource.parseTpex("""[]""", date) }
    }

    @Test
    fun twseRealDay() {
        val rows = MarginSource.parseTwse(Fixtures.require("twse-margin-day.json"), date)!!
        assertTrue("至少幾百檔", rows.size > 500)
        // TODO fixture 錄好後：挑一檔對照網頁上的數字（餘額、限額、資使用率），確認欄位順序沒對錯
    }

    @Test
    fun tpexRealDay() {
        val rows = MarginSource.parseTpex(Fixtures.require("tpex-margin-day.json"), date)!!
        assertTrue(rows.size > 300)
        val any = rows.values.first()
        assertEquals(any.finBal - any.finPrev, any.finChg)
        // TODO fixture 錄好後：挑一檔對照櫃買網頁的資使用率（端點直接給）
    }
}
