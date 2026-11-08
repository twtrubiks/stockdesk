# 資料來源

全部是公開網頁／資料的實測結論；對方改版會壞是預期中的事，壞了先錄新的 fixture 再修解析。
**請求頻率是硬規則**：下面寫的間隔不要改小、不要平行。

## 融資融券（`core/margin/`）

| 市場 | 端點 | 休市時 | 間隔 |
| --- | --- | --- | --- |
| 上市 | `https://www.twse.com.tw/rwd/zh/marginTrading/MI_MARGN?date=YYYYMMDD&selectType=ALL&response=json` | `stat` 含「沒有符合條件的資料」 | ≥ 2 秒 |
| 上櫃 | `https://www.tpex.org.tw/www/zh-tw/margin/balance?date=YYYY/MM/DD&response=json` | `tables[0].data` 空陣列 | ≥ 1 秒 |

- 一次回一天、全市場，沒有單檔歷史查詢：N 天就往回打 N 次。預設 3 天第一次實測上市約 5 秒、上櫃約 3 秒
- 證交所（**對過真實回應**）：`tables[0]` 是「信用交易統計」，`tables[1]` 是個股，`fields[0]` 是「代號」（舊版 API 叫「股票代號」，兩種都認）。欄位：
  代號、名稱、融資（買進、賣出、現金償還、前日餘額、今日餘額、次一營業日限額）、融券（**買進、賣出**、現券償還、前日餘額、今日餘額、次一營業日限額）、資券互抵、註記。
  沒有 `groups`。數字帶千分位，註記空白是 `" "`。例：2330 融資 34,596 ＋ 3,201 − 2,682 − 43 ＝ 35,072；融券 47 ＋ 49（賣出）− 21（買進）− 0 ＝ 75。
  融資、融券欄名重複，parse 照位置分兩組、組內照欄名找
- 櫃買（實測筆記，**還沒對過真實回應**；這個 VM 的網頁抓取工具會被回 403）：`tables[0].data` 每列：代號、名稱、前資餘額、資買、資賣、現償、資餘額、資屬證金、資使用率、資限額、
  前券餘額、券賣、券買、券償、券餘額、券屬證金、券使用率、券限額、資券相抵、備註
- 兩邊 parse 完都用資料本身驗欄位：融資 今日 ＝ 前日 ＋ 買進 − 賣出 − 現金償還、融券 今日 ＝ 前日 ＋ 賣出 − 買進 − 現券償還；
  超過 1/5 的列對不起來就當失敗，錯誤訊息附上實際的欄名（欄位順序錯了在手機上一抓就看得出來）
- 證交所以約 1.2 秒間隔連打幾十次之後會完全不回應（首頁照常 200），過一陣子才恢復：所以放慢到 2 秒以上，讀取逾時 8 秒
- 過去的日期沒資料就是休市，永久記進快取；今天沒資料可能還沒公布，只在記憶體記 10 分鐘
- 市場別事先不知道：第一個有開市的日子先看證交所，沒有再看櫃買；一邊沒回應就跳過看另一邊。判斷出來存進 `markets` 表
- 「證交所與櫃買都沒有這一檔」只在兩邊**都有開市**、都沒有這檔時才下結論；一邊休市、今天還沒公布（兩邊公布的時間不一樣）或沒回應，就往前一天再看
- 市場已知、但有開市的那天沒有這一檔（停止信用交易、下市）：停下來，不再一天一天往回打
- 回應不是 JSON（被擋時可能回 HTML）或欄位數不對：當失敗（`MarginException`），不往下猜

## 股東持股分級（`core/holders/`）

`https://www.tdcc.com.tw/portal/zh/smWeb/qryStock`（集保戶股權分散表查詢），不需要 token。

1. GET 查詢頁：`<input name="SYNCHRONIZER_TOKEN" value="…">`、隱藏欄位 `firDate`（最新的一週）、`<select name="scaDate">` 裡可查的週
   （約 52 週，yyyyMMdd，新到舊，不一定是星期五）。回應會 `Set-Cookie: JSESSIONID`
2. POST 同一個網址（帶上一步的 cookie，`Referer`／`Origin` 照瀏覽器送表單的樣子），form：`SYNCHRONIZER_TOKEN`、`SYNCHRONIZER_URI=/portal/zh/smWeb/qryStock`、
   `method=submit`、`firDate=<查詢頁的 firDate，沒有就用最新的週>`、`scaDate=<要查的週>`、`sqlMethod=StockNo`、`stockNo=<代號>`、`stockName=`
3. 回 HTML：表格每列「序、持股分級、人數、股數、占集保庫存數比例」，15 級 ＋ 合計；有些股票多一列「差異數調整」，略過

- **token 存在伺服器的 session 裡**：POST 一定要帶 GET 查詢頁拿到的 cookie（`JSESSIONID`）。沒帶 cookie 等於每發都是新 session，token 永遠對不上、一律回空表
  （第一版 App 的 HTTP 沒存 cookie，手機上持股分級就是壞在這裡）。`OkHttp` 用只放記憶體的 `MemoryCookies`，App 關掉就清掉
