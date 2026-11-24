#!/usr/bin/env bash
# Release-build R8 smoke: the minified, resource-shrunk, signed release APK must
# run every representative path without a class-loading or crash failure.
# Positive assertions (not just boot liveness):
#  1. Boot: MainActivity comes up and STAYS up (Application + Compose first frame,
#     CredStore/EncryptedSharedPreferences session read, StatusScreen update check
#     over OkHttp once per open).
#  2. Playlist share is REJECTED: no notification, process alive (VideoUrl
#     validation failure branch).
#  3. Valid share produces the sign-in nudge notification. On a fresh install that
#     notification only exists if the WHOLE chain ran on the R8 build: share
#     validation (VideoUrl.cleanSharedUrl) -> durable queue write (org.json
#     serialize + commit) -> WorkManager enqueue -> ShareWorker actually ran ->
#     queue read back (deserialize) -> CredStore/Tink init + session read ->
#     Notifier posted. notifySignInOnce has no other caller on this path.
#  4. Logcat verdict: no crash, class-loading, or security-crypto failure.
# A release build is not debuggable: proof is notification state + process
# liveness + a clean logcat (file inspection is what scripts/share-smoke.sh does
# on debug).
set -x
APK=$(ls ListenLater-v*.apk | grep -v debug | head -1)
[ -n "$APK" ] || { echo "RELEASE SMOKE FAIL: no release APK artifact"; exit 1; }
adb install -r "$APK" || { echo "RELEASE SMOKE FAIL: install failed"; exit 1; }
# POST_NOTIFICATIONS is a runtime permission on API 33+; grant it so the sign-in
# nudge can actually post and serve as the end-to-end assertion.
adb shell pm grant com.listenlater android.permission.POST_NOTIFICATIONS

adb logcat -c

# 1. Boot: MainActivity must come up and STAY up.
adb shell am start -n com.listenlater/.MainActivity 2>&1 | tee /tmp/rsmoke-start.log
if grep -qi "error" /tmp/rsmoke-start.log; then echo "RELEASE SMOKE FAIL: launch error"; exit 1; fi
sleep 12
adb shell pidof com.listenlater >/dev/null 2>&1 || { echo "RELEASE SMOKE FAIL: app died after launch"; exit 1; }

# 2. Playlist share must be REJECTED (validation failure branch, no queue write).
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "https://www.youtube.com/playlist?list=PLrsm0k3rj3ct" \
  --es android.intent.extra.SUBJECT "smoke" -n com.listenlater/.ShareActivity 2>&1 | tee /tmp/rsmoke-pl.log
sleep 8
adb shell pidof com.listenlater >/dev/null 2>&1 || { echo "RELEASE SMOKE FAIL: app died on playlist rejection"; exit 1; }
adb shell dumpsys notification > /tmp/rsmoke-notif-pl.txt
if grep -q "com.listenlater" /tmp/rsmoke-notif-pl.txt; then
  echo "RELEASE SMOKE FAIL: notification posted for a rejected playlist share"; exit 1
fi

# 3. Valid share must run the full chain and post the sign-in nudge.
SMOKE_ID="rsm$(date +%s%N | tail -c 9)"
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "https://www.youtube.com/watch?v=$SMOKE_ID" \
  --es android.intent.extra.SUBJECT "smoke" -n com.listenlater/.ShareActivity 2>&1 | tee /tmp/rsmoke-share.log
if grep -qi "error" /tmp/rsmoke-share.log; then echo "RELEASE SMOKE FAIL: share launch error"; exit 1; fi
sleep 15
adb shell pidof com.listenlater >/dev/null 2>&1 || { echo "RELEASE SMOKE FAIL: app died during share intake"; exit 1; }
adb shell dumpsys notification > /tmp/rsmoke-notif.txt
if ! grep -q "com.listenlater" /tmp/rsmoke-notif.txt; then
  echo "RELEASE SMOKE FAIL: sign-in nudge never posted - share->queue->worker->credstore->notifier chain broken"; exit 1
fi
if ! grep -q "Sign in again to keep sending videos." /tmp/rsmoke-notif.txt; then
  echo "RELEASE SMOKE FAIL: nudge text missing - unexpected notification source"; exit 1
fi

# 4. Logcat verdict: no crash, class-loading, or security-crypto failure.
adb logcat -d > /tmp/rsmoke-logcat.txt
if grep -E "FATAL EXCEPTION|NoClassDefFoundError|ClassNotFoundException|NoSuchMethodError|NoSuchFieldError|VerifyError|ExceptionInInitializerError|GeneralSecurityException|KeyStoreException" /tmp/rsmoke-logcat.txt; then
  echo "RELEASE SMOKE FAIL: crash/class-loading/security failure in logcat"; exit 1
fi
echo "RELEASE SMOKE TEST PASS"
