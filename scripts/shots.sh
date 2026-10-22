#!/usr/bin/env bash
# Runs inside the CI emulator: installs the debug APK and captures real screens with demo data.
set -x
APK=app/build/outputs/apk/debug/app-debug.apk
OUT=docs/screenshots
mkdir -p $OUT
adb shell settings put global hide_error_dialogs 1
adb install -r $APK
adb shell pm grant com.listenlater android.permission.POST_NOTIFICATIONS
shot() { adb shell am force-stop com.listenlater; sleep 1; adb shell am start -n com.listenlater/.MainActivity "${@:2}"; sleep 5; adb exec-out screencap -p > $OUT/$1.png; }
# tap_text <visible text or content-desc>: find its bounds in the UI dump and tap its center.
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml | grep -o "text=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" | head -1 > /tmp/hit.txt
  if [ ! -s /tmp/hit.txt ]; then
    adb shell cat /sdcard/ui.xml | grep -o "content-desc=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" | head -1 > /tmp/hit.txt
  fi
  B=$(grep -o "\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]" /tmp/hit.txt | head -1)
  X1=$(echo $B | cut -d'[' -f2 | cut -d',' -f1); Y1=$(echo $B | cut -d'[' -f2 | cut -d',' -f2 | cut -d']' -f1)
  X2=$(echo $B | cut -d'[' -f3 | cut -d',' -f1); Y2=$(echo $B | cut -d'[' -f3 | cut -d',' -f2 | cut -d']' -f1)
  adb shell input tap $(( (X1+X2)/2 )) $(( (Y1+Y2)/2 ))
}
# First launch warms up and seeds; captured, then discarded.
shot warmup --ez demo true
sleep 5
rm -f $OUT/warmup.png
shot v15-status --ez demo true
cp $OUT/v15-status.png docs/main.png
shot v15-loadfailed --ez demo true --ez loadfail true --ez empty true
shot v15-signin --ez demo true --ez signedout true
# Sign-out confirm dialog.
adb shell am force-stop com.listenlater; sleep 1
adb shell am start -n com.listenlater/.MainActivity --ez demo true; sleep 5
tap_text "Sign out"; sleep 1
adb exec-out screencap -p > $OUT/v15-signout-confirm.png
adb shell input keyevent KEYCODE_BACK; sleep 1
# Settings (Check for updates button) and Feedback buttons.
adb shell am force-stop com.listenlater; sleep 1
adb shell am start -n com.listenlater/.MainActivity --ez demo true; sleep 5
tap_text "Settings"; sleep 2
adb exec-out screencap -p > $OUT/v15-settings.png
tap_text "General feedback, bugs, and feature suggestions"; sleep 2
adb exec-out screencap -p > $OUT/v15-feedback.png
ls -la $OUT
