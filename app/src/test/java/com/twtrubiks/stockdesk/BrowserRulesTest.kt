package com.twtrubiks.stockdesk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class BrowserRulesTest {
    @Test
    fun webSchemes() {
        assertTrue(BrowserRules.isWeb("https://goodinfo.tw/tw/ShowMarginChart.asp?STOCK_ID=2330"))
        assertTrue(BrowserRules.isWeb("HTTP://example.com"))
        assertTrue(BrowserRules.isWeb(""))
        assertTrue(BrowserRules.isWeb("about:blank"))
        assertTrue(BrowserRules.isWeb("moz-extension://abc/popup.html"))
        assertFalse(BrowserRules.isWeb("intent://forum#Intent;scheme=cmoney;end"))
        assertFalse(BrowserRules.isWeb("market://details?id=x"))
        assertFalse(BrowserRules.isWeb("mailto:a@b.c"))
    }

    @Test
    fun httpOnlyForExternal() {
        assertTrue(BrowserRules.isHttp("https://www.cmoney.tw/forum/stock/2330"))
        assertFalse(BrowserRules.isHttp("about:blank"))
        assertFalse(BrowserRules.isHttp("data:text/html;base64,AA=="))
    }

    @Test
    fun errorPageEscapesAndEncodes() {
        val page = BrowserRules.errorPage("https://x.tw/?a=1&b=<2>", "連不上")
        val prefix = "data:text/html;charset=utf-8;base64,"
        assertTrue(page.startsWith(prefix))
        val html = String(Base64.getDecoder().decode(page.removePrefix(prefix)))
        assertTrue(html.contains("連不上"))
        assertTrue(html.contains("https://x.tw/?a=1&amp;b=&lt;2&gt;"))
        assertEquals(-1, html.indexOf("<2>"))
    }
}
