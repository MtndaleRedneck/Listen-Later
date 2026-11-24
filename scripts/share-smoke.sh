#!/usr/bin/env bash
# Share smoke + kill persistence: fire a real ACTION_SEND at ShareActivity with a
# UNIQUE-per-run video URL and confirm it lands in the queue store; then force-stop
# and cold-relaunch and confirm it survives. Requires the debug APK installed
# (run-as needs a debuggable build).
#
# Bounds of proof: the XML-before-kill / XML-after-relaunch comparison proves
# POST-WRITE process-death persistence (the write had already committed before the
# kill). It says nothing about surviving a kill DURING a write.
set -x
# Unique ID per run: no leftover from a previous run can false-pass.
# Unique per run AND a syntactically valid YouTube id: VideoUrl.cleanSharedUrl only
# accepts the exact 11-char [A-Za-z0-9_-] shape, so the id is built as "smk" plus 8
# digits from the epoch-nanosecond clock (CI run 45306843042 failed exactly here: a
# 22-char id was correctly rejected by the app and the queue stayed empty).
SMOKE_ID="smk$(date +%s%N | tail -c 9)"
SHARE_URL="https://www.youtube.com/watch?v=$SMOKE_ID"
QFILE="shared_prefs/queue_store.xml"

# Proven absence before the share: this run's ID is not already in the queue.
if adb shell run-as com.listenlater cat "$QFILE" 2>/dev/null | grep -q "$SMOKE_ID"; then
  echo "SHARE SMOKE FAIL: run-unique ID present before the share"; exit 1
fi

# Launch with exit/readiness checks: the intent must resolve and the app must come up.
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "$SHARE_URL" \
  --es android.intent.extra.SUBJECT "smoke" -n com.listenlater/.ShareActivity 2>&1 | tee /tmp/smoke-start.log
if grep -qi "error" /tmp/smoke-start.log; then echo "SHARE SMOKE FAIL: launch error"; exit 1; fi
for i in $(seq 1 10); do
  sleep 1
  adb shell pidof com.listenlater >/dev/null 2>&1 && break
done
adb shell pidof com.listenlater >/dev/null 2>&1 || { echo "SHARE SMOKE FAIL: app process never came up"; exit 1; }

FOUND=""
for i in $(seq 1 15); do
  sleep 2
  if adb shell run-as com.listenlater cat "$QFILE" 2>/dev/null | grep -q "$SMOKE_ID"; then
    FOUND=1; break
  fi
done
if [ -z "$FOUND" ]; then echo "SHARE SMOKE FAIL: shared URL never reached the queue"; exit 1; fi
echo "SHARE SMOKE TEST PASS"

# Control what could consume the queue after relaunch: ShareWorker only consumes
# with a login token, and only an interactive login can write one (none runs in
# this job). Prove token ABSENCE with a direct production read, not file shape:
# the app legitimately writes needsLogin/loginNotified FLAG entries at startup
# when a queued item has no session (MainActivity.kt LaunchedEffect ->
# ShareWorker.kt:62-63 -> Notifier.notifySignInOnce), and EncryptedSharedPreferences
# encrypts key names, so no file-content check can separate flags from credentials
# (5k run 45313566190: an any-entry guard fired on exactly those two flags; the
# worker still held the queue by design). The debug-variant SessionProbeActivity
# reports only TOKEN/SERVER PRESENT-or-ABSENT - never their values.
adb shell am force-stop com.listenlater
if adb shell pidof com.listenlater >/dev/null 2>&1; then echo "KILL PERSISTENCE FAIL: force-stop left a process"; exit 1; fi
adb shell run-as com.listenlater rm -f files/session_probe.txt
adb shell am start -n com.listenlater/.MainActivity 2>&1 | tee /tmp/smoke-relaunch.log
if grep -qi "error" /tmp/smoke-relaunch.log; then echo "KILL PERSISTENCE FAIL: relaunch error"; exit 1; fi
sleep 6
adb shell am start -n com.listenlater/.SessionProbeActivity 2>&1 | tee /tmp/smoke-probe.log
if grep -qi "error" /tmp/smoke-probe.log; then echo "KILL PERSISTENCE FAIL: probe launch error"; exit 1; fi
sleep 2
PROBE=$(adb shell run-as com.listenlater cat files/session_probe.txt 2>/dev/null || true)
if [ "$PROBE" != "TOKEN_ABSENT SERVER_ABSENT" ]; then
  echo "KILL PERSISTENCE FAIL: session state is '${PROBE:-unreadable}', need 'TOKEN_ABSENT SERVER_ABSENT'; consumption not controlled"; exit 1
fi
echo "NO-SESSION PROBE PASS"
if adb shell run-as com.listenlater cat "$QFILE" 2>/dev/null | grep -q "$SMOKE_ID"; then
  echo "SHARE SMOKE FAIL: run-unique ID present before the share"; exit 1
fi