- **token 每發換新**：回應頁裡帶下一發要用的 token，舊的再用就回空表（「資料日期：」後面是空的），所以請求照順序串、不能平行；間隔 ≥ 1 秒
- 回空表（token 用過、session 閒置過期）：重拿查詢頁（新的 token 與 session）、同一週再試一次；還是空表就報錯停下。回應裡沒帶新 token 也是下一發前重拿查詢頁
- 沒回應、HTTP 錯誤、改版就丟掉手上的 token，下次從 GET 查詢頁重來
- 先找分級表，找不到才看「查無此資料」（頁面其他地方可能也有這幾個字）
- 查詢頁（token 與可查的週）一天重拿一次（台北日期）：App 在背景放到下一週，回來才看得到新公布的那週
- 代號不存在回「查無此資料」
- 週資料公布後不會再變，以 (代號, 週) 存快取
- 15 級合併成 6 組（張，1 張 = 1,000 股）：1–3 級 ≤10、4–8 級 10–50、9 級 50–100、10–11 級 100–400、12–14 級 400–1000、15 級 >1000
- 另有 opendata（`opendata.tdcc.com.tw/getOD.ashx?id=1-5`）一次給全市場，但只有最新一週、沒有歷史，用不到

## 加權指數權重（`core/weights/`）

`https://www.taifex.com.tw/cht/9/futuresQADetail`（「臺灣證券交易所發行量加權股價指數成分股暨市值比重」），不需要 token。

- 整張表在 HTML 裡，一個 GET；期交所與證交所的 OpenAPI 都沒有同一份資料
- 頁尾「每月月底更新一次」，只有最新一期；只收上市股（含創新板）
- 表格左右兩欄排版（約 1,075 檔對半分，左邊比右邊多一列時右半邊是空的），每筆 4 格：排行／代號／名稱／比重（「41.6361%」，% 在同一格）；
  排行超過千帶千分位（「1,074」），名稱可能帶符號（「國巨*」、「正瀚-創」）。表頭只有「排行／證券名稱／市值佔 大盤比重」三格（代號與名稱共用一個表頭）
- 「資料日期： 2026/10/30」在頁面裡（冒號後面有空白）
- 頁面約 500 KB、沒有 Cache-Control：一天只自動抓一次
- 健全檢查：筆數 ≥ 500、排行 1..N 連續、比重加總 99%～101%，不合就當失敗

## 國際指數（`core/indices/`，Yahoo，非官方）

`https://query1.finance.yahoo.com/v7/finance/spark?symbols=^TWII,^DJI,^GSPC,^IXIC,^SOX,^N225,^KS11,^HSI&range=1d&interval=5m`
（代號要 URL 編碼），tw.stock.yahoo.com 國際指數頁用的同一份資料。**不是官方來源、沒有公開文件**：不用金鑰，但隨時可能改格式、限流或要 cookie；
美、日、韓、港的指數沒有免費的官方分時資料，所以用它。壞了就顯示錯誤，畫面上有「在 Yahoo 股市看」開網頁版。實測的格式：

- 一發拿全部 8 個，約 21 KB。回應 `{"spark":{"result":[{"symbol","response":[{"meta","timestamp":[…],"indicators":{"quote":[{"close":[…]}]}}]}]}}`，
  result 的順序是 Yahoo 自己排的（不照 symbols），用 symbol 對
- `range=1d` 回每個市場**自己的最近一個交易日**（假日抓到的全是上一個交易日）：5 分鐘一點（含頭尾，道瓊 09:30～16:00 共 79 點、台股 09:00～13:30 共 55 點）；
  close 可能是 null（那 5 分鐘沒成交），跳過
- meta：`regularMarketPrice`（最新／收盤）、`chartPreviousClose`（昨收）、`regularMarketTime`、`exchangeTimezoneName`、
  `tradingPeriods`（`[[{start,end}]]`，那天的盤；日本、香港的午休沒有切開）、`currentTradingPeriod.regular`
- 漲跌 ＝ `regularMarketPrice` − `chartPreviousClose`，跟網頁上的一樣（道瓊 ▼237.33（0.46%））。**不要用 `range=5d` 自己切**：
  前一天的最後一根 5 分鐘線不一定等於收盤價（韓國有收盤競價，差了 40 點），漲跌會對不上
- 沒有 User-Agent 回 HTTP 429；瀏覽器的 User-Agent 正常
- 開盤前 `range=1d` 回什麼沒實測到（可能只有 meta、沒有點）：某個指數沒有點就沿用快取裡它上次那一天
- 節流：`Pacer` 2 秒（一次重新整理只打一發，這是連按的下限）。進頁面時上次抓取在 5 分鐘內就讀快取，沒有自動更新
- 快取：整份解析結果（8 個指數的點）存在 `meta` 表（`indices.days` JSON、`indices.fetchedAt`），不另開表

## 社群討論區（只開網頁）

`https://www.cmoney.tw/forum/stock/<代號>`，在內建瀏覽器頁開給人看，程式不抓、不用 token。

## 股票名稱表（`core/src/main/resources/stock_names.json`）

`tools/stock_names/fetch_stock_names.py` 從公開端點產生（上市／上櫃／興櫃公司簡稱、上市櫃行情補 ETF；權證濾掉；指數與期貨代號寫死），約 3,100 檔、80 KB。
新股或新 ETF 掛牌才需要重跑；檔案壞掉退回空表，只是沒名稱。
