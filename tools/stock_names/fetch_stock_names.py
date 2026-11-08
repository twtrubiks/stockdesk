#!/usr/bin/env python3
"""
產生「代號 → 名稱」對照表 stock_names.json，給 core 的 StockNames 用（打包進 APK）。

為什麼需要這支：
    自選股只輸入代號，名稱要有一張表才查得到（離線也能查），所以把全市場的代號對名稱打包進 APK。

資料來源都是公開端點，不需要 token、不需要登入：
    上市公司   openapi.twse.com.tw/v1/opendata/t187ap03_L          （公司簡稱）
    上櫃公司   tpex.org.tw/openapi/v1/mopsfin_t187ap03_O           （公司簡稱）
    興櫃公司   tpex.org.tw/openapi/v1/mopsfin_t187ap03_R           （公司簡稱）
    上市行情   openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL （補 ETF / 特別股 / DR / ETN）
    上櫃行情   tpex.org.tw/openapi/v1/tpex_mainboard_daily_close_quotes（補 ETF；權證會濾掉）

指數與期貨不在上面任何一支裡，用底下寫死的 INDEX_NAMES（推導方式見該處註解）。

用法：
    python tools/stock_names/fetch_stock_names.py              # 覆寫 core/src/main/resources/stock_names.json
    python tools/stock_names/fetch_stock_names.py -o out.json  # 換輸出位置
    python tools/stock_names/fetch_stock_names.py --check 2542 00631L TWA00   # 只查幾個代號，不寫檔

多久跑一次：新股上市 / 新 ETF 掛牌才需要更新，一季跑一次就很夠。
"""
from __future__ import annotations

import argparse
import datetime
import json
import sys
from pathlib import Path

import requests

BASE_DIR = Path(__file__).resolve().parent
OUT_PATH = BASE_DIR.parents[1] / "core" / "src" / "main" / "resources" / "stock_names.json"
TIMEOUT = 30

# (說明, url, 代號欄位, 名稱欄位)。順序即優先序：先填的不會被後面蓋掉。
# 公司基本資料排前面，因為那是官方「公司簡稱」；行情表排後面純粹是補 ETF 這類非公司的標的。
SOURCES = [
    ("上市公司", "https://openapi.twse.com.tw/v1/opendata/t187ap03_L",
     "公司代號", "公司簡稱"),
    ("上櫃公司", "https://www.tpex.org.tw/openapi/v1/mopsfin_t187ap03_O",
     "SecuritiesCompanyCode", "CompanyAbbreviation"),
    ("興櫃公司", "https://www.tpex.org.tw/openapi/v1/mopsfin_t187ap03_R",
     "SecuritiesCompanyCode", "CompanyAbbreviation"),
    ("上市行情（補 ETF 等）", "https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL",
     "Code", "Name"),
    ("上櫃行情（補 ETF 等）", "https://www.tpex.org.tw/openapi/v1/tpex_mainboard_daily_close_quotes",
     "SecuritiesCompanyCode", "CompanyName"),
]

# 權證代號前綴。上櫃行情表約 1.05 萬筆裡有 9,199 筆是權證（例 700019 宏捷科統一5C購01），
# 這些不可能出現在個股討論的標籤上，濾掉可以讓表小一個數量級。
WARRANT_PREFIXES = ("70", "71", "72", "73")

