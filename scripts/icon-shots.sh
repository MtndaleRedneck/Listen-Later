#!/usr/bin/env bash
# Capture the launcher home screen and app drawer with the installed debug APK's icon.
set -x
APK=$(ls ListenLater-v*.apk | head -1)
OUT=icon-shots
mkdir -p $OUT
adb shell wm size 1080x2400
adb shell wm density 480
adb install -r $APK
sleep 2
adb shell input keyevent KEYCODE_HOME
sleep 3
# App drawer: swipe up from home.
adb shell input swipe 540 2200 540 700 500
sleep 3
find_icon() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  B=$(adb shell cat /sdcard/ui.xml 2>/dev/null | grep -o "text=\"Listen Later\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" | head -1 | grep -o "\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]")
  if [ -z "$B" ]; then return 1; fi
  X1=$(echo $B | cut -d'[' -f2 | cut -d',' -f1); Y1=$(echo $B | cut -d'[' -f2 | cut -d',' -f2 | cut -d']' -f1)
  X2=$(echo $B | cut -d'[' -f3 | cut -d',' -f1); Y2=$(echo $B | cut -d'[' -f3 | cut -d',' -f2 | cut -d']' -f1)
  CX=$(( (X1+X2)/2 )); CY=$(( (Y1+Y2)/2 ))
  return 0
}
# drawer may need a second swipe to reach all apps / L row
for i in 1 2 3; do
  if find_icon; then break; fi
  adb shell input swipe 540 2000 540 900 400
  sleep 2
done
adb exec-out screencap -p > $OUT/app-drawer.png
# Long-press the icon and drag it onto the home screen.
if [ -n "$CX" ]; then
  adb shell input swipe $CX $CY $CX $CY 1200
  sleep 2
  adb shell input swipe $CX $CY 300 800 1000
  sleep 2
fi
adb shell input keyevent KEYCODE_HOME
sleep 3
adb exec-out screencap -p > $OUT/home-screen.png
adb shell wm size reset
adb shell wm density reset
ls -la $OUT
