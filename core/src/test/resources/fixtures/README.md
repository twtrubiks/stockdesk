# fixture：錄好的真實回應

雲端 VM 連不到證交所、櫃買、集保、期交所，所以解析與抓取流程的測試吃這裡錄好的真實回應。
**在自己的電腦上**跑 `tools/fixtures/fetch_fixtures.py` 一次就會把下面這些檔案抓下來放進這個資料夾（見該腳本開頭）。
檔案不在時對應的測試會 skip（`Fixtures.require`），不算失敗。

| 檔案 | 來源 | 用在 |
| --- | --- | --- |
| `twse-margin-day.json` | 證交所 `MI_MARGN`，某個交易日 | `MarginParseTest`、`MarginSource.parseTwse` 的欄位順序 |
| `tpex-margin-day.json` | 櫃買 `margin/balance`，同一天 | `MarginParseTest`、`MarginSource.parseTpex` |
| `tdcc-form.html` | 集保 `qryStock` 的查詢頁（GET） | `HoldersParseTest`：token 與可查的週 |
| `tdcc-week.html` | 集保 `qryStock` 查一檔一週的結果（POST） | `HoldersParseTest`：15 級表格 |
| `taifex-weights.html` | 期交所 `futuresQADetail` | `WeightsParseTest` |
| `yahoo-spark.json` | Yahoo `v7/finance/spark`（8 個指數、`range=1d`），2026-11-08 週日在 VM 錄的（VM 連得到 Yahoo），11/06 那天的盤 | `IndicesParseTest` |

錄好之後：

1. 打開檔案對一次欄位順序，把 `MarginParseTest`、`HoldersParseTest` 裡的 `TODO` 換成具體數字的斷言（對照官方網頁上同一天的值）
2. 檔案不要太大（每個 1 MB 以內），證交所那份可以只留 `tables` 裡個股那張的前幾十列，但欄位 `fields` 要完整