# 指數 / 期貨：CMoney 自己的代號，不在證交所/櫃買的任何開放資料裡，所以寫死。
#
# 推導方式（2026-08-18 收盤資料）：拿行情 API 打 TWA00 / TWB01~TWB44 取當日收盤，
# 再跟證交所 openapi.twse.com.tw/v1/exchangeReport/MI_INDEX（各類指數收盤行情）逐一比對數值，
# 每個代號都只吻合一個指數名稱，無歧義。例：TWA00=45308.68、TWB33=1515.60 精確對上
# 「發行量加權股價指數」與「半導體類指數」。TWB01~TWB10、TWB24 查無資料（代號未使用）。
# 這個對應是代號編碼、不隨行情變動，所以直接固定下來，不必每次重新探測。
INDEX_NAMES = {
    "TWA00": "加權指數",
    "TWB11": "水泥類指數",       "TWB12": "食品類指數",
    "TWB13": "塑膠類指數",       "TWB14": "紡織纖維類指數",
    "TWB15": "電機機械類指數",   "TWB16": "電器電纜類指數",
    "TWB17": "化學生技醫療類指數", "TWB18": "玻璃陶瓷類指數",
    "TWB19": "造紙類指數",       "TWB20": "鋼鐵類指數",
    "TWB21": "橡膠類指數",       "TWB22": "汽車類指數",
    "TWB23": "電子工業類指數",   "TWB25": "建材營造類指數",
    "TWB26": "航運類指數",       "TWB27": "觀光餐旅類指數",
    "TWB28": "金融保險類指數",   "TWB29": "貿易百貨類指數",
    "TWB30": "化學類指數",       "TWB31": "生技醫療類指數",
    "TWB32": "油電燃氣類指數",   "TWB33": "半導體類指數",
    "TWB34": "電腦及週邊設備類指數", "TWB35": "光電類指數",
    "TWB36": "通信網路類指數",   "TWB37": "電子零組件類指數",
    "TWB38": "電子通路類指數",   "TWB39": "資訊服務類指數",
    "TWB40": "其他電子類指數",   "TWB41": "綠能環保類指數",
    "TWB42": "數位雲端類指數",   "TWB43": "運動休閒類指數",
    "TWB44": "居家生活類指數",
    # 期貨：TXF1 實測收盤 45085 對照同日加權 45308.68、成交量 54093（口數而非張數），
    # 與台指期近月一致。
    "TXF1": "台指期近月",
}


def fetch(url: str) -> list[dict]:
    r = requests.get(url, timeout=TIMEOUT,
                     headers={"User-Agent": "Mozilla/5.0", "Accept": "application/json"})
    r.raise_for_status()
    data = r.json()
    if not isinstance(data, list):
        raise ValueError(f"預期 JSON 陣列，實得 {type(data).__name__}")
    return data


def build() -> tuple[dict[str, str], list[dict]]:
    """回傳 (代號→名稱, 每個來源的統計)。任一來源掛掉不會整個中斷，只會少那批。"""
    names: dict[str, str] = {}
    stats: list[dict] = []

    for label, url, code_key, name_key in SOURCES:
        try:
            rows = fetch(url)
        except Exception as e:
            print(f"  ✘ {label}：{type(e).__name__}: {e}", file=sys.stderr)
            stats.append({"source": label, "url": url, "rows": 0, "added": 0,
                          "error": f"{type(e).__name__}: {e}"})
            continue

        added = 0
        for row in rows:
            code = str(row.get(code_key, "")).strip()
            name = str(row.get(name_key, "")).strip()
            if not code or not name or code in names:
                continue
            if len(code) == 6 and code.isdigit() and code.startswith(WARRANT_PREFIXES):
                continue                      # 權證，跳過
            names[code] = name
            added += 1

        print(f"  ✓ {label}：{len(rows)} 筆 → 新增 {added}")
        stats.append({"source": label, "url": url, "rows": len(rows), "added": added})

    before = len(names)
    names.update(INDEX_NAMES)                 # 指數 / 期貨最後蓋上去，優先於任何同名代號
    print(f"  ✓ 指數 / 期貨（內建）：新增 {len(names) - before}")
    stats.append({"source": "指數 / 期貨（內建 INDEX_NAMES）", "url": None,
                  "rows": len(INDEX_NAMES), "added": len(names) - before})
    return names, stats


def main() -> int:
    ap = argparse.ArgumentParser(description="產生代號→名稱對照表 stock_names.json")
    ap.add_argument("-o", "--out", default=str(OUT_PATH), help="輸出路徑")
    ap.add_argument("--check", nargs="*", metavar="代號",
                    help="只查這些代號並印出結果，不寫檔")
    a = ap.parse_args()

    print("抓取來源：")
    names, stats = build()
    if not names:
        print("✘ 一筆都沒抓到，不覆寫既有檔案", file=sys.stderr)
        return 1

    if a.check is not None:
        print()
        for code in a.check:
            print(f"  {code:12} {names.get(code) or '（查無，會退回只顯示代號）'}")
        return 0

    payload = {
        "updatedAt": datetime.datetime.now().astimezone().strftime("%Y-%m-%d %H:%M:%S%z"),
        "count": len(names),
        "note": "代號 → 名稱，給自選股查名稱用；查不到的代號會退回只顯示代號。重跑 fetch_stock_names.py 可更新。",
        "sources": stats,
        "names": dict(sorted(names.items())),
    }
    out = Path(a.out)
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\n→ {out}（{len(names)} 檔，{out.stat().st_size / 1024:.0f} KB）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
