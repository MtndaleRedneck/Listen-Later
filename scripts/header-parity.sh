#!/usr/bin/env bash
# Runs inside the CI emulator at the primary tester's phone size/density.
# 1) Instrumented test: zero-tolerance px bounds of Home vs Settings header, title, button, first content.
# 2) Screenrecord of the real Home -> Settings -> Home taps, for frame-by-frame checking.
set -x
OUT=header-parity
mkdir -p $OUT
adb shell settings put global hide_error_dialogs 1
adb shell wm size 1440x2807
adb shell wm density 649
./gradlew --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.listenlater.HeaderParityDeviceTest 2>&1 | tee $OUT/gradle.log
STATUS=${PIPESTATUS[0]}
adb logcat -d -s HeaderParity:I > $OUT/logcat-parity.txt
adb pull /sdcard/Android/data/com.listenlater/files/header-parity/. $OUT/ || true
adb shell pm grant com.listenlater android.permission.POST_NOTIFICATIONS
adb shell am force-stop com.listenlater
adb shell am start -n com.listenlater/.MainActivity --ez demo true
sleep 12
adb shell screenrecord --bit-rate 8000000 --time-limit 20 /sdcard/switch.mp4 &
REC=$!
sleep 2
tap() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml | grep -o "content-desc=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | tr -d '[]' | tr ',' ' ' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}' > /tmp/xy
  adb shell input tap $(cat /tmp/xy)
}
tap Settings; sleep 3
tap Back; sleep 3
tap Settings; sleep 3
tap Back; sleep 2
wait $REC
adb pull /sdcard/switch.mp4 $OUT/switch.mp4 || true
exit $STATUS
