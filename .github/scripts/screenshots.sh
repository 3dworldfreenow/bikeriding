#!/usr/bin/env bash
# Runs the real APK on an emulator and takes screenshots (used by .github/workflows/screenshots.yml)
set -x
APK="$1"; OUT="$2"; mkdir -p "$OUT"
PKG=com.bikeriding.mp

# close "System UI / Launcher isn't responding" popups of the slow emulator
dismiss_anr() {
  for k in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
    grep -q "responding" /tmp/ui.xml || return 0
    xy=$(python3 -c "
import re,xml.etree.ElementTree as E
for n in E.parse('/tmp/ui.xml').iter('node'):
    if (n.get('text') or '')=='Wait':
        a,b,c,d=map(int,re.findall(r'\\d+',n.get('bounds'))); print((a+c)//2,(b+d)//2); break")
    [ -n "$xy" ] && adb shell input tap $xy
    sleep 2
  done
}
shot() { sleep "${2:-3}"; dismiss_anr; adb exec-out screencap -p > "$OUT/$1.png"; ls -l "$OUT/$1.png"; }

# tap the first on-screen element whose text contains $1 (case-insensitive)
tap_text() {
  dismiss_anr
  for i in 1 2 3 4 5 6; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
    xy=$(python3 - "$1" <<'P'
import re, sys, xml.etree.ElementTree as ET
want = sys.argv[1].lower()
for n in ET.parse('/tmp/ui.xml').iter('node'):
    t = (n.get('text') or '') + ' ' + (n.get('content-desc') or '')
    if want in t.lower():
        a, b, c, d = map(int, re.findall(r'\d+', n.get('bounds')))
        print((a + c) // 2, (b + d) // 2)
        break
P
)
    if [ -n "$xy" ]; then adb shell input tap $xy; return 0; fi
    sleep 3
  done
  echo "text not found: $1"; return 1
}

adb shell cmd location set-location-enabled true || adb shell settings put secure location_mode 3
adb install -r -g "$APK" || exit 1
# stop the heavy launcher so it can't freeze over our app, let the system settle
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher || true
sleep 20
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true

# keep feeding a GPS fix at Bhopal (New Market)
( while true; do adb emu geo fix 77.4010 23.2330 >/dev/null 2>&1; sleep 2; done ) &
GEO=$!

adb shell am start -n $PKG/.MainActivity
# wait until the first-start map copy is done
for i in $(seq 1 60); do
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  grep -q "offline map" /tmp/ui.xml || break
  sleep 5
done
read W H < <(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | tr x ' ')

shot 01_map_madhya_pradesh 15

tap_text "◎"
shot 02_city_bhopal 20

# search
tap_text "Search village"
sleep 3
adb shell input text Indore
shot 03_search 6
tap_text "City ·"
shot 04_place 4
tap_text "Route here"
shot 05_routes_nh_vs_no_nh 30

tap_text "Options"
shot 06_route_options 4
adb shell input keyevent KEYCODE_BACK

tap_text "Start ride"
sleep 3
adb shell input keyevent KEYCODE_BACK
shot 07_ride_mode 15

# long-press menu
adb shell input keyevent KEYCODE_BACK
sleep 2
tap_text "Stop" || true
sleep 2
adb shell input swipe $((W*2/3)) $((H/3)) $((W*2/3)) $((H/3)) 1500
shot 08_long_press_menu 4

kill $GEO || true
adb logcat -d | grep -iE "bikeriding|AndroidRuntime|mapsforge" | tail -200 > "$OUT/logcat.txt"
ls -l "$OUT"
