#!/usr/bin/env bash
# Startet die App im Emulator an einem Punkt des Rennsteigs und macht Bildschirmfotos.
# Aufruf: screenshots.sh <apk> <ausgabeordner>
set -u
APK=$1
OUT=$2
PKG=de.rennsteig.offline
mkdir -p "$OUT"

# Testposition: Punkt auf der Route bei ca. km 27 (Großer Inselsberg)
read -r LON LAT < <(python3 - <<'PY'
import json
try:
    fc = json.load(open("app/src/main/assets/data/route.geojson"))
    line = next(f for f in fc["features"] if f["properties"].get("kind") == "route")
    km = [f for f in fc["features"] if f["properties"].get("kind") == "km" and f["properties"]["km"] == 27]
    lon, lat = km[0]["geometry"]["coordinates"] if km else line["geometry"]["coordinates"][len(line["geometry"]["coordinates"]) // 6]
    print(lon, lat)
except Exception:
    print(10.4686, 50.8517)
PY
)
echo "Testposition: $LAT, $LON"

shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "Bild $1 gespeichert"; }

ui_center() {
  # $1 = Attribut (content-desc|resource-id|text), $2 = Wert, $3 = Index (optional)
  rm -f /tmp/ui.xml
  adb shell uiautomator dump /sdcard/ui.xml >&2 || true
  adb pull /sdcard/ui.xml /tmp/ui.xml > /dev/null 2>&1 || { echo "UI-Dump fehlgeschlagen" >&2; return; }
  python3 - "$1" "$2" "${3:-0}" <<'PY'
import re, sys
attr, val, idx = sys.argv[1], sys.argv[2], int(sys.argv[3])
xml = open("/tmp/ui.xml", encoding="utf-8").read()
hits = []
for m in re.finditer(r"<node [^>]*>", xml):
    node = m.group(0)
    a = re.search(attr + r'="([^"]*)"', node)
    if a and a.group(1) == val:
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            hits.append(((x1 + x2) // 2, (y1 + y2) // 2))
if len(hits) > idx:
    print(*hits[idx])
PY
}

tap() {
  local pos
  dismiss_anr
  pos=$(ui_center "$@")
  if [ -n "$pos" ]; then adb shell input tap $pos; else echo "nicht gefunden: $*"; fi
}

# Der langsame Emulator meldet gelegentlich "System UI isn't responding" – Dialog wegklicken
dismiss_anr() {
  local pos
  for label in Wait Warten; do
    pos=$(ui_center text "$label")
    if [ -n "$pos" ]; then adb shell input tap $pos; echo "ANR-Dialog geschlossen"; sleep 2; fi
  done
}

adb wait-for-device
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put global anr_show_background 0 || true
adb install -r "$APK"
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do
  adb shell pm grant $PKG android.permission.$p || true
done
adb emu geo fix "$LON" "$LAT" 900
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity
for i in $(seq 1 12); do sleep 5; adb emu geo fix "$LON" "$LAT" 900; done
dismiss_anr
shot 01-karte-einnorden

tap content-desc "Höhenprofil"
sleep 4
shot 02-hoehenprofil
tap content-desc "Höhenprofil"
sleep 1

tap content-desc "Liste der Orte"
sleep 4
shot 03-orte-liste
tap resource-id "$PKG:id/itemName" 3
sleep 6
shot 04-ort-details

tap content-desc "Schließen"
sleep 1
tap content-desc "Menü"
sleep 2
tap text "Ganze Route zeigen"
sleep 10
shot 05-gesamte-route

echo "==== logcat der App ===="
APP_PID=$(adb shell pidof $PKG | tr -d '\r')
echo "PID: $APP_PID"
if [ -n "$APP_PID" ]; then adb logcat -d -v brief --pid="$APP_PID" | tail -n 300; fi
echo "==== logcat Mbgl/Rennsteig/Absturz ===="
adb logcat -d -v brief -s Mbgl:V Rennsteig:V AndroidRuntime:E MapLibre:V | tail -n 200
