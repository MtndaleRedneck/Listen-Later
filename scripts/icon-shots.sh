#!/usr/bin/env bash
# Capture the launcher home screen and app drawer with the installed debug APK's icon.
set -x
# Prefer the debug APK so run-as works for the share smoke test.
APK=$(ls ListenLater-debug-v*.apk 2>/dev/null | head -1)
[ -z "$APK" ] && APK=$(ls ListenLater-v*.apk 2>/dev/null | head -1)
if [ -n "$PROOF_APK_URL" ]; then
  # Proof mode: shoot a different (e.g. released) APK under the same emulator
  # to document launcher-side icon treatment independent of our assets.
  curl -sSL -o proof.apk "$PROOF_APK_URL" && APK=proof.apk
fi
OUT=icon-shots
mkdir -p $OUT
adb shell wm size 1080x2400
adb shell wm density 480
adb install -r $APK
sleep 2
# Launch once so the launcher clears the not-yet-launched pending-icon treatment
# (shrunken icon + pale ring) before the shots.
adb shell am start -n com.listenlater/.MainActivity
sleep 6
adb shell am force-stop com.listenlater
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

# Share smoke test: fire a real ACTION_SEND at ShareActivity and confirm the
# video URL actually landed in the queue store. Requires the debug APK (run-as).
case "$APK" in
  ListenLater-debug-*)
    SMOKE_ID="dQw4w9WgXcQ"
    adb shell am start -a android.intent.action.SEND -t text/plain \
      --es android.intent.extra.TEXT "https://www.youtube.com/watch?v=$SMOKE_ID" \
      --es android.intent.extra.SUBJECT "smoke" -n com.listenlater/.ShareActivity
    FOUND=""
    for i in $(seq 1 15); do
      sleep 2
      if adb shell run-as com.listenlater cat shared_prefs/queue_store.xml 2>/dev/null | grep -q "$SMOKE_ID"; then
        FOUND=1; break
      fi
    done
    if [ -n "$FOUND" ]; then echo "SHARE SMOKE TEST PASS"; else echo "SHARE SMOKE TEST FAIL: shared URL never reached the queue"; exit 1; fi
    ;;
  *)
    echo "SHARE SMOKE TEST SKIP: no debug APK (run-as needs a debuggable build)"
    ;;
esac
