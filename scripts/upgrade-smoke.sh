#!/usr/bin/env bash
# Upgrade acceptance (publish runs only, where the new APK carries the REAL
# release signature). Two baselines, each a real in-place upgrade over a PUBLISHED
# release: the CURRENT latest release (read from the releases API at run time;
# v1.7.0 / vc14 when this was written) and the older v1.6.4 (vc13), which keeps
# the earlier migration path covered. For each baseline: install the published
# APK, queue a share with no session, show that the queue exists on the OLD
# version, force-stop, upgrade in place to the new minified build, and prove the
# queue and the encrypted credential store survive.
# Proof points: `adb install -r` succeeds only when signatures match; the
# sign-in screen's waiting copy ("1 video is waiting") renders only when the
# queue row survived; a Tink/Keystore break on the upgraded install would
# crash or drop the app before that screen.
# Failure output separates the app's own state from system windows: every
# failure prints the on-screen packages and texts, the full ui.xml, and the
# logcat evidence, and says which window was on top.
set -x
PKG=com.listenlater
NEW_APK=$(ls ListenLater-v*.apk | grep -v debug | head -1)
[ -n "$NEW_APK" ] || { echo "UPGRADE SMOKE FAIL: no new release APK artifact"; exit 1; }

LATEST_TAG=$(curl -fsSL https://api.github.com/repos/MtndaleRedneck/Listen-Later/releases/latest | jq -r .tag_name)
[ -n "$LATEST_TAG" ] && [ "$LATEST_TAG" != null ] || { echo "UPGRADE SMOKE FAIL: could not read the latest published release"; exit 1; }
BASELINES="$LATEST_TAG"
[ "$LATEST_TAG" = v1.6.4 ] || BASELINES="$LATEST_TAG v1.6.4"
echo "upgrade-smoke baselines: $BASELINES"

EVID=usmoke-evidence
mkdir -p "$EVID"
# Test-only evidence for the run's one-day private-ish artifact: PNG screenshots and text. Never an APK.
shot() { # name
  adb exec-out screencap -p > "$EVID/$1.png" 2>/dev/null
  if dump_ui; then echo "$UI" > "$EVID/$1-ui.txt"; else echo "ui dump failed" > "$EVID/$1-ui.txt"; fi
}

vcode() { adb shell dumpsys package $PKG | grep -m1 -o 'versionCode=[0-9]*' | cut -d= -f2; }

# Fresh, verified dump: the old file is removed first so a failed dump can never be read as the last
# good one. Sets UI and returns 0 only for a successful, non-empty, well-formed dump; otherwise UI is empty.
dump_ui() {
  adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1
  OUT=$(adb shell uiautomator dump /sdcard/ui.xml 2>&1)
  UI=
  echo "$OUT" | grep -q "dumped to" || { echo "ui dump failed: $OUT"; return 1; }
  CAND=$(adb shell cat /sdcard/ui.xml 2>/dev/null)
  case "$CAND" in "<?xml"*"<hierarchy"*) UI="$CAND"; return 0;; esac
  echo "ui dump unreadable"; return 1
}

# The queue proof: a node whose text is the waiting copy AND belongs to the app's package, in a
# fresh dump, with the app's window focused. Text from a system window or a stale file does not count.
waiting_proof() {
  dump_ui || return 1
  echo "$UI" | grep -o '<node[^>]*text="[^"]*1 video is waiting[^"]*"[^>]*>' | grep -q "package=\"$PKG\"" || return 1
  adb shell dumpsys window | grep -m1 mCurrentFocus | grep -q "$PKG" || return 1
  return 0
}

# Print what is on screen: which packages own windows and the texts, so a launcher ANR
# or other system window is told apart from the app's own state.
show_screen() {
  dump_ui || echo "NO VERDICT from this screen: the ui dump failed"
  echo "--- on-screen packages ---"
  echo "$UI" | grep -o 'package="[^"]*"' | sort | uniq -c
  echo "--- on-screen texts ---"
  echo "$UI" | grep -o ' text="[^"]\+"' | head -40
  echo "--- focused window ---"
  adb shell dumpsys window | grep -m3 "mCurrentFocus\|mFocusedApp"
}

fail_diagnostics() {
  echo "--- full ui.xml ---"
  echo "$UI" | fold -w 200
  echo "--- app process now: $(adb shell pidof $PKG | tr -d '\r\n') (was: $PID) ---"
  echo "--- ANR/crash lines for the app and for others since relaunch ---"
  adb logcat -d | grep -E "ANR in |FATAL EXCEPTION|Fatal signal|am_anr|am_crash" | tail -20
  echo "--- app logcat since relaunch (unfiltered tail) ---"
  adb logcat -d | tail -80
}

