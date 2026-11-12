# StockDesk

看台股自選股的 Android App（Kotlin ＋ Jetpack Compose）：goodinfo 快速連結 ＋ 官方原始資料（融資融券、股東持股分級）、社群討論區（內建瀏覽器開網頁）、
八大官股買賣超（HiStock、blave，非官方）、國際指數走勢（Yahoo，非官方）、加權指數權重；自選股自己加減。
在 claude.ai/code 雲端 session 開發；VM 沒有模擬器，模擬器上的測試與錄影交給 GitHub Actions（`.github/workflows/record.yml`）。

## 文件

- `docs/spec.md`：每個功能怎麼運作。**改功能之前先讀對應的段落**，改了行為要一起更新
- `docs/data-sources.md`：資料來源的端點、欄位、節流、休市判斷、快取單位
- `docs/testing.md`：測試清單與寫法、CI 錄影（`media` branch）、fixture。**改測試或 `record.sh` 之前先讀**
- `docs/ideas.md`：跟使用者討論過、之後可能做的，開工前先確認
- `docs/cloud-setup.md`：雲端環境設定，改了 `scripts/cloud-setup.sh` 或網路設定要一起更新
- `docs/debug-vs-release.md`、`docs/release-key.md`：兩種版本的差別、簽章、發版

## 目前的進度

功能都做了，VM 內 build 與 JVM 測試都過；還沒發第一個正式版。

- **還沒在手機上驗證**：集保持股分級（session cookie）、內建瀏覽器與 uBO 的安裝、八大官股。uBO 在 CI 模擬器的 logcat 看得到裝好，但沒有測試檢查
- **還沒對過真實回應**：櫃買、集保的 HTML；權重只看過轉成文字的頁面（`Samples` 照同一份筆記造，擋不下欄位順序的錯）
- 官方來源的 fixture 還沒錄（使用者說先不用，沒有 fixture 的測試會 skip）

## 範圍

**不做**（這個 App 的定義）：

- 廣告、任何第三方 SDK（Firebase、Analytics、Crashlytics、推播）、自家後端
- 社群要 token 的 API：只在內建瀏覽器開討論區網頁，不登入、不存 token、不替使用者填帳密
- 背景抓取：前景服務、WorkManager、定期更新、通知
- 程式抓 goodinfo（Cloudflare 驗證，回 403）、玩股網的資料端點（裝置指紋驗證，回 400）：只在內建瀏覽器開網頁
- INTERNET 以外的任何權限

**先不做**（要做之前先問使用者）：個股的行情（K 線）、走勢（分時）、籌碼（日報／週報）的資料來源；用 API 把社群文章抓回 App；拖曳排序；
App 內更新股票名稱表；錄 fixture；換發版金鑰。

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
  indices/  IndicesSource：Yahoo v7/finance/spark；8 個國際指數最近一個交易日的 5 分鐘走勢，一發拿全部
  govbank/  GovBankSource：HiStock 全市場一發 ＋ blave 自選股一檔一發（上櫃股改抓 HiStock 個股頁）
  store/    Sql 介面、WatchlistStore（自選股）、CacheStore（快取）
  http/     Http 介面與 OkHttp 實作、Pacer（節流）、Taipei（時區）
  names/    StockNames（resources/stock_names.json）     links/ Links（各網站的網址）
