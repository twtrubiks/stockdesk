# 資料來源

都是公開網頁／資料；對方改版會壞是預期中的事，壞了先錄新的 fixture 再修解析。
官方來源：證交所、櫃買、集保、期交所；非官方：Yahoo（國際指數）、blave 與 HiStock（八大官股）。
**請求頻率是硬規則**：下面寫的間隔不要改小、不要平行。

## 融資融券（`core/margin/`）

| 市場 | 端點 | 休市時 | 間隔 |
| --- | --- | --- | --- |
| 上市 | `https://www.twse.com.tw/rwd/zh/marginTrading/MI_MARGN?date=YYYYMMDD&selectType=ALL&response=json` | `stat` 含「沒有符合條件的資料」 | ≥ 2 秒 |
| 上櫃 | `https://www.tpex.org.tw/www/zh-tw/margin/balance?date=YYYY/MM/DD&response=json` | `tables[0].data` 空陣列 | ≥ 1 秒 |

- 一次回一天、全市場，沒有單檔歷史查詢：N 天就往回打 N 次。預設 3 天：上市約 5 秒、上櫃約 3 秒
- 證交所（**對過真實回應**）：`tables[0]` 是「信用交易統計」，`tables[1]` 是個股，`fields[0]` 是「代號」或「股票代號」（兩種都認）。欄位：
  代號、名稱、融資（買進、賣出、現金償還、前日餘額、今日餘額、次一營業日限額）、融券（**買進、賣出**、現券償還、前日餘額、今日餘額、次一營業日限額）、資券互抵、註記。
  沒有 `groups`。數字帶千分位，註記空白是 `" "`。例：2330 融資 34,596 ＋ 3,201 − 2,682 − 43 ＝ 35,072；融券 47 ＋ 49（賣出）− 21（買進）− 0 ＝ 75。
  融資、融券欄名重複，parse 照位置分兩組、組內照欄名找
- 櫃買（**還沒對過真實回應**）：`tables[0].data` 每列：代號、名稱、前資餘額、資買、資賣、現償、資餘額、資屬證金、資使用率、資限額、
  前券餘額、券賣、券買、券償、券餘額、券屬證金、券使用率、券限額、資券相抵、備註
- 兩邊 parse 完都用資料本身驗欄位：融資 今日 ＝ 前日 ＋ 買進 − 賣出 − 現金償還、融券 今日 ＝ 前日 ＋ 賣出 − 買進 − 現券償還；
  超過 1/5 的列對不起來就當失敗，錯誤訊息附上實際的欄名（欄位順序錯了在手機上一抓就看得出來）
- 證交所連打太快（約 1.2 秒一發、幾十發）會整個不回應一陣子（首頁照常 200）：間隔 2 秒以上，讀取逾時 8 秒
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

- **token 存在伺服器的 session 裡**：POST 一定要帶 GET 查詢頁拿到的 cookie（`JSESSIONID`）。沒帶 cookie 等於每發都是新 session，token 永遠對不上、一律回空表。
  `OkHttp` 用只放記憶體的 `MemoryCookies`，App 關掉就清掉
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
- 「資料日期： YYYY/MM/DD」在頁面裡（冒號後面有空白）
- 頁面約 500 KB、沒有 Cache-Control：一天只自動抓一次
- 健全檢查：筆數 ≥ 500、排行 1..N 連續、比重加總 99%～101%，不合就當失敗

## 國際指數（`core/indices/`，Yahoo，非官方）

`https://query1.finance.yahoo.com/v7/finance/spark?symbols=^TWII,^DJI,^GSPC,^IXIC,^SOX,^N225,^KS11,^HSI&range=1d&interval=5m`
（代號要 URL 編碼），tw.stock.yahoo.com 國際指數頁用的同一份資料。**不是官方來源、沒有公開文件**：不用金鑰，但隨時可能改格式、限流或要 cookie；
美、日、韓、港的指數沒有免費的官方分時資料，所以用它。壞了就顯示錯誤，畫面上有「在 Yahoo 股市看」開網頁版。格式：

- 一發拿全部 8 個，約 21 KB。回應 `{"spark":{"result":[{"symbol","response":[{"meta","timestamp":[…],"indicators":{"quote":[{"close":[…]}]}}]}]}}`，
  result 的順序是 Yahoo 自己排的（不照 symbols），用 symbol 對
- `range=1d` 回每個市場**自己的最近一個交易日**（假日抓到的全是上一個交易日）：5 分鐘一點（含頭尾，道瓊 09:30～16:00 共 79 點、台股 09:00～13:30 共 55 點）；
  close 可能是 null（那 5 分鐘沒成交），跳過
