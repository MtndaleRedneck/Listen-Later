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

# Assert with dialog dismissal + retry: the emulator can throw SYSTEM windows
# over the app (a launcher ANR "isn't responding" dialog covered it on a real
# run while the app logcat stayed clean). Those are environment, not queue
# state, so dismiss them, re-focus the app, and re-read. An external ANR is
# answered with its "Wait" button (parsed from the dump) so nothing is killed.
# The app's OWN failure is a defect and must never be dismissed into a pass,
# so all app-failure detection runs BEFORE the waiting-text check: an app ANR
# or crash dialog, a process restart (PID change - an unnoticed kill/restart
# would fake a later pass), and app ANR/native-crash evidence in logcat since
# relaunch each break straight into the failure diagnostics.
PASS=0
UI=
for i in $(seq 1 15); do
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  UI=$(adb shell cat /sdcard/ui.xml 2>/dev/null)
  if echo "$UI" | grep -q "Listen Later isn't responding\|Listen Later keeps stopping"; then
    echo "upgrade-smoke: the APP itself ANR'd/crashed during the assert - not dismissible"
    break
  fi
  CUR=$(adb shell pidof com.listenlater | tr -d '\r\n')
  if [ "$CUR" != "$PID" ]; then
    echo "upgrade-smoke: app process changed ($PID -> ${CUR:-none}) during the assert - restart"
    break
  fi
  LC=$(adb logcat -d)
  if echo "$LC" | grep -q "ANR in com.listenlater" \
     || echo "$LC" | grep -A8 "FATAL EXCEPTION" | grep -q "Process: com.listenlater" \
     || echo "$LC" | grep "Fatal signal" | grep -q "com.listenlater"; then
    echo "upgrade-smoke: app ANR/crash evidence in logcat during the assert"
    break
  fi
  if echo "$UI" | grep -q "1 video is waiting"; then PASS=1; break; fi
  if echo "$UI" | grep -q "isn't responding"; then
    B=$(echo "$UI" | grep -o 'aerr_wait[^/]*' | grep -o 'bounds="[^"]*"' | head -1)
    if [ -n "$B" ]; then
      N=$(echo "$B" | sed 's/[^0-9]/ /g')
      set -- $N
      [ -n "$4" ] && adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
    fi
  else
    adb shell input keyevent 4
  fi
  adb shell am start -n com.listenlater/.MainActivity >/dev/null 2>&1
  sleep 8
done
if [ "$PASS" != 1 ]; then
  # Decisive diagnostics: show what IS on screen and what the app logged, so a
  # failure separates real queue loss from a covered/obscured assertion.
  echo "UPGRADE SMOKE FAIL: queued share lost across vc13 -> vc14 upgrade"
  echo "--- ui.xml (on-screen hierarchy) ---"
  echo "$UI" | head -c 4000
  echo "--- app logcat since relaunch ---"
  # Unfiltered on purpose: after a restart the new process is not $PID, and a
  # filtered dump would hide exactly the crash evidence this failure needs.
  adb logcat -d | tail -80
  exit 1
fi

adb logcat -d --pid="$PID" > /tmp/usmoke-logcat.txt
if grep -E "FATAL EXCEPTION|NoClassDefFoundError|ClassNotFoundException|NoSuchMethodError|NoSuchFieldError|VerifyError|ExceptionInInitializerError|GeneralSecurityException|KeyStoreException" /tmp/usmoke-logcat.txt; then
  echo "UPGRADE SMOKE FAIL: crash/class-loading/security failure after upgrade"; exit 1
fi
echo "UPGRADE SMOKE TEST PASS"
