#!/usr/bin/env bash
# Upgrade acceptance (publish runs only, where the new APK carries the REAL
# release signature): install the published 1.6.4 (vc13), queue a share with no
# session, force-stop, then upgrade in place to the new minified build (vc14)
# and prove the queue and the encrypted credential store survive.
# Proof points: `adb install -r` succeeds only when signatures match; the
# sign-in screen's waiting copy ("1 video is waiting") renders only when the
# queue row survived; a Tink/Keystore break on the upgraded install would
# crash or drop the app before that screen.
set -x
NEW_APK=$(ls ListenLater-v*.apk | grep -v debug | head -1)
[ -n "$NEW_APK" ] || { echo "UPGRADE SMOKE FAIL: no new release APK artifact"; exit 1; }
curl -sL -o /tmp/old-1.6.4.apk \
  https://github.com/MtndaleRedneck/Listen-Later/releases/download/v1.6.4/ListenLater-v1.6.4.apk
[ -s /tmp/old-1.6.4.apk ] || { echo "UPGRADE SMOKE FAIL: could not fetch published 1.6.4"; exit 1; }

adb install /tmp/old-1.6.4.apk || { echo "UPGRADE SMOKE FAIL: 1.6.4 install failed"; exit 1; }

# Grant up front: MainActivity asks for POST_NOTIFICATIONS on its first-ever launch,
# and in this flow MainActivity first runs AFTER the upgrade (the share went to the
# NoDisplay ShareActivity). Without the grant the system permission dialog covers the
# sign-in screen and the waiting-copy assert fails on the dialog, not on queue loss.
# A real upgrading user granted it long ago, and grants survive install -r.
adb shell pm grant com.listenlater android.permission.POST_NOTIFICATIONS

# Queue a share on 1.6.4 with no session (unique valid-shaped id).
SMOKE_ID="upg$(date +%s%N | tail -c 9)"
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "https://www.youtube.com/watch?v=$SMOKE_ID" \
  --es android.intent.extra.SUBJECT "upgrade" -n com.listenlater/.ShareActivity 2>&1 | tee /tmp/usmoke-share.log
if grep -qi "error" /tmp/usmoke-share.log; then echo "UPGRADE SMOKE FAIL: share launch error on 1.6.4"; exit 1; fi
sleep 12
adb shell am force-stop com.listenlater

# In-place upgrade to the new minified build: signature continuity proven here.
adb install -r "$NEW_APK" || { echo "UPGRADE SMOKE FAIL: in-place upgrade refused (signature or version problem)"; exit 1; }

adb logcat -c
adb shell am start -n com.listenlater/.MainActivity 2>&1 | tee /tmp/usmoke-relaunch.log
if grep -qi "error" /tmp/usmoke-relaunch.log; then echo "UPGRADE SMOKE FAIL: relaunch error after upgrade"; exit 1; fi
# Give the worker its pass: it marks the session missing, which is what shows
# the waiting copy on the sign-in screen.
sleep 18
PID=$(adb shell pidof com.listenlater | tr -d '\r\n')
[ -n "$PID" ] || { echo "UPGRADE SMOKE FAIL: app died after upgrade"; exit 1; }

adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
if ! adb shell cat /sdcard/ui.xml 2>/dev/null | grep -q "1 video is waiting"; then
  # Decisive diagnostics: show what IS on screen and what the app logged, so a
  # failure separates real queue loss from a covered/obscured assertion.
  echo "UPGRADE SMOKE FAIL: queued share lost across vc13 -> vc14 upgrade"
  echo "--- ui.xml (on-screen hierarchy) ---"
  adb shell cat /sdcard/ui.xml 2>/dev/null | head -c 4000
  echo "--- app logcat since relaunch ---"
  adb logcat -d ${PID:+--pid=$PID} | tail -60
  exit 1
fi

adb logcat -d --pid="$PID" > /tmp/usmoke-logcat.txt
if grep -E "FATAL EXCEPTION|NoClassDefFoundError|ClassNotFoundException|NoSuchMethodError|NoSuchFieldError|VerifyError|ExceptionInInitializerError|GeneralSecurityException|KeyStoreException" /tmp/usmoke-logcat.txt; then
  echo "UPGRADE SMOKE FAIL: crash/class-loading/security failure after upgrade"; exit 1
fi
echo "UPGRADE SMOKE TEST PASS"
