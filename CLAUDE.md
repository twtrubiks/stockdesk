# StockDesk

看台股自選股的 Android App：goodinfo 快速連結 ＋ 官方原始資料（融資融券、股東持股分級）、個股社群討論區與八大官股買賣超（內建瀏覽器頁開網頁）、國際指數走勢（Yahoo）、加權指數權重；自選股自己加減。
Kotlin + Jetpack Compose 原生 App，在 claude.ai/code 雲端 session 開發。
雲端 VM 沒有模擬器，模擬器操作與錄影交給 GitHub Actions（`.github/workflows/record.yml`）。
VM 連不到證交所、櫃買、集保、期交所：解析與流程的測試吃錄好的真實回應（fixture），真的回應在使用者的電腦上錄（使用者說先不用錄，沒有 fixture 的測試會 skip）。

## 目前的進度

功能都做了，VM 內 build 與 JVM 測試都過；模擬器上的結果看 CI（`media` branch，見 `docs/testing.md`）。還沒發第一個正式版。

- `core`（純 Kotlin、JVM 測試）：`StockRef`／`Market`／`StockCode`、`StockNames`（打包的代號→名稱表）、`Http`／`OkHttp`、`Pacer`（節流）、`Taipei`（時區）、
  `MarginSource`（證交所／櫃買）、`HoldersSource`（集保）、`WeightsSource`（期交所）、`IndicesSource`（Yahoo 國際指數，非官方），`Sql`／`WatchlistStore`／`CacheStore`、`Links`（goodinfo、討論區網址）
- `app`：四個分頁 `GoodinfoScreen`、`ForumScreen`、`MarketScreen`（大盤：切換鈕切 `IndicesPage` 國際指數、`WeightsPage` 權重、`PublicBankPage` 八大官股，只開玩股網的網頁）、`WatchlistScreen`，蓋頁 `BrowserScreen` 與 `SettingsScreen`；
  內建瀏覽器是 GeckoView ＋ uBlock Origin：`BrowserTab`（GeckoSession 與 delegate）、`Ublock`（從 AMO 裝、更新、badge、popup）、
  `BrowserRules`（scheme、錯誤頁，JVM 測試）；`AppContainer`（`StockDeskApplication`）、`FetchJobs`（抓取狀態，不在組合裡）、`AndroidSql`
- 測試（清單與寫法在 `docs/testing.md`）：core 的抓取流程、解析、store 用 `FakeHttp` ＋ `Samples`（testFixtures，照實測格式造的假回應）＋ `JdbcSql`，
  吃 fixture 的幾個沒有就 skip；app 的 `OverlayTest`、`FormatTest`、`BrowserRulesTest`；模擬器 `DeviceTest`（Android 9 SQLite）、`DemoRecordingTest`（「抓」走 `FakeSources`）
- **還沒在手機上驗證的**：集保持股分級（session cookie 的修法）、內建瀏覽器與 uBO 的安裝；**櫃買、集保的 HTML 還沒對過真實回應**，權重只看過轉成文字的頁面
  （`Samples` 照同一份筆記造，對錯一起，擋不下欄位順序的錯；融資融券的 parse 會用餘額驗欄位）。
  CI 的模擬器連得到 AMO、logcat 看得到 uBO 裝好，但沒有測試去檢查它
- **還沒做**：fixture 還沒錄（使用者說先不用；要錄用 `tools/fixtures/fetch_fixtures.py`，見 `docs/testing.md`）

## 規格

每個功能該怎麼運作寫在 `docs/spec.md`，資料來源的端點、欄位、節流、休市判斷在 `docs/data-sources.md`。**改功能之前先讀對應的段落**，改了行為要一起更新。

### 不做（這個 App 的定義）

- 廣告、任何第三方 SDK（Firebase、Analytics、Crashlytics、推播）、自家後端
- 社群要 token 的 API：社群只在內建瀏覽器頁開討論區網頁，不登入、不存 token、不替使用者填帳密
- 背景抓取：前景服務、WorkManager、定期更新、通知
- 抓 goodinfo 本身（前面有 Cloudflare 驗證，程式抓會回 403；資料改抓官方來源）；抓玩股網的八大官股資料端點（裝置指紋驗證，回 400；只在內建瀏覽器頁開網頁）
- 任何 INTERNET 以外的權限

