#!/usr/bin/env bash
# Erzeugt alle Offline-Daten der App aus OpenStreetMap und Höhendaten.
#
# Ergebnis in $OUT:
#   route.geojson, pois.geojson, profile.json  (klein, werden ins Repo übernommen)
#   rennsteig.mbtiles                           (Vektorkarte, OpenMapTiles-Schema)
#   dem.mbtiles                                 (Terrarium-Höhenkacheln für die Schummerung)
#
# Benötigt: curl, osmium-tool, Java 21, Python 3 mit den Paketen aus requirements.txt
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=${WORK:-$ROOT/build/work}
OUT=${OUT:-$ROOT/build/out}
PLANETILER_VERSION=${PLANETILER_VERSION:-0.10.2}
GEOFABRIK=https://download.geofabrik.de/europe/germany
REGIONS=(thueringen bayern/oberfranken hessen)
# grob um den Rennsteig (Hörschel ... Blankenstein) plus Puffer
BBOX=9.75,50.15,12.05,51.25

mkdir -p "$WORK" "$OUT"
cd "$WORK"

echo "== OSM-Extrakte laden"
pbfs=()
for r in "${REGIONS[@]}"; do
  f="$(basename "$r").osm.pbf"
  if [ ! -s "$f" ] || [ "${REFRESH_OSM:-0}" = 1 ]; then
    curl -fsSL --retry 5 --retry-delay 10 -o "$f.tmp" "$GEOFABRIK/$r-latest.osm.pbf"
    mv "$f.tmp" "$f"
  fi
  pbfs+=("$f")
  ls -la "$f"
done

echo "== Zusammenführen und zuschneiden"
osmium merge "${pbfs[@]}" -o merged.osm.pbf --overwrite
osmium extract -b "$BBOX" --set-bounds -s smart merged.osm.pbf -o region.osm.pbf --overwrite
rm -f merged.osm.pbf
osmium fileinfo -e region.osm.pbf | grep -E "Number of (nodes|ways|relations)|Bounding" || true

echo "== POI-Objekte vorfiltern"
osmium tags-filter region.osm.pbf \
  nwr/tourism \
  nwr/amenity=restaurant,cafe,biergarten,pub,fast_food,ice_cream,shelter,drinking_water,water_point \
  nwr/historic \
  nwr/man_made=tower,water_tap \
  nwr/natural=peak,cave_entrance,spring,rock,stone \
  nwr/waterway=waterfall \
  nwr/railway=station,halt \
  -o poi-src.osm.pbf --overwrite

echo "== Route und POIs"
python3 "$ROOT/pipeline/extract_osm.py" \
  --region region.osm.pbf --pois poi-src.osm.pbf \
  --highlights "$ROOT/pipeline/highlights.json" \
  ${RENNSTEIG_RELATION:+--relation "$RENNSTEIG_RELATION"} \
  --out-route "$OUT/route.geojson" --out-pois "$OUT/pois.geojson" \
  --out-corridor-poly corridor.poly --out-corridor-geojson corridor.geojson \
  --out-gpx "$OUT/rennsteig.gpx"

echo "== Höhenmodell und Profil"
python3 "$ROOT/pipeline/build_dem.py" \
  --route "$OUT/route.geojson" --pois "$OUT/pois.geojson" --corridor corridor.geojson \
  --cache "$WORK/dem-cache" --out-mbtiles "$OUT/dem.mbtiles" --out-profile "$OUT/profile.json"

echo "== Vektorkacheln (planetiler $PLANETILER_VERSION)"
if [ ! -s "planetiler-$PLANETILER_VERSION.jar" ]; then
  curl -fsSL --retry 5 -o "planetiler-$PLANETILER_VERSION.jar" \
    "https://github.com/onthegomap/planetiler/releases/download/v$PLANETILER_VERSION/planetiler.jar"
fi
java -Xmx${PLANETILER_HEAP:-4g} -jar "planetiler-$PLANETILER_VERSION.jar" \
  --osm-path=region.osm.pbf \
  --download --download_dir="$WORK/sources" \
  --polygon=corridor.poly \
  --minzoom=0 --maxzoom=14 \
  --languages=de \
  --exclude_layers=housenumber,aerodrome_label,aeroway \
  --tile_compression=none \
  --output="$OUT/rennsteig.mbtiles" --force
ls -la "$OUT"
