#!/usr/bin/env bash
# Runs inside the CI emulator: installs the debug APK and captures real screens with demo data.
set -x
APK=app/build/outputs/apk/debug/app-debug.apk
OUT=docs/screenshots
mkdir -p $OUT
adb shell settings put global hide_error_dialogs 1
# Match the primary tester's phone (1440x3088 @ ~649dpi, Galaxy S22 Ultra class)
adb shell wm size 1440x2807
adb shell wm density 649
adb install -r $APK
adb shell pm grant com.listenlater android.permission.POST_NOTIFICATIONS
shot() {
  adb shell am force-stop com.listenlater; sleep 1
  adb shell am start -n com.listenlater/.MainActivity "${@:2}"
  for i in $(seq 1 15); do
    sleep 2
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    if adb shell cat /sdcard/ui.xml 2>/dev/null | grep -q "Listen Later"; then break; fi
  done
  # Proof shots must not be covered by a launch toast; toasts clear within ~4s.
  sleep 6
  adb exec-out screencap -p > $OUT/$1.png
}
# tap_text <visible text or content-desc>: find its bounds in the UI dump and tap its center.
tap_text() {
  TAP_OK=0
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml | grep -o "text=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" | head -1 > /tmp/hit.txt
  if [ ! -s /tmp/hit.txt ]; then
    adb shell cat /sdcard/ui.xml | grep -o "content-desc=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" | head -1 > /tmp/hit.txt
  fi
  B=$(grep -o "\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]" /tmp/hit.txt | head -1)
  if [ -z "$B" ]; then echo "TAP-MISS: no element named '$1' - nothing tapped"; return 1; fi
  X1=$(echo $B | cut -d'[' -f2 | cut -d',' -f1); Y1=$(echo $B | cut -d'[' -f2 | cut -d',' -f2 | cut -d']' -f1)
  X2=$(echo $B | cut -d'[' -f3 | cut -d',' -f1); Y2=$(echo $B | cut -d'[' -f3 | cut -d',' -f2 | cut -d']' -f1)
  if [ "$X2" -le "$X1" ] || [ "$Y2" -le "$Y1" ]; then echo "TAP-MISS: empty bounds for '$1' - nothing tapped"; return 1; fi
  adb shell input tap $(( (X1+X2)/2 )) $(( (Y1+Y2)/2 ))
  TAP_OK=1
}
# swipe_up: scroll the list using the real screen size (never a fixed coordinate).
swipe_up() {
  read SW SH <<< "$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | tr x ' ')"
  case "$SW$SH" in ''|*[!0-9]*) echo "SWIPE-MISS: wm size not numeric ('$SW' '$SH'), swipe skipped"; return 1;; esac
  adb shell input swipe $((SW/2)) $((SH*70/100)) $((SW/2)) $((SH*25/100))
}
# Showcase shots: clean demo feed, no queued or failed rows.
shot warmup --ez demo true
rm -f $OUT/warmup.png
shot showcase-home --ez demo true --ez clean true --ez empty true
adb shell am force-stop com.listenlater; sleep 1
adb shell am start -n com.listenlater/.MainActivity --ez demo true --ez clean true --ez empty true; sleep 6
tap_text "Settings"; sleep 2
adb exec-out screencap -p > $OUT/showcase-settings-top.png
swipe_up; sleep 1
adb exec-out screencap -p > $OUT/showcase-settings-accent.png
# Navigation evidence: scroll kept on Back from Feedback, top on fresh entry.
adb shell am force-stop com.listenlater; sleep 1
adb shell am start -n com.listenlater/.MainActivity --ez demo true --ez clean true --ez empty true; sleep 6
tap_text "Settings"; sleep 2
swipe_up; sleep 1; swipe_up; sleep 1
adb exec-out screencap -p > $OUT/nav-1-settings-scrolled.png
tap_text "Send feedback, report a bug, or suggest a feature" || tap_text "Feedback &amp; bugs"; sleep 2
adb exec-out screencap -p > $OUT/nav-2-feedback.png
tap_text "Back"; sleep 2
adb exec-out screencap -p > $OUT/nav-3-back-to-settings-should-match-1.png
adb shell input keyevent KEYCODE_BACK; sleep 2
adb exec-out screencap -p > $OUT/nav-4-home.png
tap_text "Settings"; sleep 2
adb exec-out screencap -p > $OUT/nav-5-settings-fresh-should-be-top.png
# Large font.
adb shell settings put system font_scale 1.5
adb shell am force-stop com.listenlater; sleep 1
adb shell am start -n com.listenlater/.MainActivity --ez demo true --ez clean true --ez empty true; sleep 6
adb exec-out screencap -p > $OUT/font-1-home.png
tap_text "Settings"; sleep 2
adb exec-out screencap -p > $OUT/font-2-settings-top.png
swipe_up; sleep 1
adb exec-out screencap -p > $OUT/font-3-settings-mid.png
swipe_up; sleep 1
adb exec-out screencap -p > $OUT/font-4-settings-lower.png
adb shell settings put system font_scale 1.0