### 先不做（要做之前先問使用者）

個股的行情（K 線）、走勢（分時）、籌碼（日報／週報）這三個資料來源（國際指數的當天走勢已經用 Yahoo 做了，見 `docs/data-sources.md`）；用 API 把社群文章抓回 App 內看（要 token）；拖曳排序；股票名稱表在 App 內更新；
錄 fixture；換發版金鑰（已經產生，不要再產生）。

### 之後可能會做

跟使用者討論過、記下來的想法在 `docs/ideas.md`。開工前先跟使用者確認。

## 程式的分層

```
app（Android、Compose）
  GoodinfoScreen / ForumScreen / MarketScreen（IndicesPage・WeightsPage・PublicBankPage）/ WatchlistScreen / BrowserScreen（GeckoView ＋ uBO：BrowserTab、Ublock、BrowserRules）/ SettingsScreen
  StockDeskApp（底部四個分頁 ＋ 一層蓋頁 Overlay）  FetchJobs（抓取狀態）  AndroidSql  AppContainer
      │ 只透過 core 的 source、store 與 model
core（純 Kotlin，沒有 Android）
  margin/   MarginSource：證交所 MI_MARGN、櫃買 margin/balance；一天一次全市場、只存自選股那幾列
  holders/  HoldersSource：集保 qryStock；一檔一週、token 每發換新、要照順序串
  weights/  WeightsSource：期交所 futuresQADetail；整張表、一天一次
  indices/  IndicesSource：Yahoo v7/finance/spark（非官方）；8 個國際指數最近一個交易日的 5 分鐘走勢，一發拿全部
  store/    Sql 介面、WatchlistStore（自選股）、CacheStore（快取）
  http/     Http 介面與 OkHttp 實作、Pacer（節流）、Taipei（時區）
  names/    StockNames（resources/stock_names.json）     links/ Links（goodinfo、討論區網址）
```

- **core 不能 import android.\***（它是 Kotlin JVM module，compile 就會擋）。要碰 Android 的放 app
- 資料來源都透過 `Http` 介面發請求，base URL 都能換：JVM 測試用 `FakeHttp` 回 `Samples` 或 fixture，模擬器用 `FakeSources`（MockWebServer，
  `StockDeskApplication.useEndpointsForTest` 在第一次用到 `container` 之前換網址）。`today`、`now`、`Pacer` 都能注入，測試不看真的時鐘
- **節流是硬規則**：每個主機一個 `Pacer`（證交所 2 秒、櫃買 1 秒、集保 1 秒、Yahoo 2 秒，另加 0～25% 隨機），讀快取不算；
  一次只跑一個對外的抓取（`FetchJobs.busy`），不要平行。逾時抓短（連線 5 秒、讀取 8 秒），沒回應就停、提示可能被限流
- 快取（`CacheStore`）跟來源的單位一樣：融資融券 (市場, 日期) 只存自選股的列、持股分級 (代號, 週)、權重整張表一天一次、國際指數整份（進頁面 5 分鐘內讀快取，沒有自動更新）。
  「今天沒資料」可能只是還沒公布，不記成休市，只在記憶體記 10 分鐘
- `Sql` 介面：手機上 `AndroidSql`（`databases/stockdesk.db`），JVM 測試 `JdbcSql`。**Android 9 的 SQLite 是 3.22**：不要用 UPSERT、window function、`RETURNING`，
  JVM 測試擋不下來，靠 `DeviceTest`。改 schema 用 `ALTER TABLE ADD COLUMN` 或在 store 的 init 判斷，手機上的自選股不能丟
