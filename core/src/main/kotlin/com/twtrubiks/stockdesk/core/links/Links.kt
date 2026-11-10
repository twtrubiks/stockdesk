package com.twtrubiks.stockdesk.core.links

import java.net.URLEncoder

/**
 * 給人看的網頁網址：App 內建的瀏覽器頁（`BrowserScreen`）直接開，也可以從那一頁丟給手機的瀏覽器。
 * goodinfo 前面有 Cloudflare 驗證，程式直接抓會回 403，所以 goodinfo 只給人看、不抓；資料改抓同一份的官方來源。
 */
object Links {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** goodinfo 融資融券。 */
    fun goodinfoMargin(code: String) = "https://goodinfo.tw/tw/ShowMarginChart.asp?STOCK_ID=${enc(code)}"

    /** goodinfo 股東持股分級（股權分散表）。 */
    fun goodinfoHolders(code: String) = "https://goodinfo.tw/tw/EquityDistributionClassHis.asp?STOCK_ID=${enc(code)}"

    /** 社群的個股討論區網頁。 */
    fun forum(code: String) = "https://www.cmoney.tw/forum/stock/${enc(code)}"

    /**
     * 玩股網的「八大官股券商每日買賣超」網頁（全市場，不分個股）。
     * 資料端點有裝置指紋驗證，程式抓會回 400，所以跟 goodinfo 一樣只在內建瀏覽器頁開給人看、不抓。
     */
    fun publicBank(market: PublicBankMarket) = "https://www.wantgoo.com/stock/public-bank/trend?market=${market.param}"

    /** blave 的個股「八大公股銀行買賣超」網頁（有圖、可以看更久以前）：官股頁點一檔開這裡。 */
    fun blaveGovBank(code: String) = "https://blave.org/studio/twstock/zh/chips/gov_bank?stock_id=${enc(code)}"

    /** HiStock 的「八大官股銀行／公股行庫買賣超排名」網頁：官股頁全市場那一塊的網頁版（App 抓的就是這一頁）。 */
    const val HISTOCK_GOV_BANK = "https://histock.tw/stock/broker8.aspx"

    /** HiStock 的個股「八大官股買賣超」網頁（上市、上櫃都有）：blave 沒有的檔（上櫃）App 抓的就是這一頁，官股頁點那一檔開這裡。 */
    fun histockGovBank(code: String) = "https://histock.tw/stock/broker.aspx?no=${enc(code)}"

    /** Yahoo 股市的國際指數網頁：大盤分頁「國際指數」抓不到時的備案（同一份資料的網頁版）。 */
    const val YAHOO_WORLD_INDICES = "https://tw.stock.yahoo.com/world-indices"

    /** 集保戶股權分散表查詢頁。 */
    const val TDCC_QUERY = "https://www.tdcc.com.tw/portal/zh/smWeb/qryStock"
}

/** 八大官股網頁的市場範圍，`param` 是網址上 `market=` 的值。 */
enum class PublicBankMarket(val label: String, val param: Int) {
    ALL("上市櫃", -1),
    TWSE("上市", 0),
    TPEX("上櫃", 1),
}
