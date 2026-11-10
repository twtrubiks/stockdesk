#!/usr/bin/env bash
# 把 CI 模擬器的截圖（media branch 的 screenshots/，見 docs/testing.md）縮成 400 寬，放到 docs/screenshots/ 給 README 用。
# 畫面改了、CI 跑完之後在 repo 根目錄跑：scripts/readme-screenshots.sh；先看 media 的 info.txt 確認是哪個 commit 的截圖。
# 截圖裡的數字是 FakeSources 的假資料（資料來源換成假的）。
set -euo pipefail

OUT=docs/screenshots
WIDTH=400
# media 上的檔名 → README 用的檔名
SHOTS=(
  "05-holders:goodinfo"
  "06-goodinfo-collapsed:goodinfo-collapsed"
  "07-forum:forum"
  "12-watchlist-gov-bank:watchlist"
  "09-indices:indices"
  "10-weights:weights"
  "11-public-bank:public-bank"
  "13-settings:settings"
)

git fetch -q origin media
git show origin/media:info.txt | head -2
mkdir -p "$OUT"
for pair in "${SHOTS[@]}"; do
  src=${pair%%:*}
  dst=${pair##*:}
  git show "origin/media:screenshots/$src.png" | convert png:- -resize "${WIDTH}x" -strip "$OUT/$dst.png"
  echo "$OUT/$dst.png"
done
