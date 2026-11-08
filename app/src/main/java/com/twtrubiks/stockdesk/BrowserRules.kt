package com.twtrubiks.stockdesk

import java.util.Base64

/** 內建瀏覽器頁的網址規則與錯誤頁。純 Kotlin，有 JVM 測試（`BrowserRulesTest`）。 */
object BrowserRules {
    /**
     * 瀏覽器自己載得了的網址。其他的（`mailto:`、`tel:`、`intent:`、`market:`…）交給手機上別的 App 開。
     * 空的是 `about:blank`：`window.open()` 不帶網址（之後再設 location）時 GeckoView 給的就是空字串。
     */
    fun isWeb(uri: String): Boolean = uri.isEmpty() || uri.substringBefore(':').lowercase() in WEB_SCHEMES

    /** 能丟給手機的瀏覽器開的網址（`about:blank`、`moz-extension:`、`data:` 之類的不行）。 */
    fun isHttp(uri: String): Boolean = uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)

    /**
     * 網頁載不出來（找不到網站、連不上、憑證有問題、一直轉址…）時顯示的錯誤頁，[reason] 是給使用者看的原因。
     * GeckoView 不帶錯誤頁，`onLoadError` 不給它一頁的話畫面就停在一片白。
     */
    fun errorPage(uri: String, reason: String): String {
        val html = """<!doctype html><html lang="zh-Hant"><head><meta charset="utf-8">
            |<meta name="viewport" content="width=device-width, initial-scale=1"><title>打不開這個網頁</title>
            |<style>:root{color-scheme:light dark}body{font-family:sans-serif;margin:48px 24px;line-height:1.7}
            |h1{font-size:22px}.url{word-break:break-all;opacity:.7;font-size:14px}</style></head>
            |<body><h1>打不開這個網頁</h1><p>${escape(reason)}</p><p class="url">${escape(uri)}</p>
            |<p>按上面的「重新整理」再試一次，或用「在瀏覽器開啟」交給手機的瀏覽器。</p></body></html>
        """.trimMargin()
        return "data:text/html;charset=utf-8;base64," + Base64.getEncoder().encodeToString(html.toByteArray())
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript", "moz-extension", "resource")
}
