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
# First launch warms up and seeds; captured, then discarded.
shot warmup --ez demo true
sleep 5
rm -f $OUT/warmup.png
shot v15-status --ez demo true
cp $OUT/v15-status.png docs/main.png
shot v15-loadfailed --ez demo true --ez loadfail true --ez empty true
shot v15-signin --ez demo true --ez signedout true

# v110: press feedback, accent picker, spacing and Feedback screen.
shot v110-home --ez demo true --ez queuestates true
adb shell am force-stop com.listenlater; sleep 1
adb shell am start -n com.listenlater/.MainActivity --ez demo true; sleep 5
tap_text "Settings"; sleep 2
adb exec-out screencap -p > $OUT/v110-settings-top.png
# Press and hold the server pill: the sunk, dimmed state is captured while the finger is still down.
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
SRV=$(adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*\.[^"]*"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -v 'Version\|Feed' | head -1)
echo "SERVER-PILL-NODE: $SRV"
B=$(echo "$SRV" | grep -o "\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]" | head -1)
if [ -n "$B" ]; then
  X1=$(echo $B | cut -d'[' -f2 | cut -d',' -f1); Y1=$(echo $B | cut -d'[' -f2 | cut -d',' -f2 | cut -d']' -f1)
  X2=$(echo $B | cut -d'[' -f3 | cut -d',' -f1); Y2=$(echo $B | cut -d'[' -f3 | cut -d',' -f2 | cut -d']' -f1)
  adb shell "input swipe $(( (X1+X2)/2 )) $(( (Y1+Y2)/2 )) $(( (X1+X2)/2 )) $(( (Y1+Y2)/2 )) 3500" &
  sleep 1.5
  adb exec-out screencap -p > $OUT/v110-server-pressed.png
  wait
else echo "REQUIRED-MISS: server pill not found"; fi
adb shell am force-stop com.listenlater; sleep 1
adb shell am start -n com.listenlater/.MainActivity --ez demo true; sleep 5
tap_text "Settings"; sleep 2
swipe_up; sleep 1
adb exec-out screencap -p > $OUT/v110-settings-mid.png
# Haptics switch off (the whole row toggles), then the bottom of Settings.
tap_text "Vibrate on press" || echo "REQUIRED-TAP-MISS: Vibrate on press"; sleep 1
adb exec-out screencap -p > $OUT/v110-vibrate-off.png
swipe_up; swipe_up; sleep 1
adb exec-out screencap -p > $OUT/v110-settings-bottom.png
shot v110-accent-indigo --ez demo true --ei accent 6 --ez queuestates true
adb shell am force-stop com.listenlater; sleep 1
adb shell am start -n com.listenlater/.MainActivity --ez demo true --ei accent 1; sleep 5
tap_text "Settings"; sleep 2
adb exec-out screencap -p > $OUT/v110-settings-burnt-orange.png
swipe_up; swipe_up; sleep 1
tap_text "Send feedback, report a bug, or suggest a feature"; sleep 2
adb exec-out screencap -p > $OUT/v110-feedback.png
tap_text "Your message"; sleep 1
adb shell input text "It_looks_fine"; sleep 1
adb exec-out screencap -p > $OUT/v110-feedback-typed.png
# Sign-out confirm dialog.
adb shell am force-stop com.listenlater; sleep 1
adb shell am start -n com.listenlater/.MainActivity --ez demo true; sleep 5
tap_text "Settings"; sleep 2
tap_text "Sign out"; sleep 1
adb exec-out screencap -p > $OUT/v15-signout-confirm.png
adb shell input keyevent KEYCODE_BACK; sleep 1
# Settings (Check for updates button) and Feedback buttons.
adb shell am force-stop com.listenlater; sleep 1
adb shell am start -n com.listenlater/.MainActivity --ez demo true; sleep 5
tap_text "Settings"; sleep 2
adb exec-out screencap -p > $OUT/v15-settings.png
tap_text "Send feedback, report a bug, or suggest a feature"; sleep 2
adb exec-out screencap -p > $OUT/v15-feedback.png
# The new queue states, with real shared titles: sending, retrying at a time,
# unexpected server replies past an hour, one very long title for the wrap check.
shot v16-queued-states --ez demo true --ez queuestates true
# Same rows while a sign-in is outstanding.
shot v16-waiting-signin --ez demo true --ez queuestates true --ez needslogin true --ez staystatus true
# Startup background: the app's plain color while storage readies - no logo, no spinner.
adb shell am force-stop com.listenlater; sleep 1
adb shell am start -n com.listenlater/.MainActivity --ez demo true --ez holdgate true
sleep 2
adb exec-out screencap -p > $OUT/v16-startup.png
sleep 4
# A real send toast with the worker's own wording.
adb shell am force-stop com.listenlater; sleep 1
adb shell am start -n com.listenlater/.MainActivity --ez demo true --ez queuestates true --ez toast true
for i in $(seq 1 10); do
  sleep 1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  if adb shell cat /sdcard/ui.xml 2>/dev/null | grep -q "Listen Later"; then break; fi
done
adb exec-out screencap -p > $OUT/v16-toast.png
sleep 4
# Scrolled down: the long bottom title must be reachable by scrolling, not cut off.
adb shell am force-stop com.listenlater; sleep 1
adb shell am start -n com.listenlater/.MainActivity --ez demo true --ez queuestates true
sleep 6
swipe_up; sleep 1
adb exec-out screencap -p > $OUT/v16-scrolled.png
# Responsive check: small width + large font (review risk: fixed heights, wide rows).
adb shell wm size 1080x2280
adb shell wm density 440
adb shell settings put system font_scale 1.3
shot v17-responsive-status --ez demo true --ez queuestates true
adb shell am force-stop com.listenlater; sleep 1
adb shell am start -n com.listenlater/.MainActivity --ez demo true; sleep 5
tap_text "Settings"; sleep 2
adb exec-out screencap -p > $OUT/v17-responsive-settings.png
adb shell settings put system font_scale 1.0
adb shell wm size reset
adb shell wm density reset
# v17: an expired session on a cold start lands on sign-in - the status screen is only
# restored when needsLogin is clear, and the observable StateFlow keeps sign-in in place.
# (The in-place drop mid-session is the same code path; captcha needs a live server and
# cannot be exercised on the emulator.)
shot v17-auth-expired-signin --ez demo true --ez needslogin true

# v19: centered status labels, server pill, up-to-date line clearing after 5 seconds.
adb shell wm size 1440x2807
adb shell wm density 649
shot v19-status-centered --ez demo true --ez centered true --ez queuestates true
shot v19-status-right --ez demo true --ez queuestates true
shot v19-disconnected --ez demo true --ez loadfail true
adb shell am force-stop com.listenlater; sleep 1
adb shell am start -n com.listenlater/.MainActivity --ez demo true --ez centered true; sleep 5
tap_text "Settings"; sleep 2
adb exec-out screencap -p > $OUT/v19-settings-centered.png
adb shell am force-stop com.listenlater; sleep 1
adb shell "am start -n com.listenlater/.MainActivity --ez demo true --es updateResult \"You're up to date.\""; sleep 5
tap_text "Settings" || echo "REQUIRED-TAP-MISS: Settings (uptodate)"
UTD0=$(date +%s)   # result countdown starts when Settings opens (tap just completed, +-1s)
utd_shot() { adb exec-out screencap -p > $OUT/$1.png; echo "UTD-TIME: $1 captured $(( $(date +%s) - UTD0 ))s after Settings tap"; }
swipe_up
utd_shot v19-uptodate-early
sleep 1
utd_shot v19-uptodate-early2
while [ $(( $(date +%s) - UTD0 )) -lt 8 ]; do sleep 0.5; done
utd_shot v19-uptodate-late
# Narrow width and large font with the centered switch on.
adb shell wm size 1080x2280
adb shell wm density 440
adb shell settings put system font_scale 1.3
shot v19-responsive-centered --ez demo true --ez centered true --ez queuestates true
shot v19-responsive-episodes-centered --ez demo true --ez centered true
swipe_up; sleep 1
adb exec-out screencap -p > $OUT/v19-responsive-episodes-centered.png
shot v19-responsive-disconnected --ez demo true --ez loadfail true
# 360dp wide (1080 px at 480 dpi) with font 1.3: the narrowest common phone.
adb shell wm size 1080x2280
adb shell wm density 480
shot v19-360dp-disconnected --ez demo true --ez loadfail true
shot v19-360dp-queuestates --ez demo true --ez centered true --ez queuestates true
shot v19-360dp-episodes-centered --ez demo true --ez centered true
swipe_up; sleep 1
adb exec-out screencap -p > $OUT/v19-360dp-episodes-centered.png
adb shell settings put system font_scale 1.0
adb shell wm size reset
adb shell wm density reset

# v19 repeat timer MEASUREMENT (not a gate, not exact proof): real network check twice.
# uiautomator dumps take ~2s each, so ages carry about +-2s. Prints a time series of
# whether "You're up to date." is visible, relative to when it was first seen.
adb shell am force-stop com.listenlater; sleep 1
adb shell am start -n com.listenlater/.MainActivity --ez demo true; sleep 5
tap_text "Settings"; sleep 2
has_utd() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml | grep -q "You're up to date."; }
tap_text "Check for updates" || echo "TIMER-MEASURE: INCONCLUSIVE, check button not found"
CX=$(( (X1+X2)/2 )); CY=$(( (Y1+Y2)/2 ))
T1=""
if [ "$TAP_OK" != 1 ]; then CY=0; fi
for i in $(seq 1 20); do if has_utd; then T1=$(date +%s); break; fi; done
if [ "$TAP_OK" != 1 ]; then :; elif [ -z "$T1" ]; then echo "TIMER-MEASURE: no up-to-date line seen after the first check"; else
  adb shell input tap $CX $CY
  echo "TIMER-MEASURE: first result seen at age 0 (+-2s); second check tapped at age $(( $(date +%s) - T1 ))s"
  for i in $(seq 1 6); do
    if has_utd; then V=visible; else V=gone; fi
    echo "TIMER-MEASURE: age $(( $(date +%s) - T1 ))s after first-seen: $V (gone can mean the 2nd tap cleared it while re-checking, not expiry; final-gone age is the number to read)"
  done
fi
# Centered and right-aligned feed rows with every episode status (scrolled to the bottom).
adb shell am force-stop com.listenlater; sleep 1
adb shell am start -n com.listenlater/.MainActivity --ez demo true --ez centered true; sleep 6
swipe_up; sleep 1
adb exec-out screencap -p > $OUT/v19-episodes-centered.png
adb shell am force-stop com.listenlater; sleep 1
adb shell am start -n com.listenlater/.MainActivity --ez demo true; sleep 6
swipe_up; sleep 1
adb exec-out screencap -p > $OUT/v19-episodes-right.png
ls -la $OUT
adb shell wm size reset
adb shell wm density reset