```

- **core 不能 import android.\***（Kotlin JVM module，compile 就會擋）。和 Android 無關的邏輯一律放 core、用 JVM 測試
- 資料來源都透過 `Http` 介面發請求，base URL 都能換：JVM 測試用 `FakeHttp`，模擬器用 `FakeSources`（MockWebServer，
  `StockDeskApplication.useEndpointsForTest` 要在第一次用到 `container` 之前呼叫）。`today`、`now`、`Pacer` 都能注入，測試不看真的時鐘
- **節流是硬規則**：每個主機一個 `Pacer`（證交所 2 秒、櫃買 1 秒、集保 1 秒、Yahoo／blave／HiStock 各 2 秒，另加 0～25% 隨機），讀快取不算；
  一次只跑一個對外的抓取（`FetchJobs.busy`），不要平行。goodinfo 的「全部抓取」（`FetchJobs.fetchAll`）也是照順序一檔一檔，檔與檔之間另加隨機的停頓。逾時抓短（連線 5 秒、讀取 8 秒），沒回應就停、提示可能被限流
- 「今天沒資料」可能只是還沒公布，不記成休市，只在記憶體記 10 分鐘
- **Android 9 的 SQLite 是 3.22**：不要用 UPSERT、window function、`RETURNING`。改 schema 用 `ALTER TABLE ADD COLUMN` 或在 store 的 init 判斷，手機上的自選股不能丟
- **Android 的 regex 是 ICU**：`{`、`}` 一定要跳脫（JVM 接受沒跳脫的 `}`；寫在 companion 裡整個 class 載入就失敗）
- 上面兩條 JVM 測試擋不下來：新的 SQL、新的解析都要在 `DeviceTest` 跑一次
- 資料庫與網路一律在 `Dispatchers.IO`，不在組畫面時讀資料庫（`produceState`／`LaunchedEffect` 裡 `withContext(IO)`）
- 抓取的狀態在 `FetchJobs`（`AppContainer.scope`），不在組合裡。`scope` 沒有例外處理器，`launch` 裡的例外一定要接住
- 導覽：四個分頁 ＋ 一層蓋頁（`Overlay`），`rememberSaveable` 存字串 key，不引入 Navigation 套件
- 整個 App 一個 `OkHttpClient`（`AppContainer.http`），用到才建；cookie 只放記憶體（`MemoryCookies`），不寫檔
- **`Application.onCreate` 什麼都不做**：GeckoView 的網頁內容子行程也跑 `StockDeskApplication`，不能碰 `container`
- 內建瀏覽器：`GeckoRuntime` 一個行程只能有一個（`AppContainer.gecko`，主執行緒建）；分頁 `BrowserTab` 放在 `AppContainer`、不放在組合裡，
  delegate 都在主執行緒被呼叫、直接改 Compose 的狀態。GeckoView 的網路層不看 Android 的 network security config。不要加任何自動登入、自動填表

## 專案約定

- Gradle 根目錄就是 repo 根目錄；兩個 module：`:core`、`:app`
- applicationId / namespace `com.twtrubiks.stockdesk`：**不要改**（手機會當成另一個 App，自選股接不回來；`scripts/ci/record.sh` 也依賴它）
- **minSdk 28（Android 9）**，compileSdk 37.2（GeckoView 要求：`compileSdk { version = release(37) { minorApiLevel = 2 } }`），targetSdk 36，Java／Kotlin 17
- 依賴版本集中在 `gradle/libs.versions.toml`；build 報版本相容問題時，以錯誤訊息為準調整
- AGP 9.x 用 built-in Kotlin：app **不要**套 `org.jetbrains.kotlin.android`；core 套 `org.jetbrains.kotlin.jvm`；Compose、serialization 外掛版本跟 Kotlin 一樣
- **GeckoView 要跟著升**（約 4 週一版、帶安全修補）：只放在 `maven.mozilla.org`，最新版看
  `https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/maven-metadata.xml` 的 `<release>`，改 `libs.versions.toml` 再發一版
- **APK 按 CPU 拆開**（GeckoView 每種 CPU 一份引擎）：只出 `arm64-v8a`（手機，release.yml 發）與 `x86_64`（CI 模擬器），輸出 `app-<abi>-<debug|release>.apk`
- **uBlock Origin 不打包進 APK**：從 AMO 裝有簽章的版本（`Ublock.AMO_URL`），見 `docs/spec.md` 的「內建瀏覽器」
- release 版開 R8；依賴都自帶 R8 規則，`app/proguard-rules.pro` 是空的
- 備份只含資料庫與 `settings`，不含 GeckoView 的設定檔（超過 25 MB 整份不備份，自選股也沒了）；`backup_rules.xml` 與 `data_extraction_rules.xml` 兩份要一樣
- `MainActivity` 的 `configChanges` 包含轉螢幕：不重建 Activity
- 發版：推 tag `v<版本>` 或改 `release-version`（語意化版本）。**發版金鑰已經產生，不要再產生、不要換**。
  給使用者裝的 APK 從 GitHub Release 來；VM 的 debug 金鑰每個 session 都不一樣，簽章對不上只能移除重裝（**會刪掉自選股**：先匯出）
- 股票名稱表 `stock_names.json` 由 `tools/stock_names/fetch_stock_names.py` 產生，新股掛牌才需要重跑

## 雲端 VM 環境

- Android SDK 在 `/opt/android-sdk`（`scripts/cloud-setup.sh` 裝），沒裝好先看 `/var/log/android-sdk-setup.log`
- 第一次 build 遇到 Maven Central 429（錯誤訊息常是 `Plugin [id: '...'] was not found`、`Could not resolve ...`）：隔 20 秒重跑，不要改版本
- 連不到證交所、櫃買、集保、期交所與 AMO（uBO 只有真機裝得到）；連得到 Yahoo。測試一律靠 `FakeHttp`／MockWebServer 與 fixture
- `gh pr`／`gh issue` 不能用（GraphQL 被擋），改用 `gh api` 打 REST

## 在 VM 內驗證（每次 push 前）

```bash
./gradlew test assembleDebug assembleRelease assembleDebugAndroidTest
```

## 分支與 PR

- GitHub：`twtrubiks/stockdesk`（public）。推到哪個分支照使用者說的（現在直接在 `main` 上做）。雲端 session 的 git 只能推自己的 `claude/…` 分支，使用者要求才開 PR，CI 過了才合併
- 合併 PR 用 **Rebase and merge**：合併後要在同一個分支繼續做，先 `git checkout -B <分支> origin/main`，再 `git push --force-with-lease`。
  force push、改寫 `main` 的歷史都要先問使用者
- commit 訊息不加共同作者、不加 claude.ai/code session 的連結
