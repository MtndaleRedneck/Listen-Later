#!/usr/bin/env bash
# Share smoke test: fire a real ACTION_SEND at ShareActivity and confirm the
# video URL actually landed in the queue store. Requires the debug APK
# installed on the emulator (run-as needs a debuggable build).
set -x
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