- 資料庫與網路一律在 `Dispatchers.IO`，不在組畫面時讀資料庫（`produceState`／`LaunchedEffect` 裡 `withContext(IO)`）
- 抓取的狀態在 `FetchJobs`（`AppContainer.scope`），不在組合裡：切分頁、轉螢幕不會中斷。`scope` 沒有例外處理器，`launch` 裡的例外一定要接住
- 導覽：底部 `NavigationBar` 四個分頁 ＋ 一層蓋頁（`Overlay`：內建瀏覽器、設定），`rememberSaveable` 存字串 key，不引入 Navigation 套件。大盤分頁選到哪一頁（`MarketPage`）也存在 `StockDeskApp`
- 內建瀏覽器 `BrowserScreen`：GeckoView ＋ uBlock Origin。`GeckoRuntime` 一個行程只能有一個，放在 `AppContainer.gecko`，第一次用到才建（主執行緒）；
  GeckoView 會另外開網頁內容的子行程，子行程也跑 `StockDeskApplication`，所以 `onCreate` 裡不能碰 `container`。
  分頁 `BrowserTab` 放在 `AppContainer`（key 是蓋頁的 key，關掉蓋頁時 `closeTab`），不放在組合裡：轉螢幕不重新載入；delegate 都在主執行緒被呼叫，直接改 Compose 的狀態。
  第一頁出現 2 秒後 `warmUpBrowser` 先把引擎與 uBO 開起來（`isLowRamDevice` 不做）。GeckoView 自己的網路層不看 Android 的 network security config。
  返回鍵先回網頁上一頁；goodinfo 的 Cloudflare 驗證過不了時，標題列有「在瀏覽器開啟」丟給手機的瀏覽器。不要加任何自動登入、自動填表
- 整個 App 一個 `OkHttpClient`（`AppContainer.http`），第一次要抓資料時才建；`Application.onCreate` 什麼都不做。
  cookie 只放記憶體（`MemoryCookies`，集保的 token 綁 session cookie），不寫檔

## 專案約定

- Gradle 專案根目錄就是 repo 根目錄，`./gradlew` 在這裡跑；兩個 module：`:core`、`:app`
- applicationId / namespace：`com.twtrubiks.stockdesk`（`scripts/ci/record.sh` 依賴這個值）。**不要改**：一變手機會當成另一個 App，自選股就接不回來
- **minSdk 28（Android 9）**，compileSdk 37.2（GeckoView 159 要求，`compileSdk { version = release(37) { minorApiLevel = 2 } }`；缺 `platforms;android-37.2` 時 AGP 會自動下載），
  targetSdk 36，Java / Kotlin target 17（`java.time` 在 API 26 就內建，不需要 desugaring）
- 依賴版本集中在 `gradle/libs.versions.toml`（GeckoView 也是）；build 報版本相容問題時，以錯誤訊息為準調整
- **GeckoView**（`org.mozilla.geckoview:geckoview`）只放在 `maven.mozilla.org`（`settings.gradle.kts` 只讓這個 group 從那裡拿）。
  它就是瀏覽器的引擎，約每 4 週一版、帶安全修補：**要跟著升**。最新版看
  `https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/maven-metadata.xml` 的 `<release>`，改 `libs.versions.toml` 再發一版
- **APK 按 CPU 拆開**（`splits.abi`）：GeckoView 每種 CPU 一份引擎，整包 500 MB 以上，只出 `arm64-v8a`（手機，release.yml 發這份）
  與 `x86_64`（CI 的模擬器，record.sh 裝這份）。輸出是 `app-<abi>-<debug|release>.apk`。
  native library 用壓縮的打包（`useLegacyPackaging`）：arm64 的 release 版約 95 MB
- **uBlock Origin 不打包進 APK**：從 AMO 裝有 Mozilla 簽章的版本（`Ublock.AMO_URL`），裝好的留在 GeckoView 的設定檔；一天最多問一次 AMO 有沒有新版。詳見 `docs/spec.md` 的「內建瀏覽器」
- AGP 9.x 使用 built-in Kotlin：app **不要**套 `org.jetbrains.kotlin.android`；core 是 Kotlin JVM module，套 `org.jetbrains.kotlin.jvm`；
  Compose 套 `org.jetbrains.kotlin.plugin.compose`，JSON 套 `org.jetbrains.kotlin.plugin.serialization`，版本都跟 Kotlin 一樣
- OkHttp、jsoup、kotlinx.serialization、GeckoView 都自帶 R8 規則，`app/proguard-rules.pro` 目前是空的
- `android:allowBackup="true"`：只備份資料庫與 `settings`，GeckoView 的設定檔（`files/mozilla/`，有 uBO 的過濾清單、cookie）不備份：自動備份每個 App 只有 25 MB，
  超過就整份不備份、自選股也跟著沒了（`backup_rules.xml` 給 Android 11 以前、`data_extraction_rules.xml` 給 12 以後，兩份要一樣）
