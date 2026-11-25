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

# Share smoke test (shared with migration-test): needs the debug APK (run-as). It runs BEFORE the notification
# proof below, because the proof launches the demo seed, which signs the demo account in and would break the
# smoke test's signed-out starting state.
SMOKE_RC=0
case "$APK" in
  ListenLater-debug-*) ./scripts/share-smoke.sh || SMOKE_RC=$? ;;
  *) echo "SHARE SMOKE TEST SKIP: no debug APK (run-as needs a debuggable build)" ;;
esac

# Notification proof: the debug build posts its real notifications (sign-in ask and a skipped video), then the
# status bar icon and the expanded shade are captured at normal and large font. Font scale is restored on every
# exit path. The dumpsys check is only a sanity check that something from the app was posted; the screenshots
# are the proof.
trap 'adb shell settings put system font_scale 1.0' EXIT
adb shell pm grant com.listenlater android.permission.POST_NOTIFICATIONS
for scale in 1.0 1.3; do
  adb shell settings put system font_scale $scale
  adb shell am force-stop com.listenlater; sleep 1
  adb shell am start -n com.listenlater/.MainActivity --ez demo true --ez postnotice true
  # Wait for the real condition (the app's notification is posted), up to 40s, and record the observed delay.
  # The count keeps the proven --noredact form; only the number is kept, the dump itself is never printed.
  echo 0 > /tmp/ncount
  WAITED=0
  for _ in $(seq 1 20); do
    sleep 2; WAITED=$((WAITED+2))
    adb shell dumpsys notification --noredact | grep -c "pkg=com.listenlater" > /tmp/ncount
    [ "$(cat /tmp/ncount)" -ge 1 ] && break
  done
  echo "NOTIF-DELAY-OBSERVED: scale $scale count $(cat /tmp/ncount) after ~${WAITED}s (2s poll steps, includes app startup)"
  if [ "$(cat /tmp/ncount)" -lt 1 ]; then
    echo "NO LISTENLATER NOTIFICATION POSTED"
    # App-scoped forensics only (this package's permission, process and log), no device-wide dumps.
    adb shell dumpsys package com.listenlater | grep -i "POST_NOTIFICATIONS"
    APP_PID=$(adb shell pidof com.listenlater)
    echo "app pid: ${APP_PID:-none}"
    adb shell dumpsys activity activities | grep -iE "mResumedActivity|topResumed" | grep -i listenlater | head -3
    [ -n "$APP_PID" ] && adb logcat -d --pid="$APP_PID" -t 300 | tail -80
    exit 1
  fi
  adb shell input keyevent KEYCODE_HOME; sleep 2
  adb exec-out screencap -p > $OUT/notif-statusbar-$scale.png
  adb shell cmd statusbar expand-notifications; sleep 3
  adb exec-out screencap -p > $OUT/notif-shade-$scale.png
  adb shell cmd statusbar collapse; sleep 1
done
ls -la $OUT
exit $SMOKE_RC
