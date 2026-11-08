# 測試、錄影、fixture

寫或改 `app/src/androidTest/`、`scripts/ci/record.sh`、fixture 之前先讀；每次 push 前要跑的指令在 `CLAUDE.md` 的「在 VM 內驗證」。

## JVM 測試（主要的測試）

- `core/src/test/`：
  - 抓取流程（不連網）：`MarginSourceTest`、`HoldersSourceTest`、`WeightsSourceTest`、`IndicesSourceTest`。`FakeHttp` 回 `Samples` 造的回應，快取是真的 SQLite（`JdbcSql`），
    節流走 `runTest` 的虛擬時間（`Pacer(now = { testScheduler.currentTime })`，請求間隔看得到、不用真的等）。
    測市場判斷、休市與「今天還沒公布」、快取命中、一邊沒回應、集保 token 串接與失效（重拿查詢頁再試一次）、權重一天一次與重抓失敗顯示舊的、國際指數 5 分鐘內讀快取與某個指數沒點時沿用舊的、每個主機的節流
  - 解析：`MarginParseTest`、`HoldersParseTest`、`WeightsParseTest`、`IndicesParseTest`（吃 VM 裡錄的 Yahoo 真實回應 `yahoo-spark.json`，數字對照當時的 Yahoo 網頁）。名字有 `Real`／`formPage…`／`weekPage…` 的吃 fixture（不在就 `Assume` skip，不算失敗），
    其他用 `Samples` 測千分位、挑對表、15 級合併、健全檢查、壞掉的回應
  - store 與小東西：`CacheStoreTest`、`WatchlistStoreTest`（sqlite-jdbc 記憶體資料庫）、`PacerTest`、`SmallPartsTest`（代號、打包的名稱表、台北時間、網址）
  - `OkHttpTest`：真的 OkHttp 對本機 MockWebServer（表單編碼、4xx、斷線、櫃買日期的斜線）
  - `HoldersOverOkHttpTest`：真的 OkHttp 對 MockWebServer 上的 `FakeTdcc`：session cookie 有帶回去、沒帶 cookie 會被擋、session 過期重來。
    `FakeHttp` 不經過 OkHttp，cookie 的錯只有這裡測得到
  - `FakeHttp`：照網址回固定內容（後設定的優先）、記下每個請求的方法／網址／表單／時間；`JdbcSql`：測試用的 `Sql`
- `core/src/testFixtures/`：`Samples`，照 `data-sources.md` 的實測格式**造**的官方回應（證交所、櫃買、集保、期交所）；
  `FakeTdcc`，照集保的規則回應的假伺服器邏輯（token 綁 session cookie、每發換新），`HoldersOverOkHttpTest` 與 `FakeSources` 共用。
  core 的 JVM 測試與模擬器上的 `FakeSources` 共用（app 用 `androidTestImplementation(testFixtures(project(":core")))`）。
  **只測流程**：欄位順序跟 parse 照的是同一份筆記，對錯一起，欄位順序本身要靠 fixture 測試
- `app/src/test/`：`OverlayTest`（蓋頁的 key 轉換）、`FormatTest`（數字格式，跟手機語言無關）、`BrowserRulesTest`（瀏覽器頁放行哪些 scheme、錯誤頁）
- 真的連線只在使用者的電腦上發生（錄 fixture）；雲端 VM 與 CI 都不連這些站

## fixture（`core/src/test/resources/fixtures/`）

雲端 VM 連不到證交所、櫃買、集保、期交所。**使用者說先不用錄**；要錄的時候在**使用者的電腦**上跑一次：

```bash
python tools/fixtures/fetch_fixtures.py
```

抓下來的檔案與用途在該資料夾的 `README.md`。放進 repo 前：

1. 對一次欄位順序，把 `MarginParseTest`、`HoldersParseTest` 裡的 TODO 換成對照官方網頁的具體數字
2. 每個檔案 1 MB 以內

## 模擬器上的測試（CI）

`record.sh` 依序：`DeviceTest`（不錄影）→ `DemoRecordingTest`（錄影）→ release 版冒煙測試（打開沒閃退、看到底部分頁列的 `tab_goodinfo`）。
模擬器**不連外**（uBO 從 AMO 安裝只有在真機上才會發生，CI 沒有測；內建瀏覽器頁在 CI 只看得到標題列與錯誤頁）。
APK 按 CPU 拆開（GeckoView），模擬器裝的是 `app-x86_64-debug.apk`／`app-x86_64-release.apk`；模擬器記憶體給 3 GB（GeckoView 另外開網頁內容的行程）。

