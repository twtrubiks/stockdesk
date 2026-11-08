# debug 版與 release 版

兩個版本的功能、程式邏輯完全一樣，差在**打包方式**、**速度**和**簽章**。
給人用的是 release 版（GitHub Release 發的就是它）；debug 版是開發時方便接除錯器用的。

## 對照

|  | debug | release |
|---|---|---|
| R8（刪掉沒用到的程式碼、改短名稱、最佳化） | 沒有：Compose、Material3、Kotlin、OkHttp、jsoup 全部原封不動打包 | 有，只留用得到的部分（GeckoView 的引擎是 native library，兩版一樣大） |
| 刪掉沒用到的資源 | 沒有 | 有 |
| Compose 預覽工具（`ui-tooling`） | 有 | 沒有 |
| 可以接除錯器（debuggable） | 可以 | 不行 |
| 速度 | Compose 明顯比較卡 | 順很多 |
| 簽章 | 每個 VM session 自己的 debug 金鑰 | GitHub 發版用發版金鑰（`docs/release-key.md`）；在 VM 沒金鑰時退回 debug 金鑰 |
| 連本機的明文 HTTP（`network_security_config`） | 只放行 localhost／127.0.0.1（模擬器上的假來源 `FakeSources` 用；GeckoView 自己的網路層不看這份設定） | 沒有，全部只能 HTTPS |
| APK 大小 | 兩版都按 CPU 拆開（`app-<abi>-<debug\|release>.apk`），大部分是 GeckoView 的 native library（壓縮打包） | arm64 約 95 MB |

## 設定在哪裡

都在 `app/build.gradle.kts`：`buildTypes.release` 的 `isMinifyEnabled`、`isShrinkResources`、`proguardFiles`；
`debugImplementation(libs.androidx.compose.ui.tooling)` 預覽工具只進 debug 版；簽章看環境變數 `STOCKDESK_KEYSTORE`、`STOCKDESK_KEYSTORE_PASSWORD`。
debug 版多一份 `app/src/debug/AndroidManifest.xml` ＋ `res/xml/network_security_config.xml`（只給模擬器測試連 MockWebServer）。

## 裝在手機上要注意的

- 兩個版本的 applicationId 一樣（`com.twtrubiks.stockdesk`），手機上**只能裝一個**，新的蓋舊的
- **簽章不同就不能直接蓋**，要先移除再裝。移除會清掉自選股與快取：**先在自選股分頁匯出**，裝好再匯入
  - GitHub Release 的正式版：發版金鑰，每一版都一樣，可以一直直接更新
  - 雲端 VM 給的測試 APK：VM 的 debug 金鑰，**每個 session 都不一樣**（同一個 session 給的可以互相蓋）
- 覺得滑動卡卡的，先確認裝的是不是 debug 版

## release 版才會出的問題

R8 會刪掉「看起來沒用到」的程式碼。kotlinx.serialization 的 `@Serializable` class 靠外掛產生的 serializer，自帶規則；
加新的依賴、特別是會用反射的，要跑一次 `assembleRelease` 並在 release 版上實際用過。CI 的模擬器有 release 版的冒煙測試（`docs/testing.md`）。
