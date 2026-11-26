#!/usr/bin/env bash
# Runs inside the CI emulator at the primary tester's phone size/density.
# 1) Instrumented test: zero-tolerance px bounds of Home vs Settings header, title, button, first content.
#    The app and test APKs are installed with Gradle and the test is run directly with
#    `am instrument`, so nothing is uninstalled afterwards and the app's external files survive.
# 2) Screenrecord of the real app doing Home -> Settings -> Home taps, for frame-by-frame checking.
# Every critical step fails closed.
set -euxo pipefail
OUT=header-parity
mkdir -p $OUT
adb shell settings put global hide_error_dialogs 1
trap 'adb logcat -d -s HeaderParity:V > $OUT/logcat-last.txt 2>&1 || true; adb pull /sdcard/Android/data/com.listenlater/files/header-parity/. $OUT/ >/dev/null 2>&1 || true; adb shell wm size reset; adb shell wm density reset; adb shell settings put system font_scale 1.0' EXIT

./gradlew --no-daemon installDebug installDebugAndroidTest 2>&1 | tee $OUT/gradle.log
adb shell pm list instrumentation | tee $OUT/instrumentation-list.txt
adb shell pm grant com.listenlater android.permission.POST_NOTIFICATIONS

# Lanes: size/density (dp width) x font scale. 1440x2807 @649 dpi is 355 dp wide (the narrow, <400 dp
# branch), 1080x2340 @440 dpi is 392 dp (the producer narrow lane); 1080x2400 @420 dpi is 411 dp (>= 400 dp). Each lane runs the same zero-tolerance test.
run_lane() { # name size density fontscale
  adb shell wm size "$2"; adb shell wm density "$3"; adb shell settings put system font_scale "$4"
  sleep 2
  adb logcat -c
  adb shell am instrument -w -e class com.listenlater.HeaderParityDeviceTest -e lane "$1" com.listenlater.test/androidx.test.runner.AndroidJUnitRunner | tee "$OUT/instrument-$1.txt"
  grep -q '^OK (1 test)' "$OUT/instrument-$1.txt" || { echo "lane $1: HeaderParityDeviceTest did not pass"; exit 1; }
  adb logcat -d -s HeaderParity:I > "$OUT/logcat-$1.txt"
  grep -q "lane=$1" "$OUT/logcat-$1.txt" || { echo "lane $1: no HEADER_PARITY numbers logged"; exit 1; }
  adb pull /sdcard/Android/data/com.listenlater/files/header-parity/. $OUT/
  for n in home settings feedback; do test -s "$OUT/$1-$n.png" || { echo "lane $1: $n screenshot missing"; exit 1; }; done
}
run_lane narrow-355dp-font1.0 1440x2807 649 1.0
run_lane narrow-355dp-font1.3 1440x2807 649 1.3
run_lane narrow-392dp-font1.3 1080x2340 440 1.3
run_lane wide-411dp-font1.0 1080x2400 420 1.0
run_lane wide-411dp-font1.3 1080x2400 420 1.3

# Recording uses the primary tester's phone geometry at default font.
adb shell wm size 1440x2807; adb shell wm density 649; adb shell settings put system font_scale 1.0
adb shell pm grant com.listenlater android.permission.POST_NOTIFICATIONS
adb shell am force-stop com.listenlater
adb shell am start -n com.listenlater/.MainActivity --ez demo true
sleep 12
adb shell dumpsys window | grep -E 'mCurrentFocus' | tee $OUT/focus-before-record.txt | grep -q com.listenlater || { echo "the app is not in the foreground"; exit 1; }
adb shell screenrecord --bit-rate 8000000 --size 720x1404 --time-limit 45 /sdcard/switch.mp4 &
REC=$!
sleep 2
tap() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb shell cat /sdcard/ui.xml | tr '>' '>\n' | grep "content-desc=\"$1\"" | head -1 | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1 | grep -o '[0-9]*' | tr '\n' ' ' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}' > /tmp/xy
  if ! test -s /tmp/xy || ! grep -Eq '^[0-9]+ [0-9]+$' /tmp/xy; then echo "button $1 not found or bad coordinates: $(cat /tmp/xy 2>/dev/null)"; kill $REC 2>/dev/null || true; exit 1; fi
  read -r X Y < /tmp/xy
  if [ "$Y" -gt 2807 ] || [ "$X" -gt 1440 ]; then echo "tap $X $Y outside the 1440x2807 screen"; kill $REC 2>/dev/null || true; exit 1; fi
  adb shell input tap $X $Y
}
tap Settings; sleep 3
tap Back; sleep 3
tap Settings; sleep 3
tap Back; sleep 2
wait $REC
adb pull /sdcard/switch.mp4 $OUT/switch.mp4
test -s $OUT/switch.mp4 || { echo "switch.mp4 missing"; exit 1; }