- meta：`regularMarketPrice`（最新／收盤）、`chartPreviousClose`（昨收）、`regularMarketTime`、`exchangeTimezoneName`、
  `tradingPeriods`（`[[{start,end}]]`，那天的盤；日本、香港的午休沒有切開）、`currentTradingPeriod.regular`
- 漲跌 ＝ `regularMarketPrice` − `chartPreviousClose`，跟網頁上的一樣。**不要用 `range=5d` 自己切**：
  前一天的最後一根 5 分鐘線不一定等於收盤價（韓國有收盤競價），漲跌會對不上
- 沒有 User-Agent 回 HTTP 429；瀏覽器的 User-Agent 正常
- 某個指數沒有點（例如開盤前）就沿用快取裡它上次那一天
- 節流：`Pacer` 2 秒（一次重新整理只打一發，這是連按的下限）。進頁面時上次抓取在 5 分鐘內就讀快取，沒有自動更新
- 快取：整份解析結果（8 個指數的點）存在 `meta` 表（`indices.days` JSON、`indices.fetchedAt`），不另開表

## 八大官股（`core/govbank/`，blave ＋ HiStock，非官方）

八家公股券商（臺銀、土銀、合庫、第一、華南、彰銀、兆豐、台企銀）所有分點的買賣加起來。官方的原始資料是證交所「買賣日報表查詢系統」
（`bsr.twse.com.tw`）與櫃買的券商買賣證券日報表，一檔一查、列出所有券商分點，但**要過圖形驗證碼**：不破解，所以用下面兩個非官方的來源
（個股先 blave，blave 沒有的上櫃股用 HiStock 的個股頁；全市場 HiStock）。
玩股網（`wantgoo.com/stock/public-bank/...`）的資料端點有裝置指紋驗證（回 400），只在內建瀏覽器頁開網頁。

### 自選股：blave

`https://api.blave.org/studio/charts/twstock/gov_bank/<代號>?start=YYYY-MM-DD&end=YYYY-MM-DD`（blave 網頁 `blave.org/studio/twstock/zh/chips/gov_bank?stock_id=2330` 自己打的端點）

- 不用登入、不用 token、不用 cookie，不帶 Origin／Referer 也回 200。blave 另外有付費的 API 方案（文件上同一份資料是
  `/studio/market/twstock/gov_bank/<代號>`、要金鑰），我們用的是網頁的這個，**沒有公開文件、隨時可能改**
- 回 `{"data":[{"bank_name":"兆豐","buy":716188,"buy_amount":1670269710.0,"date":"2026-11-02","sell":338301,"sell_amount":789166250.0,"stock_id":"2330"}, …],
  "history_tier":"anon","history_window":"3m","history_truncated":true, …}`：一列一家券商一天（日期舊到新；那天沒成交的券商沒有列），
  股數是**股**、金額是**元**。App 把同一天的八家加起來
- **只有上市股票**：上櫃股（如 6488 環球晶）回 200 ＋ 空的 `data`（其他欄位跟上市股一樣，不是付費限制），少數只有零星幾天。
  上櫃股改抓 HiStock 的個股頁（下面）
- 一發最多 31 天（`end − start ≤ 31`，超過回 400 `{"error":"Date range exceeds 31 days"}`），沒登入只給最近 3 個月：App 一發抓最近 20 天（約 14 個交易日）
- 不存在的代號回 200 ＋ 空的 `data`；文件寫 400 是代號無效、404 沒有資料、503 上游配額用完
- 當天的數字約 18:30 更新（blave 網頁上寫的）
- 節流：`Pacer` 2 秒，自選股一檔一發、照順序。沒回應、429、503、格式不對就停（後面的檔不打）；空的改抓 HiStock 的個股頁，400 只記在那一檔
- 快取：`gov_bank_days(code, date, json)`，一天一筆（八家加起來），只留最近 90 天；每檔的抓取時間在 `meta`（`govbank.fetchedAt.<代號>`）

### 自選股裡 blave 沒有的（上櫃）：HiStock 的個股頁

`https://histock.tw/stock/broker.aspx?no=<代號>`（「弘塑 (3131) 八大官股買賣超」網頁，約 90 KB，HTML；上市、上櫃都有）

