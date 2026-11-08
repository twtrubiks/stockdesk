# 在 claude.ai/code 設定雲端開發環境

全程在 [Claude Code 雲端 session](https://claude.ai/code) 開發，不需要在自己的電腦安裝 Android Studio、SDK 或模擬器。
看畫面這一步交給 GitHub Actions 的模擬器錄影（`.github/workflows/record.yml`）；真的資料回應在自己電腦上用 `tools/fixtures/fetch_fixtures.py` 錄。

**已經有其他 Android 專案用的雲端環境可以直接沿用**：只要 `ANDROID_HOME=/opt/android-sdk` 且網路放行 `dl.google.com`，
少的 platform（這裡是 android-37.2）AGP 會自己下載。另外要放行 `maven.mozilla.org`（GeckoView 只放在那裡）。沒有的話照下面三步設定一次。

## 1. 連 GitHub

1. 到 <https://claude.ai/connect-github> 連結 GitHub 帳號。
2. 確認 **Claude GitHub App** 已經裝在這個 repo 上，沒裝的話 session 無法 push。
   可以在同一頁安裝，或到 <https://github.com/apps/claude/installations/select_target> 安裝。

## 2. 新增一個環境

在 claude.ai/code 開新 session 的畫面，點訊息框下方那排的第一個控制項（環境選單），選最下面的 **New environment**，填好以下三項後按 **Add environment**。

| 設定 | 值 |
| --- | --- |
| **Network access** | 選 **Custom**，在 **Allowed domains** 加入 `dl.google.com` 與 `maven.mozilla.org`，並勾選 **Also include default list of common package managers**。<br>只是想先測試的話，可以直接選 **Full**。 |
| **Environment variables** | `ANDROID_HOME=/opt/android-sdk` |
| **Setup script** | 按 **Choose file**，選 `scripts/cloud-setup.sh`。 |

每一項為什麼這樣設：

- **`dl.google.com`**：Android SDK 和 Google Maven（AGP、AndroidX）實際都從這個網域下載，但它不在預設清單裡。
  預設清單已經包含 Maven Central、`maven.google.com` 和 Gradle 的網域。
- **`maven.mozilla.org`**：內建瀏覽器的引擎 GeckoView 只放在 Mozilla 自己的 Maven。AMO（uBlock Origin 的下載）在 VM 連不到，uBO 只有真機上裝得到。
- **`ANDROID_HOME`**：給 session 裡的 Gradle 用。setup script 執行時環境變數還沒設好，所以 script 裡自己寫死了 `/opt/android-sdk`，兩邊要一致。
- **Setup script**：**Choose file** 是把檔案內容複製進環境設定，之後改了 repo 裡的 `scripts/cloud-setup.sh`，要重新上傳一次才會生效。
  - script 只在第一次開 session 時執行（約幾分鐘），之後的 session 直接沿用快取。
  - 修改 script 或 Allowed domains，或快取大約過了 7 天，會再重跑一次。
  - script 失敗也不會讓 session 起不來，SDK 沒裝好時看 `/var/log/android-sdk-setup.log`。
- **資料來源**：Custom 網路只放行套件庫，連不到證交所、櫃買、集保、期交所與一般網站是正常的。
  測試一律靠 `FakeHttp`／MockWebServer 與錄好的 fixture；fixture 在自己電腦上錄（`docs/testing.md`）。

## 3. 開 session

選剛才建立的環境和這個 repo，然後寫 prompt，例如：

> 先跑 `./gradlew test assembleDebug assembleRelease` 確認環境沒問題，再開始做。

## 常見問題

- **第一次 build 出現 Maven Central `429 Too Many Requests`**：雲端 VM 共用的出口 IP 被限流，隔 20 秒左右重跑 `./gradlew` 就好。
  錯誤訊息通常不會直接寫 429，常見的是 `Plugin [id: '...'] was not found`、`Could not resolve ...`，加 `--info` 才看得到；先重跑，不要去改版本。
- **`gh pr` / `gh issue` 不能用**：雲端 session 的 GitHub proxy 擋掉了 GraphQL，一律改用 `gh api` 打 REST API。
