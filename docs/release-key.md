# 發版金鑰

給別人裝的 APK 一律從 GitHub Release 來，每一版都要用**同一把**金鑰簽：
換了金鑰，手機上的舊版就不能直接更新，只能移除重裝（自選股會一起刪掉）。所以這把金鑰只產生一次，備份好，之後永遠用它。

**金鑰已經產生。** PKCS12 格式、alias `stockdesk`，檔案與密碼由使用者保管（密碼管理器、另一顆硬碟），不在 repo 裡；
`.gitignore` 擋掉 `*.p12`／`*.jks`／`*.keystore`。**不要再產生新的**：金鑰弄丟了，只能換一把新的、手機上的舊版移除重裝（自選股先匯出）。

repo 裡只有憑證的 SHA-256，填在 `.github/workflows/release.yml` 的 `EXPECTED_CERT`：

```
fb2cfef2e4a61a4c8beb1dd04967cb8139857cd66c57571b25f6e1459eb25a2e
```

workflow 簽完會核對這個值，Secret 被換成別把金鑰的話就不發版。

## GitHub Secrets

| Secret | 內容 |
| --- | --- |
| `ANDROID_SIGNING_KEYSTORE` | 金鑰檔的 base64（`base64 -w0 stockdesk-release.p12`） |
| `ANDROID_SIGNING_PASSWORD` | 金鑰檔與金鑰的密碼（PKCS12 兩者同一個） |

```bash
base64 -w0 stockdesk-release.p12 | gh secret set ANDROID_SIGNING_KEYSTORE --repo twtrubiks/stockdesk
gh secret set ANDROID_SIGNING_PASSWORD --repo twtrubiks/stockdesk   # 貼上密碼
```

憑證的 SHA-256 要重新算的話：

```bash
keytool -list -v -keystore stockdesk-release.p12 -alias stockdesk \
  | sed -n 's/.*SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f'
```

## 發版

版本號用語意化版本（`1.0.0`、`1.0.1`、`1.1.0`…）。兩種發法：

- 推一個 tag：`git tag v1.0.1 && git push origin v1.0.1`
- repo 根目錄的 `release-version` 內容寫版本（例如 `1.0.1`），改了推上去（推不了 tag 的環境用這個）

都會 build、簽名、發 Release（tag `v1.0.1`）。先確認 CI（`record.yml`）過了再發。
