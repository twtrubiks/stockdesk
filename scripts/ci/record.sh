#!/usr/bin/env bash
# 在模擬器上先跑 DeviceTest（Android 的 SQLite 跑 store 的 SQL，不錄影），再一邊錄影一邊跑 DemoRecordingTest，
# 最後覆蓋安裝 release 版做冒煙測試（打開 App 沒有閃退、goodinfo 頁有出來）。
# 資料來源換成假的：DemoRecordingTest 用 MockWebServer 假裝證交所 / 櫃買 / 集保 / 期交所等（App 的 base URL 可以改）；內建瀏覽器頁只開一下，看得到網站的標題，內容通常還沒畫出來。
# APK 按 CPU 拆開（GeckoView），模擬器裝 x86_64 那一份。
# 輸出到 $1（預設 out/）：device-test.txt、demo.raw.mp4、instrument.txt、logcat.txt、screenshots/、release-smoke.txt、logcat-release.txt
# 由 .github/workflows/record.yml 在 android-emulator-runner 裡呼叫，工作目錄是 repo 根目錄。
set -uo pipefail

OUT=${1:-out}
APP_ID=com.twtrubiks.stockdesk
TEST_CLASS=$APP_ID.DemoRecordingTest
DEVICE_TEST_CLASS=$APP_ID.DeviceTest
# release 冒煙測試要看到的元件（底部分頁列 goodinfo 的 testTag）
SMOKE_TAG=tab_goodinfo
mkdir -p "$OUT"

# adb 指令都限時，卡住時早點停下來，後面的步驟還能把 log 與截圖收回來
ADB_BIN=$(command -v adb)
adb() { timeout "${ADB_TIMEOUT:-120}" "$ADB_BIN" "$@"; }

# 每一段開始時記一行（含時間）：卡住時看 progress.txt 就知道卡在哪
step() { echo "$(date +%T) $*" | tee -a "$OUT/progress.txt"; }

# GeckoView 的 APK 按 CPU 拆開，模擬器是 x86_64
step "安裝 debug 版與測試 APK"
ADB_TIMEOUT=300 adb install -r -t app/build/outputs/apk/debug/app-x86_64-debug.apk || exit 1
ADB_TIMEOUT=300 adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1

# 喚醒並解鎖螢幕、在影片裡顯示點擊位置
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb shell settings put system show_touches 1 || true

# 剛開機時系統服務還很忙：等 1 分鐘 load average 降到 2.5 以下再開始，最多等 3 分鐘
for _ in $(seq 1 36); do
  load=$(adb shell cat /proc/loadavg | cut -d' ' -f1)
  echo "loadavg: $load"
  awk -v l="$load" 'BEGIN { exit !(l < 2.5) }' && break
  sleep 5
done

# 開機時卡死的系統程式會一直跳 ANR 對話框，對話框在的時候 UiAutomator 也讀不到 App 畫面：直接重新啟動它們
anr_pkgs=$(adb shell dumpsys window windows | grep -o 'Application Not Responding: [A-Za-z0-9_.]*' | sed 's/.*: //' | sort -u)
for pkg in $anr_pkgs; do
  echo "重新啟動 ANR 的程式：$pkg"
  adb shell am force-stop "$pkg" || true
done
if [ -n "$anr_pkgs" ]; then
  sleep 5
fi

# 只有真機才測得到的事：Android 9 內建的 SQLite（3.22）跑得動 store 的 SQL。不錄影
step "DeviceTest"
timeout 300 adb shell am instrument -w -e class "$DEVICE_TEST_CLASS" \
  "$APP_ID.test/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/device-test.txt"

adb logcat -c || true

# pixel_7_pro 是 1440x3120，直接錄可能超過模擬器編碼器上限，所以錄一半尺寸；screenrecord 單次最長 180 秒
step "DemoRecordingTest（錄影）"
ADB_TIMEOUT=200 adb shell screenrecord --size 720x1560 --bit-rate 8000000 --time-limit 180 /sdcard/demo.mp4 &
REC_PID=$!
sleep 2

timeout 600 adb shell am instrument -w -e class "$TEST_CLASS" \
  "$APP_ID.test/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/instrument.txt"

sleep 1
adb shell am force-stop "$APP_ID" || true
adb shell 'pkill -INT screenrecord 2>/dev/null || pkill -l 2 screenrecord' || true
wait "$REC_PID" || true
sleep 2

step "收回錄影"
adb pull /sdcard/demo.mp4 "$OUT/demo.raw.mp4" || true
adb pull "/sdcard/Android/data/$APP_ID/files/screenshots" "$OUT/" || true
adb logcat -d > "$OUT/logcat.txt" || true

status=0
# 測試失敗時 am instrument 的 exit code 仍是 0，要看輸出判斷
if ! grep -q '^OK (' "$OUT/device-test.txt"; then
  echo "DeviceTest 沒有通過，詳見 $OUT/device-test.txt"
  status=1
fi
if ! grep -q '^OK (' "$OUT/instrument.txt"; then
  echo "DemoRecordingTest 沒有通過，詳見 $OUT/instrument.txt 與 $OUT/logcat.txt"
  status=1
fi

# release 版冒煙測試：R8 壓縮過的程式在 debug 版測不到。兩個版本都用 debug 金鑰簽，可以直接覆蓋。
# 先清掉 App 資料，從第一次打開的樣子開始；打開後要看到 goodinfo 頁（UiAutomator dump 找 resource-id）
RELEASE_APK=app/build/outputs/apk/release/app-x86_64-release.apk
if [ -f "$RELEASE_APK" ]; then
  mkdir -p "$OUT/screenshots"
  step "release 冒煙測試"
  adb shell pm clear "$APP_ID" || true
  ADB_TIMEOUT=300 adb install -r "$RELEASE_APK" || exit 1
  adb logcat -c || true
  adb shell am start -W -n "$APP_ID/.MainActivity" | tee "$OUT/release-smoke.txt"
  sleep 4
  adb exec-out screencap -p > "$OUT/screenshots/release-1-launch.png"
  seen=0
  for _ in 1 2 3 4 5; do
    if adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb shell cat /sdcard/ui.xml | grep -q "resource-id=\"$SMOKE_TAG\""; then
      seen=1
      break
    fi
    sleep 2
  done
  adb shell am force-stop "$APP_ID" || true
  adb logcat -d > "$OUT/logcat-release.txt" || true
  if grep -q 'FATAL EXCEPTION' "$OUT/logcat-release.txt"; then
    echo "release 版閃退，詳見 $OUT/logcat-release.txt"
    status=1
  fi
  if [ "$seen" -ne 1 ]; then
    echo "release 版打開後沒看到 goodinfo 頁，詳見 $OUT/screenshots/release-1-launch.png 與 $OUT/logcat-release.txt"
    status=1
  fi
fi

exit $status