# Launch with exit/readiness checks: the intent must resolve and the app must come up.
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "$SHARE_URL" \
  --es android.intent.extra.SUBJECT "smoke" -n com.listenlater/.ShareActivity 2>&1 | tee /tmp/smoke-start.log
if grep -qi "error" /tmp/smoke-start.log; then echo "SHARE SMOKE FAIL: launch error"; exit 1; fi
for i in $(seq 1 10); do
  sleep 1
  adb shell pidof com.listenlater >/dev/null 2>&1 && break
done
adb shell pidof com.listenlater >/dev/null 2>&1 || { echo "SHARE SMOKE FAIL: app process never came up"; exit 1; }

FOUND=""
for i in $(seq 1 15); do
  sleep 2
  if adb shell run-as com.listenlater cat "$QFILE" 2>/dev/null | grep -q "$SMOKE_ID"; then
    FOUND=1; break
  fi
done
if [ -z "$FOUND" ]; then echo "SHARE SMOKE FAIL: shared URL never reached the queue"; exit 1; fi
echo "SHARE SMOKE TEST PASS"

# Control what could consume the queue after relaunch: ShareWorker only consumes
# with a login token, and only an interactive login can write one (none runs in
# this job). Prove token ABSENCE with a direct production read, not file shape:
# the app legitimately writes needsLogin/loginNotified FLAG entries at startup
# when a queued item has no session (MainActivity.kt LaunchedEffect ->
# ShareWorker.kt:62-63 -> Notifier.notifySignInOnce), and EncryptedSharedPreferences
# encrypts key names, so no file-content check can separate flags from credentials
# (5k run 45313566190: an any-entry guard fired on exactly those two flags; the
# worker still held the queue by design). The debug-variant SessionProbeActivity
# reports only TOKEN/SERVER PRESENT-or-ABSENT - never their values.
adb shell am force-stop com.listenlater
if adb shell pidof com.listenlater >/dev/null 2>&1; then echo "KILL PERSISTENCE FAIL: force-stop left a process"; exit 1; fi
adb shell run-as com.listenlater rm -f shared_prefs/creds.xml shared_prefs/creds.xml.bak
for f in creds.xml creds.xml.bak; do
  if adb shell run-as com.listenlater ls "shared_prefs/$f" >/dev/null 2>&1; then
    echo "KILL PERSISTENCE FAIL: could not remove $f for a proven no-session state"; exit 1
  fi
done
adb shell am start -n com.listenlater/.MainActivity 2>&1 | tee /tmp/smoke-relaunch.log
if grep -qi "error" /tmp/smoke-relaunch.log; then echo "KILL PERSISTENCE FAIL: relaunch error"; exit 1; fi
sleep 6
# An EMPTY EncryptedSharedPreferences file is NOT entry-free: it always carries the
# two tink keyset metadata entries - key names verified from the actual dependency
# (security-crypto-1.1.0-alpha06 EncryptedSharedPreferences.class strings):
#   __androidx_security_crypto_encrypted_prefs_key_keyset__
#   __androidx_security_crypto_encrypted_prefs_value_keyset__
# Real credential keys (token, server, ...) are stored under ENCRYPTED names, so any
# name= attribute outside the two metadata aliases proves a credential was written.
# Nothing in this job logs in, so none may exist.
# Fail closed: a read/access error is NOT an empty store. Genuine absence is
# acceptable ONLY here, after the preflight proved absence pre-kill - it means
# the app did not recreate the store at all, so no credential can exist.
if adb shell run-as com.listenlater ls shared_prefs/creds.xml >/dev/null 2>&1; then
  if ! CREDS_DUMP=$(adb shell run-as com.listenlater cat shared_prefs/creds.xml); then
    echo "KILL PERSISTENCE FAIL: session store exists but could not be read"; exit 1
  fi
  BAD=$(printf '%s\n' "$CREDS_DUMP" \
    | grep -o 'name="[^"]*"' \
    | grep -v '__androidx_security_crypto_encrypted_prefs_key_keyset__' \
    | grep -v '__androidx_security_crypto_encrypted_prefs_value_keyset__' || true)
  if [ -n "$BAD" ]; then
    echo "KILL PERSISTENCE FAIL: session store holds a credential entry after relaunch; consumption not controlled"; exit 1
  fi
else
  echo "session store absent after relaunch (app did not recreate it): no credential can exist"
fi
# The post-relaunch queue read must SUCCEED: an unreadable store is a different
# failure from a lost item.
if ! QXML=$(adb shell run-as com.listenlater cat "$QFILE"); then
  echo "KILL PERSISTENCE FAIL: queue store unreadable after relaunch"; exit 1
fi
if printf '%s' "$QXML" | grep -q "$SMOKE_ID"; then
  echo "KILL PERSISTENCE PASS"
else
  echo "KILL PERSISTENCE FAIL: queued share lost across force-stop + relaunch"; exit 1
fi