run_one() {
  OLD_TAG=$1
  OLD_APK=/tmp/old-$OLD_TAG.apk
  echo "=== upgrade from published $OLD_TAG ==="
  adb uninstall $PKG >/dev/null 2>&1
  curl -fsSL -o "$OLD_APK" "https://github.com/MtndaleRedneck/Listen-Later/releases/download/$OLD_TAG/ListenLater-$OLD_TAG.apk"
  [ -s "$OLD_APK" ] || { echo "UPGRADE SMOKE FAIL: could not fetch published $OLD_TAG"; return 1; }
  adb install "$OLD_APK" || { echo "UPGRADE SMOKE FAIL: $OLD_TAG install failed"; return 1; }
  OLD_VC=$(vcode)
  echo "installed baseline $OLD_TAG versionCode=$OLD_VC"
  case "$OLD_VC" in ''|*[!0-9]*) echo "UPGRADE SMOKE SETUP FAIL ($OLD_TAG): could not read a numeric old versionCode (got '$OLD_VC'), so no upgrade verdict is possible"; return 1;; esac

  # Grant up front: MainActivity asks for POST_NOTIFICATIONS on its first-ever launch, and a
  # system permission dialog would cover the sign-in screen. A real upgrading user granted it
  # long ago, and grants survive install -r.
  adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS

  # Queue a share on the old version with no session (unique valid-shaped id).
  SMOKE_ID="upg$(date +%s%N | tail -c 9)"
  adb shell am start -a android.intent.action.SEND -t text/plain \
    --es android.intent.extra.TEXT "https://www.youtube.com/watch?v=$SMOKE_ID" \
    --es android.intent.extra.SUBJECT "upgrade" -n $PKG/.ShareActivity 2>&1 | tee /tmp/usmoke-share.log
  if grep -qi "error" /tmp/usmoke-share.log; then echo "UPGRADE SMOKE FAIL: share launch error on $OLD_TAG"; return 1; fi
  sleep 12

  # BEFORE evidence: the queue exists on the OLD version (its sign-in screen shows the waiting copy).
  adb shell am start -n $PKG/.MainActivity >/dev/null 2>&1
  BEFORE=0
  for i in $(seq 1 10); do
    sleep 4
    if waiting_proof; then BEFORE=1; break; fi
  done
  echo "BEFORE upgrade ($OLD_TAG vc$OLD_VC): waiting copy shown on the old version = $BEFORE"
  shot "before-$OLD_TAG-vc$OLD_VC"
  if [ "$BEFORE" != 1 ]; then
    # SETUP failure, not an upgrade verdict: without the seeded queue confirmed on the old
    # version the after-upgrade check cannot say anything about queue survival.
    echo "UPGRADE SMOKE SETUP FAIL ($OLD_TAG vc$OLD_VC): the seeded queued share was NOT confirmed on the old version before the upgrade, so no upgrade verdict is possible for this baseline"
    show_screen
    return 1
  fi
  adb shell am force-stop $PKG

  # In-place upgrade to the new minified build: signature continuity proven here.
  adb install -r "$NEW_APK" || { echo "UPGRADE SMOKE FAIL: in-place upgrade from $OLD_TAG refused (signature or version problem)"; return 1; }
  NEW_VC=$(vcode)
  echo "versionCode old=$OLD_VC new=$NEW_VC"
  case "$NEW_VC" in ''|*[!0-9]*) echo "UPGRADE SMOKE FAIL ($OLD_TAG): could not read a numeric new versionCode after the upgrade (got '$NEW_VC')"; return 1;; esac
  [ "$NEW_VC" -gt "$OLD_VC" ] || { echo "UPGRADE SMOKE FAIL: versionCode did not increase ($OLD_VC -> $NEW_VC)"; return 1; }

  adb logcat -c
  adb shell am start -n $PKG/.MainActivity 2>&1 | tee /tmp/usmoke-relaunch.log
  if grep -qi "error" /tmp/usmoke-relaunch.log; then echo "UPGRADE SMOKE FAIL: relaunch error after upgrade"; return 1; fi
  # Give the worker its pass: it marks the session missing, which is what shows
  # the waiting copy on the sign-in screen.
  sleep 18
  PID=$(adb shell pidof $PKG | tr -d '\r\n')
  [ -n "$PID" ] || { echo "UPGRADE SMOKE FAIL: app died after upgrade from $OLD_TAG"; return 1; }

  # Assert with dialog dismissal + retry: the emulator can throw SYSTEM windows over the app
  # (a launcher ANR "isn't responding" dialog has covered it). Those are environment, not queue
  # state, so dismiss them, re-focus the app, and re-read. An external ANR is answered with its
  # "Wait" button so nothing is killed. The app's OWN failure is a defect and is never dismissed
  # into a pass: all app-failure detection runs BEFORE the waiting-text check (app ANR or crash
  # dialog, process restart, app ANR/native-crash evidence in logcat since relaunch).
  PASS=0
  UI=
  REASON=""
  DUMP_OK=1
  for i in $(seq 1 15); do
    DUMP_OK=1; dump_ui || DUMP_OK=0
    if echo "$UI" | grep -q "Listen Later isn't responding\|Listen Later keeps stopping"; then
      REASON="the APP itself ANR'd/crashed during the assert - not dismissible"; break
    fi
    CUR=$(adb shell pidof $PKG | tr -d '\r\n')
    if [ "$CUR" != "$PID" ]; then
      REASON="app process changed ($PID -> ${CUR:-none}) during the assert - restart"; break
    fi
    LC=$(adb logcat -d)
    if echo "$LC" | grep -q "ANR in com.listenlater" \
       || echo "$LC" | grep -A8 "FATAL EXCEPTION" | grep -q "Process: com.listenlater" \
       || echo "$LC" | grep "Fatal signal" | grep -q "com.listenlater"; then
      REASON="app ANR/crash evidence in logcat during the assert"; break
    fi
    if [ "$DUMP_OK" = 1 ] && waiting_proof; then PASS=1; break; fi
    if [ "$DUMP_OK" = 0 ]; then
      echo "attempt $i: no successful ui dump; no verdict from this attempt"
    elif echo "$UI" | grep -q "isn't responding"; then
      echo "attempt $i: a system 'isn't responding' window is on top (not the app): answering Wait"
      echo "$UI" | grep -o 'package="[^"]*" content-desc="[^"]*"[^>]*text="[^"]*isn[^"]*"' | head -2
      ANRB=$(echo "$UI" | grep -o 'aerr_wait[^/]*' | grep -o 'bounds="[^"]*"' | head -1)
      if [ -n "$ANRB" ]; then
        N=$(echo "$ANRB" | sed 's/[^0-9]/ /g')
        set -- $N
        [ -n "$4" ] && adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
      fi
    else
      adb shell input keyevent 4
    fi
    adb shell am start -n $PKG/.MainActivity >/dev/null 2>&1
    sleep 8
  done
  if [ "$PASS" != 1 ] && [ -z "$REASON" ] && [ "$DUMP_OK" = 0 ]; then
    REASON="the final attempt had no successful ui dump, so there is no verdict (failing closed, not a queue-loss finding)"
  fi
  shot "after-$OLD_TAG-vc$OLD_VC-to-vc$NEW_VC-pass$PASS"
  adb logcat -d | tail -300 > "$EVID/after-$OLD_TAG-logcat.txt"
  if [ "$PASS" != 1 ]; then
    if [ -n "$REASON" ]; then
      echo "UPGRADE SMOKE FAIL ($OLD_TAG vc$OLD_VC -> vc$NEW_VC): app-side failure: $REASON"
    else
      echo "UPGRADE SMOKE FAIL ($OLD_TAG vc$OLD_VC -> vc$NEW_VC): the waiting copy never appeared in 15 tries and no app ANR/crash/restart was detected (the seeded queue WAS confirmed on the old version before the upgrade). Read the on-screen packages/texts below: if a system window covered the app this is not queue-loss proof, if the app's own sign-in screen showed without the waiting copy the queue was lost."
    fi
    show_screen
    fail_diagnostics
    return 1
  fi

  adb logcat -d --pid="$PID" > /tmp/usmoke-logcat.txt
  if grep -E "FATAL EXCEPTION|NoClassDefFoundError|ClassNotFoundException|NoSuchMethodError|NoSuchFieldError|VerifyError|ExceptionInInitializerError|GeneralSecurityException|KeyStoreException" /tmp/usmoke-logcat.txt; then
    echo "UPGRADE SMOKE FAIL: crash/class-loading/security failure after upgrade from $OLD_TAG"; return 1
  fi
  echo "UPGRADE PASS from $OLD_TAG (vc$OLD_VC -> vc$NEW_VC)"
  return 0
}

FAILED=""
for BASE in $BASELINES; do
  # Run every baseline (no fail-fast) so a current-release failure and an older-release failure are told apart.
  run_one "$BASE" || FAILED="$FAILED $BASE"
done
if [ -n "$FAILED" ]; then
  echo "UPGRADE SMOKE FAIL: baselines failed:$FAILED (of: $BASELINES)"
  exit 1
fi
echo "UPGRADE SMOKE TEST PASS ($BASELINES)"