- `MainActivity` 的 `configChanges` 包含轉螢幕：不重建 Activity，Compose 自己重排，GeckoView 不用重接畫面
- release 版開 R8（minify + shrinkResources）。簽名看環境變數：有 `STOCKDESK_KEYSTORE`、`STOCKDESK_KEYSTORE_PASSWORD`
  就用發版金鑰（PKCS12，alias `stockdesk`）；沒有就用這台機器的 debug 金鑰
- **給使用者裝的 APK**：正式版從 GitHub Release 來（`docs/debug-vs-release.md`）。VM 的 debug 金鑰每個 session 都不一樣，簽章對不上的 APK 只能移除重裝
  （**移除會刪掉自選股**：先匯出）
  - 發版：`.github/workflows/release.yml`。版本號用語意化版本。推 tag `v<版本>`，或把 `release-version` 改成新版本推上去
  - **發版金鑰已經產生**（`docs/release-key.md`）：檔案與密碼在使用者手上、放在 Secrets，`EXPECTED_CERT` 已經填好。**不要再產生、不要換**
  - versionCode 是 2026-01-01 起算的分鐘數（workflow 算好用環境變數帶進來）
  - README 的下載連結是 `releases/latest/download/stockdesk.apk`
- 股票名稱表 `core/src/main/resources/stock_names.json` 由 `tools/stock_names/fetch_stock_names.py` 產生（證交所、櫃買公開端點），新股或新 ETF 掛牌才需要重跑

## 雲端 VM 環境

- Android SDK 由 `scripts/cloud-setup.sh` 裝在 `/opt/android-sdk`，`ANDROID_HOME` 由環境變數提供；SDK 不存在時先看 `/var/log/android-sdk-setup.log`
- 新 session 第一次 build 可能遇到 Maven Central `429 Too Many Requests`，隔 20 秒重跑 `./gradlew` 即可；
  錯誤訊息常是 `Plugin [id: '...'] was not found`、`Could not resolve ...`，加 `--info` 才看得到 429。先重跑，不要改版本
- VM 連不到證交所、櫃買、集保、期交所：測試一律靠 `FakeHttp`／MockWebServer 與 fixture。VM 連得到 Yahoo（國際指數），`fixtures/yahoo-spark.json` 是在 VM 錄的。fixture 在使用者的電腦上用 `tools/fixtures/fetch_fixtures.py` 錄（見 `docs/testing.md`）
- 網路要放行 `maven.mozilla.org`（GeckoView）；AMO（uBO）連不到，uBO 只有真機上裝得到
- `gh pr`／`gh issue` 不能用（GraphQL 被擋），改用 `gh api` 打 REST

## 在 VM 內驗證（每次 push 前）

```bash
./gradlew test assembleDebug assembleRelease assembleDebugAndroidTest
```

`test` 跑 core 與 app 的 JVM 測試；`assembleRelease` 會跑 R8。和 Android 無關的邏輯一律放 core、用 JVM 測試，幾秒就有結果。

## 分支與 PR

- GitHub：`twtrubiks/stockdesk`（public）。推到哪個分支照使用者說的（現在直接在 `main` 上做）。雲端 session 的 git 只能推自己的 `claude/…` 分支，使用者要求才開 PR，CI 過了才合併
- 合併 PR 用 **Rebase and merge**：`main` 上的 commit 會換成新的 hash。合併後要在同一個分支繼續做，
  先 `git checkout -B <分支> origin/main`，再 `git push --force-with-lease`。force push、改寫 `main` 的歷史都要先問使用者
- commit 訊息不加共同作者註解

## 測試與錄影

模擬器上的測試（`DeviceTest`、`DemoRecordingTest`、release 冒煙測試）怎麼寫、CI 的錄影怎麼看（`media` branch）、fixture 怎麼錄，都在 `docs/testing.md`。
**改測試或 `record.sh` 之前先讀**。

## 環境設定

claude.ai/code 的環境設定步驟寫在 `docs/cloud-setup.md`，改了 `scripts/cloud-setup.sh` 或網路設定要一起更新。
