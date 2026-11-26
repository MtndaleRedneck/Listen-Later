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
adb shell wm size 1440x2807
adb shell wm density 649
trap 'adb shell wm size reset; adb shell wm density reset' EXIT

./gradlew --no-daemon installDebug installDebugAndroidTest 2>&1 | tee $OUT/gradle.log
adb shell pm list instrumentation | tee $OUT/instrumentation-list.txt
adb logcat -c
adb shell am instrument -w -e class com.listenlater.HeaderParityDeviceTest com.listenlater.test/androidx.test.runner.AndroidJUnitRunner | tee $OUT/instrument.txt
adb logcat -d -s HeaderParity:I > $OUT/logcat-parity.txt
grep -q '^OK (1 test)' $OUT/instrument.txt || { echo "HeaderParityDeviceTest did not pass"; exit 1; }
grep -q 'HeaderParity' $OUT/logcat-parity.txt || { echo "no HEADER_PARITY numbers logged"; exit 1; }
adb pull /sdcard/Android/data/com.listenlater/files/header-parity/. $OUT/
test -s $OUT/home.png && test -s $OUT/settings.png || { echo "home/settings screenshots missing"; exit 1; }

adb shell pm grant com.listenlater android.permission.POST_NOTIFICATIONS
adb shell am force-stop com.listenlater
adb shell am start -n com.listenlater/.MainActivity --ez demo true
sleep 12
adb shell dumpsys window | grep -E 'mCurrentFocus' | tee $OUT/focus-before-record.txt | grep -q com.listenlater || { echo "the app is not in the foreground"; exit 1; }
adb shell screenrecord --bit-rate 8000000 --time-limit 20 /sdcard/switch.mp4 &
REC=$!
sleep 2
tap() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb shell cat /sdcard/ui.xml | grep -o "content-desc=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | tr -d '[]' | tr ',' ' ' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}' > /tmp/xy
  test -s /tmp/xy || { echo "button $1 not found"; kill $REC 2>/dev/null || true; exit 1; }
  adb shell input tap $(cat /tmp/xy)
}
tap Settings; sleep 3
tap Back; sleep 3
tap Settings; sleep 3
tap Back; sleep 2
wait $REC
adb pull /sdcard/switch.mp4 $OUT/switch.mp4
test -s $OUT/switch.mp4 || { echo "switch.mp4 missing"; exit 1; }
