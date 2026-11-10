#!/usr/bin/env python3
"""
在自己的電腦上抓測試用的真實回應，放進 core/src/test/resources/fixtures/（雲端 VM 連不到這些站）。

    python tools/fixtures/fetch_fixtures.py                 # 證交所、櫃買、集保、期交所，都是公開端點、不用登入
    python tools/fixtures/fetch_fixtures.py --gov-bank      # 另外重錄八大官股（blave、HiStock 全市場與上櫃 3131 的個股頁，非官方；已經錄過一份）

只依賴 requests。請求之間有停頓（證交所 2 秒、櫃買 1 秒、集保 1 秒、blave 與 HiStock 2 秒），不要改小。
重錄八大官股的 fixture 之後，GovBankParseTest 裡的具體數字要跟著換（對照 blave、HiStock 網頁上同一天的值）。
"""
from __future__ import annotations

import argparse
import datetime
import json
import os
import re
import sys
import time
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "core" / "src" / "test" / "resources" / "fixtures"
UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"}
TPE = datetime.timezone(datetime.timedelta(hours=8))
OTC_CODE = "3131"  # 八大官股個股頁的 fixture 錄這一檔上櫃股（弘塑）


def save(name: str, text: str) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / name).write_text(text, encoding="utf-8")
    print(f"  → {name}（{len(text) / 1024:.0f} KB）")


def last_weekday() -> datetime.date:
    d = datetime.datetime.now(TPE).date() - datetime.timedelta(days=1)
    while d.weekday() >= 5:
        d -= datetime.timedelta(days=1)
    return d


def margin(day: datetime.date) -> None:
    print("證交所 MI_MARGN")
    r = requests.get("https://www.twse.com.tw/rwd/zh/marginTrading/MI_MARGN",
                     params={"date": day.strftime("%Y%m%d"), "selectType": "ALL", "response": "json"},
                     headers={**UA, "Accept": "application/json"}, timeout=15)
    r.raise_for_status()
    save("twse-margin-day.json", r.text)
    time.sleep(2)
    print("櫃買 margin/balance")
    r = requests.get("https://www.tpex.org.tw/www/zh-tw/margin/balance",
                     params={"date": day.strftime("%Y/%m/%d"), "response": "json"},
                     headers={**UA, "Accept": "application/json"}, timeout=15)
    r.raise_for_status()
    save("tpex-margin-day.json", r.text)


def holders(code: str) -> None:
    url = "https://www.tdcc.com.tw/portal/zh/smWeb/qryStock"
    print("集保 qryStock 查詢頁")
    s = requests.Session()
    r = s.get(url, headers=UA, timeout=15)
    r.raise_for_status()
    save("tdcc-form.html", r.text)
    token = re.search(r'name="SYNCHRONIZER_TOKEN"\s+value="([^"]+)"', r.text).group(1)
    dates = re.findall(r'<option value="(\d{8})"', r.text)
    time.sleep(1)
    print(f"集保 {code} 第 {dates[0]} 週")
    r = s.post(url, headers=UA, timeout=15, data={
        "SYNCHRONIZER_TOKEN": token, "SYNCHRONIZER_URI": "/portal/zh/smWeb/qryStock",
        "method": "submit", "firDate": dates[-1], "scaDate": dates[0],
        "sqlMethod": "StockNo", "stockNo": code, "stockName": "",
    })
    r.raise_for_status()
    save("tdcc-week.html", r.text)


def weights() -> None:
    print("期交所 futuresQADetail")
    r = requests.get("https://www.taifex.com.tw/cht/9/futuresQADetail", headers=UA, timeout=30)
    r.raise_for_status()
    save("taifex-weights.html", r.text)


def gov_bank(code: str) -> None:
    today = datetime.datetime.now(TPE).date()
    print(f"blave 八大官股 {code}")
    r = requests.get(f"https://api.blave.org/studio/charts/twstock/gov_bank/{code}",
                     params={"start": (today - datetime.timedelta(days=8)).isoformat(), "end": today.isoformat()},
                     headers={**UA, "Accept": "application/json"}, timeout=15)
    r.raise_for_status()
    save(f"blave-gov-bank-{code}.json", r.text)
    time.sleep(2)
    print("HiStock 八大官股 broker8")
    r = requests.get("https://histock.tw/stock/broker8.aspx", headers=UA, timeout=15)
    r.raise_for_status()
    r.encoding = "utf-8"
    save("histock-broker8.html", r.text)
    time.sleep(2)
    # blave 沒有上櫃股，App 改抓 HiStock 的個股頁；錄一檔上櫃的
    print(f"HiStock 八大官股個股頁 {OTC_CODE}")
    r = requests.get("https://histock.tw/stock/broker.aspx", params={"no": OTC_CODE}, headers=UA, timeout=15)
    r.raise_for_status()
    r.encoding = "utf-8"
    save(f"histock-broker-{OTC_CODE}.html", r.text.replace("\r\n", "\n"))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--code", default="2330", help="集保要查的代號（預設 2330）")
    ap.add_argument("--date", help="融資融券的日期 YYYYMMDD（預設最近一個平日）")
    ap.add_argument("--gov-bank", action="store_true", help="只重錄八大官股（blave 的 --code、HiStock）")
    a = ap.parse_args()
    if a.gov_bank:
        gov_bank(a.code)
        print(f"\n放在 {OUT}。接著把 GovBankParseTest 的數字換成這一份的。")
        return
    day = datetime.datetime.strptime(a.date, "%Y%m%d").date() if a.date else last_weekday()
    margin(day)
    time.sleep(1)
    holders(a.code)
    time.sleep(1)
    weights()
    print(f"\n放在 {OUT}。接著照 fixtures/README.md 對欄位。")


if __name__ == "__main__":
    main()
