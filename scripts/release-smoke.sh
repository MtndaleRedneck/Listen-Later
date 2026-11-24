#!/usr/bin/env bash
# Release-build R8 smoke: the minified, resource-shrunk, signed release APK must
# boot and take a share without any class-loading or crash failure. Exercises:
# app startup (Application + Compose first frame), EncryptedSharedPreferences/Tink
# credential-store init (MainActivity's session read), share intake (ShareActivity
# -> queue write), WorkManager scheduling (a queued share enqueues ShareWorker),
# notification path (sign-in nudge for a queued share with no session).
# A release build is not debuggable: proof is process liveness + a clean logcat,
# not file inspection (that is what scripts/share-smoke.sh does on debug).
set -x
APK=$(ls ListenLater-v*.apk | grep -v debug | head -1)
[ -n "$APK" ] || { echo "RELEASE SMOKE FAIL: no release APK artifact"; exit 1; }
adb install -r "$APK" || { echo "RELEASE SMOKE FAIL: install failed"; exit 1; }

adb logcat -c

# Boot: MainActivity must come up and STAY up.
adb shell am start -n com.listenlater/.MainActivity 2>&1 | tee /tmp/rsmoke-start.log
if grep -qi "error" /tmp/rsmoke-start.log; then echo "RELEASE SMOKE FAIL: launch error"; exit 1; fi
sleep 12
adb shell pidof com.listenlater >/dev/null 2>&1 || { echo "RELEASE SMOKE FAIL: app died after launch"; exit 1; }

# Share intake: a real ACTION_SEND with a valid-shaped YouTube URL.
SMOKE_ID="rsm$(date +%s%N | tail -c 9)"
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "https://www.youtube.com/watch?v=$SMOKE_ID" \
  --es android.intent.extra.SUBJECT "smoke" -n com.listenlater/.ShareActivity 2>&1 | tee /tmp/rsmoke-share.log
if grep -qi "error" /tmp/rsmoke-share.log; then echo "RELEASE SMOKE FAIL: share launch error"; exit 1; fi
sleep 12
adb shell pidof com.listenlater >/dev/null 2>&1 || { echo "RELEASE SMOKE FAIL: app died during share intake"; exit 1; }

# Logcat verdict: no crash or class-loading failure from our process.
adb logcat -d > /tmp/rsmoke-logcat.txt
if grep -E "FATAL EXCEPTION|NoClassDefFoundError|ClassNotFoundException|NoSuchMethodError|NoSuchFieldError|VerifyError|ExceptionInInitializerError" /tmp/rsmoke-logcat.txt; then
  echo "RELEASE SMOKE FAIL: crash/class-loading failure in logcat"; exit 1
fi
echo "RELEASE SMOKE TEST PASS"