- 不用登入。每天的數字嵌在 script 裡：`initalChart_Broker8({"Money1":"[[1794182400000,143], …]", …, "Count8":"…","SumCount":"[[…,1], …]","SumMoney":"[[…,291], …]","Price":"…"})`
  （函式名是 HiStock 自己的拼法）。值都是**字串**裡的 JSON 陣列，每點 [epoch 毫秒（UTC 的午夜）, 數字]，約 3 個月（58 個交易日），舊到新。
  `Money1`～`8`／`Count1`～`8` 是合庫、土銀、台銀、台企銀、彰銀、第一金、兆豐、華南永昌各自的；`SumMoney` 是合計的**萬元**、`SumCount` 是合計的**張**
  （網頁上滑過去的表頭「官股｜張｜萬元」），都捨去成整數。`Price` 是收盤價，比合計多一天（今天盤中）
- **只有淨額**（買進 − 賣出），沒有買、賣各多少。App 存成一天一筆，買超放在買的那一邊、賣超放在賣的那一邊（`GovBankDay.net`），畫面只用淨額
- **代號查不到時回 200 ＋ 台積電的頁面**（`?no=9999`）：標題 `<title>弘塑 (3131) 八大官股買賣超 - …` 裡的代號不是查的那一檔就當沒有
- 找不到標題、圖的資料、欄位就當改版：只記在那一檔（不停）；沒回應、429、其他 HTTP 錯誤就停
- 什麼時候用：blave 回空的才抓這一頁，有資料就在 `meta` 記 `govbank.via.<代號>` ＝ `HISTOCK`，之後這一檔直接抓這一頁、不再問 blave。
  兩邊都沒有（興櫃、停止交易、代號不對）不記，10 分鐘後再進來兩邊都再問一次。官股頁點這一檔開的也是這一頁
- 節流：跟全市場同一台主機，共用一個 `Pacer`（2 秒）

### 全市場：HiStock

`https://histock.tw/stock/broker8.aspx`（「八大官股銀行／公股行庫買賣超排名」網頁，約 100 KB，HTML）

- 不用登入。robots.txt 只擋 `/manage/` 之類的後台路徑
- 每天的買賣超總額嵌在頁面的 script 裡：`loadChart({"SumMoney":"[[1794182400000,102.562], …]","Price":"…"})`。
  SumMoney 是**字串**裡的 JSON 陣列，每點 [epoch 毫秒（UTC 的午夜，換成台北是同一天）, 億元]（圖例「買賣總金額(億元)」），約半年（120 點），舊到新。App 留最近 20 天
- 最新一天的買超、賣超各前 30 名：`<li title="2026-11-09 八大公股行庫合計買超 Top.1 : 台積電 (2330) 合計：213,162萬元">`
  （賣超是「合計賣超」、金額是負的「-69,172萬元」；名稱可能帶符號「國巨*」）。頁面別的地方也有帶 title 的 `<li>`，照這個格式的才收。App 留前 10 名、畫面顯示前 5
- 找不到 SumMoney 或是空的就當改版（失敗）；找不到排行只是沒有排行
- `?d=YYYY-MM-DD` 可以看以前某一天的排行，App 沒用
- 節流：`Pacer` 2 秒（一次只打一發，跟個股頁共用）。快取：整份解析結果存 `meta`（`govbank.market` JSON、`govbank.market.fetchedAt`）

### 什麼時候抓（兩個一起，進官股頁時）

- 「該公布的那天」：平日 18:30（台北）以後是今天，以前是前一個平日；週末是星期五。國定假日不知道
- 全市場：快取的最新一天 ≥ 該公布的那天就不抓；不然抓，還是沒有（還沒公布、國定假日）10 分鐘內不再問
- 個股的目標日：全市場這次有拿到就用它的最新一天（假日、還沒公布都跟著它，不白打 blave），拿不到才用該公布的那天。
  快取的最新一天 ≥ 目標日就不抓；不然抓，還是沒有（那天沒成交、blave 比 HiStock 晚更新）10 分鐘內不再問
- 重新整理：全部重抓

## 社群討論區（只開網頁）

`https://www.cmoney.tw/forum/stock/<代號>`，在內建瀏覽器頁開給人看，程式不抓、不用 token。

## 股票名稱表（`core/src/main/resources/stock_names.json`）

`tools/stock_names/fetch_stock_names.py` 從公開端點產生（上市／上櫃／興櫃公司簡稱、上市櫃行情補 ETF；權證濾掉；指數與期貨代號寫死），約 3,100 檔、80 KB。
新股或新 ETF 掛牌才需要重跑；檔案壞掉退回空表，只是沒名稱。
