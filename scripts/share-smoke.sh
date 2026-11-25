#!/usr/bin/env bash
# Share smoke + kill persistence: fire a real ACTION_SEND at ShareActivity with a
# UNIQUE-per-run video URL and confirm it lands in the queue store; then force-stop
# and cold-relaunch and confirm it survives, with consumption proven impossible.
# Requires the debug APK installed (run-as needs a debuggable build).
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
# with a login token AND server, and only an interactive login can write them
# (none runs in this job). Prove their ABSENCE with a direct production read, not
# file shape: the app legitimately writes needsLogin/loginNotified FLAG entries at
# startup when a queued item has no session (MainActivity.kt LaunchedEffect ->
# ShareWorker.kt:62-63 -> Notifier.notifySignInOnce), and EncryptedSharedPreferences
# encrypts key names, so no file-content check can separate flags from credentials
# (5k run 45313566190: an any-entry guard fired on exactly those two flags; the
# worker still held the queue by design). The debug-variant SessionProbeActivity
# reports only TOKEN/SERVER PRESENT-or-ABSENT - never their values.
adb shell am force-stop com.listenlater
if adb shell pidof com.listenlater >/dev/null 2>&1; then echo "KILL PERSISTENCE FAIL: force-stop left a process"; exit 1; fi
adb shell run-as com.listenlater rm -f files/session_probe.txt
if adb shell run-as com.listenlater ls files/session_probe.txt >/dev/null 2>&1; then
  echo "KILL PERSISTENCE FAIL: could not remove the stale probe file"; exit 1
fi
adb shell am start -n com.listenlater/.MainActivity 2>&1 | tee /tmp/smoke-relaunch.log
if grep -qi "error" /tmp/smoke-relaunch.log; then echo "KILL PERSISTENCE FAIL: relaunch error"; exit 1; fi
sleep 6
adb shell am start -n com.listenlater/.SessionProbeActivity --es run_id "$SMOKE_ID" 2>&1 | tee /tmp/smoke-probe.log
if grep -qiE "error|denial|exception" /tmp/smoke-probe.log; then echo "KILL PERSISTENCE FAIL: probe launch error"; exit 1; fi
# Wait for the probe file (bound to this run id, below) for up to 20s instead of one fixed 2s read:
# a slow emulator can take longer to start the probe activity.
PROBE=""
for _ in $(seq 1 20); do
  sleep 1
  PROBE=$(adb shell run-as com.listenlater cat files/session_probe.txt 2>/dev/null || true)
  case "$PROBE" in "$SMOKE_ID"*) break;; esac
done
# The run-id prefix binds the answer to THIS run: a leftover file (removal or
# fresh write failed silently) carries an old id and fails here.
if [ "$PROBE" != "$SMOKE_ID TOKEN_ABSENT SERVER_ABSENT" ]; then
  echo "KILL PERSISTENCE FAIL: session state is '${PROBE:-unreadable}', need '$SMOKE_ID TOKEN_ABSENT SERVER_ABSENT'; consumption not controlled"; exit 1
fi
echo "NO-SESSION PROBE PASS"

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
