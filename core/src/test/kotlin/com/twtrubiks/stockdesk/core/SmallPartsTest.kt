package com.twtrubiks.stockdesk.core

import com.twtrubiks.stockdesk.core.http.Taipei
import com.twtrubiks.stockdesk.core.links.Links
import com.twtrubiks.stockdesk.core.links.PublicBankMarket
import com.twtrubiks.stockdesk.core.model.StockCode
import com.twtrubiks.stockdesk.core.model.StockRef
import com.twtrubiks.stockdesk.core.names.StockNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** 代號、名稱表、台北時間、網址這些小東西。 */
class SmallPartsTest {
    @Test
    fun stockCodes() {
        listOf("2330", "6515", "00631L", "00878", "006208", "2330 ").forEach { assertTrue(it, StockCode.isValid(it)) }
        listOf("233", "1234567", "台積電", "23-30", "", "   ").forEach { assertFalse(it, StockCode.isValid(it)) }
        assertEquals("00631L", StockCode.normalize(" 00631l "))
        assertNull(StockCode.normalize("tsmc!"))
    }

    @Test
    fun labels() {
        assertEquals("2330 台積電", StockRef("2330", "台積電").label)
        assertEquals("9999", StockRef("9999", null).label)
        assertEquals("9999", StockRef("9999", " ").label)
    }

    @Test
    fun bundledNamesLoad() {
        // 打包的名稱表壞掉不會讓 App 壞，但會讓所有名稱消失：這裡擋下來
        val names = StockNames.load()
        assertTrue("名稱表只有 ${names.size} 檔", names.size > 2_000)
        assertEquals("台積電", names["2330"])
        assertEquals("元大台灣50", names["0050"])
        assertNull(names["0000"])
    }

    @Test
    fun searchByCodePrefixOrNameSubstring() {
        val names = StockNames(mapOf("2330" to "台積電", "2301" to "光寶科", "1101" to "台泥", "2308" to "台達電", "00631L" to "元大台灣50正2"))
        // 英數字：代號前綴，照代號排
        assertEquals(listOf("2301", "2308", "2330"), names.search("23").map { it.code })
        assertEquals(listOf("00631L"), names.search(" 00631l ").map { it.code })
        assertEquals(emptyList<StockRef>(), names.search("2"))  // 一個字不列
        // 含中文：名稱包含（「元大台灣50正2」的「台」在中間也算；00631L 的代號最小，排第一）
        assertEquals(listOf("00631L", "1101", "2308", "2330"), names.search("台").map { it.code })
        assertEquals(listOf("2330"), names.search("積電").map { it.code })
        assertEquals(emptyList<StockRef>(), names.search("鴻海"))
        assertEquals(emptyList<StockRef>(), names.search("  "))
        // limit
        assertEquals(2, names.search("台", limit = 2).size)
    }

    @Test
    fun brokenNamesFileIsJustEmpty() {
        assertEquals(0, StockNames.parse("""{"version":1}""").size)
        assertEquals("台積電", StockNames.parse("""{"names":{"2330":"台積電"}}""")["2330"])
    }

    @Test
    fun taipeiDates() {
        // UTC 11/7 16:30 是台北 11/8 00:30：「今天」看台北
        assertEquals(LocalDate.of(2026, 11, 8), Taipei.today { Instant.parse("2026-11-07T16:30:00Z") })
        assertEquals(LocalDate.of(2026, 11, 7), Taipei.today { Instant.parse("2026-11-07T15:59:59Z") })
        assertEquals("20261106", Taipei.ymd(LocalDate.of(2026, 11, 6)))
        assertEquals("2026/11/06", Taipei.slash(LocalDate.of(2026, 11, 6)))
        assertEquals("2026-11-08 00:30", Taipei.stamp(Instant.parse("2026-11-07T16:30:00Z").toEpochMilli()))
    }

    @Test
    fun links() {
        assertEquals("https://goodinfo.tw/tw/ShowMarginChart.asp?STOCK_ID=2330", Links.goodinfoMargin("2330"))
        assertEquals("https://goodinfo.tw/tw/EquityDistributionClassHis.asp?STOCK_ID=00631L", Links.goodinfoHolders("00631L"))
        assertEquals("https://www.cmoney.tw/forum/stock/6515", Links.forum("6515"))
        assertEquals("https://www.tdcc.com.tw/portal/zh/smWeb/qryStock", Links.TDCC_QUERY)
        assertEquals("https://tw.stock.yahoo.com/world-indices", Links.YAHOO_WORLD_INDICES)
        assertEquals("https://www.wantgoo.com/stock/public-bank/trend?market=-1", Links.publicBank(PublicBankMarket.ALL))
        assertEquals("https://www.wantgoo.com/stock/public-bank/trend?market=1", Links.publicBank(PublicBankMarket.TPEX))
        assertEquals("https://blave.org/studio/twstock/zh/chips/gov_bank?stock_id=2330", Links.blaveGovBank("2330"))
        assertEquals("https://histock.tw/stock/broker8.aspx", Links.HISTOCK_GOV_BANK)
        assertEquals("https://histock.tw/stock/broker.aspx?no=3131", Links.histockGovBank("3131"))
    }
}