- `DeviceTest`：Android 9 內建的 SQLite（3.22）跑 `WatchlistStore`／`CacheStore` 的**每一條** SQL 與巢狀 transaction 的 rollback
  （JVM 的 sqlite-jdbc 版本新很多，新語法擋不下來）。store 加了新的 SQL 就要在這裡呼叫到。用另一個資料庫檔名。
  另外驗 Android 上的數字格式（`Locale.ROOT` 在 JVM 有千分位、在 Android 沒有，JVM 的 `FormatTest` 擋不下來）
- `DemoRecordingTest`：展示流程，用 UiAutomator 操作（真實時間、動畫會播）。不要用 Compose test rule、不要用 `ActivityScenario`，
  找元件用 `UiTest.waitFor` 每 0.1 秒輪詢。元件找到之後畫面又重組了，按之前要重新找一次。
  用 `By.text(...)`，要用 testTag 時用 `res("tag")`（root 已開 `testTagsAsResourceId`）。
  每個操作之間停 1～1.5 秒；全長控制在 150 秒內（`screenrecord` 上限 180 秒）。
  流程：自選股（加 2330、6515）→ goodinfo（2330 的融資融券、持股分級按「抓」）→ 社群（點一檔開內建瀏覽器頁再關掉；模擬器沒網路，只看得到標題列與錯誤頁）
  → 大盤（國際指數 → 權重（進頁面自動抓）→ 官股，點一列開內建瀏覽器頁再關掉）→ 設定。
  瀏覽器開在畫面上時 adb 的 `screencap` 可能卡住，流程裡開瀏覽器頁的那幾步不截圖
- 假來源 `FakeSources`：模擬器上的 MockWebServer（綁 127.0.0.1），回 `Samples` 現造的回應（數字是假的；集保走 `FakeTdcc`，App 沒帶 cookie 就回空表）。`DemoRecordingTest` 在 `@Before` 裡
  `app.useEndpointsForTest(fake.start())`：**要在第一次用到 `container` 之前**（`UiTest.container` 是 getter，用到才建）。
  App 連 `http://localhost` 靠 debug 版才有的 `src/debug/res/xml/network_security_config.xml`（只放行 localhost／127.0.0.1 的明文 HTTP；release 版沒有）。
  fixture 錄好之後可以改成回錄好的真實回應
- release 冒煙測試沒有測試 APK，靠 `uiautomator dump` 找 `tab_goodinfo`

### testTag 一覽

`tab_goodinfo`／`tab_forum`／`tab_market`／`tab_watchlist`（底部分頁）、`market_indices`／`market_weights`／`market_public_bank`（大盤的切換鈕）、`settings_button`、`code_input`／`add_button`／`candidates`／`rename_input`／`rename_confirm`（自選股）、
`fetch_margin_<代號>`／`fetch_holders_<代號>`（goodinfo 卡片上的「抓」）、`card_header_<代號>`（goodinfo 卡片的代號名稱列，點了收合／展開）、`expand_all`／`collapse_all`（goodinfo 標題列）、`forum_<代號>`（社群的每一列）、`public_bank_all`／`public_bank_twse`／`public_bank_tpex`（官股的三列）、`browser_close`／`browser_external`／`browser_web`／`ublock_button`／`ublock_badge`／`ublock_popup`（內建瀏覽器頁）、`weights_refresh`／`indices_refresh`（大盤標題列的重新整理）、`index_<代號去掉 ^>`（國際指數的卡片，例如 `index_TWII`）、`indices_yahoo`（在 Yahoo 股市看）。

## 看 CI 的結果

`record.yml` 把結果 force-push 到 `media` branch：`demo.mp4`、`frames/`（每 2 秒一張影格）、`screenshots/`、
`gradle-build.txt`、`device-test.txt`、`instrument.txt`、`logcat*.txt`、`info.txt`（branch、commit、run 連結、狀態）。
雲端 session 用 `git fetch origin media && git show origin/media:info.txt` 讀，影格用 `git show origin/media:frames/010.png > /tmp/f.png` 再看圖。

README 的「畫面」用的是這裡的截圖，縮成 400 寬放在 `docs/screenshots/`：畫面改了、CI 跑完之後在 repo 根目錄跑 `scripts/readme-screenshots.sh`
（哪一張對到 README 的哪一格寫在 script 裡；改了 `DemoRecordingTest` 的截圖名稱要一起改）。
