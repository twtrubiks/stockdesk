package com.twtrubiks.stockdesk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 蓋在分頁上的頁用字串存進 rememberSaveable，轉螢幕後要能還原；網址裡有冒號與問號也不能壞。 */
class OverlayTest {
    @Test
    fun roundTrips() {
        val browser = Overlay.Browser("https://goodinfo.tw/tw/ShowMarginChart.asp?STOCK_ID=2330")
        assertEquals(browser, Overlay.parse(browser.key))
        assertEquals(Overlay.Settings, Overlay.parse(Overlay.Settings.key))
        assertNull(Overlay.parse(null))
        assertNull(Overlay.parse("garbage"))
    }
}
