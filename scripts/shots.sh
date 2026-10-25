#!/usr/bin/env bash
# Runs inside the CI emulator: installs the debug APK and captures real screens with demo data.
set -x
APK=app/build/outputs/apk/debug/app-debug.apk
OUT=docs/screenshots
mkdir -p $OUT
adb shell settings put global hide_error_dialogs 1
# Match the primary tester's phone (1440x3088 @ ~649dpi, Galaxy S22 Ultra class)
adb shell wm size 1440x2807
adb shell wm density 649
adb install -r $APK
adb shell pm grant com.listenlater android.permission.POST_NOTIFICATIONS
shot() {
  adb shell am force-stop com.listenlater; sleep 1
  adb shell am start -n com.listenlater/.MainActivity "${@:2}"
  for i in $(seq 1 15); do
    sleep 2
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    if adb shell cat /sdcard/ui.xml 2>/dev/null | grep -q "Listen Later"; then break; fi
  done
  sleep 2
  adb exec-out screencap -p > $OUT/$1.png
}
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
# The new queue states, with real shared titles: sending, retrying at a time,
# unexpected server replies past an hour, one very long title for the wrap check.
shot v16-queued-states --ez demo true --ez queuestates true
# Same rows while a sign-in is outstanding.
shot v16-waiting-signin --ez demo true --ez queuestates true --ez needslogin true --ez staystatus true
# Startup background: the app's plain color while storage readies - no logo, no spinner.
adb shell am force-stop com.listenlater; sleep 1
adb shell am start -n com.listenlater/.MainActivity --ez demo true --ez holdgate true
sleep 2
adb exec-out screencap -p > $OUT/v16-startup.png
sleep 4
# A real send toast with the worker's own wording.
adb shell am force-stop com.listenlater; sleep 1
adb shell am start -n com.listenlater/.MainActivity --ez demo true --ez queuestates true --ez toast true
for i in $(seq 1 10); do
  sleep 1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  if adb shell cat /sdcard/ui.xml 2>/dev/null | grep -q "Listen Later"; then break; fi
done
adb exec-out screencap -p > $OUT/v16-toast.png
sleep 4
# Scrolled down: the long bottom title must be reachable by scrolling, not cut off.
adb shell am force-stop com.listenlater; sleep 1
adb shell am start -n com.listenlater/.MainActivity --ez demo true --ez queuestates true
sleep 6
adb shell input swipe 720 2000 720 900; sleep 1
adb exec-out screencap -p > $OUT/v16-scrolled.png
ls -la $OUT
adb shell wm size reset
adb shell wm density reset
