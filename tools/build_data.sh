#!/usr/bin/env bash
# Builds all offline data for the app from OpenStreetMap:
#   app/src/main/assets/mp.map      – Mapsforge vector map (drawn on the phone)
#   app/src/main/assets/mp.graph    – routing graph (trip planning, avoid NH/SH ...)
#   app/src/main/assets/places.tsv  – offline search (villages, towns, fuel pumps ...)
#
# Needs: curl, osmium-tool, python3 + pip packages osmium numpy, Java 17+.
# Runs on GitHub Actions (see .github/workflows/build.yml) or any Linux box.
#
# Env overrides:
#   REGION_NAME   name of the state boundary in OSM   (default "Madhya Pradesh")
#   SRC_URL       source extract                      (default Geofabrik India)
#   FALLBACK_BBOX minLat,minLon,maxLat,maxLon if the boundary can't be found
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK:-$ROOT/build-data}"
OUT="${OUT:-$ROOT/app/src/main/assets}"
REGION_NAME="${REGION_NAME:-Madhya Pradesh}"
SRC_URL="${SRC_URL:-https://download.geofabrik.de/asia/india-latest.osm.pbf}"
FALLBACK_BBOX="${FALLBACK_BBOX:-21.0,74.0,26.9,82.85}"
OSMOSIS_VERSION="${OSMOSIS_VERSION:-0.49.2}"
MAPSFORGE_VERSION="${MAPSFORGE_VERSION:-0.25.0}"
JAVA_HEAP="${JAVA_HEAP:-10g}"

mkdir -p "$WORK" "$OUT"
cd "$WORK"

echo "== 1/5 download OSM data"
if [ ! -s source.osm.pbf ]; then
  curl -fL --retry 3 -o source.osm.pbf.part "$SRC_URL"
  mv source.osm.pbf.part source.osm.pbf
fi
ls -lh source.osm.pbf

echo "== 2/5 cut out $REGION_NAME"
osmium tags-filter --overwrite source.osm.pbf "r/name=$REGION_NAME" -o boundary.osm.pbf
osmium export --overwrite boundary.osm.pbf --geometry-types=polygon -o boundary-all.geojson || true
BBOX=$(python3 "$ROOT/tools/pick_boundary.py" boundary-all.geojson "$REGION_NAME" region.geojson || true)
if [ -n "$BBOX" ] && [ -s region.geojson ]; then
  echo "boundary found, bbox $BBOX"
  osmium extract --overwrite -s smart --set-bounds -p region.geojson source.osm.pbf -o region.osm.pbf
else
  BBOX="$FALLBACK_BBOX"
  echo "boundary NOT found, using bbox $BBOX"
  IFS=, read -r a b c d <<< "$BBOX"
  osmium extract --overwrite -s smart --set-bounds -b "$b,$a,$d,$c" source.osm.pbf -o region.osm.pbf
fi
ls -lh region.osm.pbf

echo "== 3/5 routing graph + places"
osmium tags-filter --overwrite region.osm.pbf w/highway nw/place nw/amenity nw/tourism nw/shop \
  nw/historic n/natural=waterfall nw/craft -o filtered.osm.pbf
python3 "$ROOT/tools/build_graph.py" filtered.osm.pbf "$WORK/out"

echo "== 4/5 vector map (mapsforge)"
OSMOSIS="$(find "$WORK" -path '*bin/osmosis' -type f 2>/dev/null | head -1 || true)"
if [ -z "$OSMOSIS" ]; then
  base="https://github.com/openstreetmap/osmosis/releases/download/$OSMOSIS_VERSION/osmosis-$OSMOSIS_VERSION"
  for ext in tar tgz zip; do
    if curl -fL --retry 3 -o "osmosis.$ext" "$base.$ext"; then
      mkdir -p osmosis-dist && cd osmosis-dist
      case $ext in zip) unzip -q "../osmosis.$ext" ;; *) tar xf "../osmosis.$ext" ;; esac
      cd ..
      break
    fi
  done
  OSMOSIS="$(find "$WORK" -path '*bin/osmosis' -type f 2>/dev/null | head -1 || true)"
fi
if [ -z "$OSMOSIS" ]; then
  echo "osmosis download failed, trying the system package"
  sudo apt-get install -y -q osmosis
  OSMOSIS="$(command -v osmosis)"
fi
chmod +x "$OSMOSIS"
"$OSMOSIS" -v 2>&1 | head -3 || true
PLUGIN_DIR="$HOME/.openstreetmap/osmosis/plugins"
mkdir -p "$PLUGIN_DIR"
WRITER="mapsforge-map-writer-$MAPSFORGE_VERSION-jar-with-dependencies.jar"
if [ ! -s "$PLUGIN_DIR/$WRITER" ]; then
  curl -fL --retry 3 -o "$PLUGIN_DIR/$WRITER" \
    "https://repo1.maven.org/maven2/org/mapsforge/mapsforge-map-writer/$MAPSFORGE_VERSION/$WRITER"
fi
IFS=, read -r a b c d <<< "$BBOX"
CENTER="23.2599,77.4126"   # Bhopal
JAVACMD_OPTIONS="-Xmx$JAVA_HEAP" "$OSMOSIS" --rbf file=region.osm.pbf workers=2 \
  --mapfile-writer file="$WORK/out/mp.map" type=hd threads=2 \
  bbox="$a,$b,$c,$d" map-start-position="$CENTER" map-start-zoom=8 \
  preferred-languages=en,hi comment="Madhya Pradesh - data (c) OpenStreetMap contributors (ODbL)"

echo "== 5/5 copy to app assets"
cp "$WORK/out/mp.map" "$WORK/out/mp.graph" "$WORK/out/places.tsv" "$OUT/"
date -u +%Y%m%d%H%M > "$OUT/data_version.txt"
ls -lh "$OUT"
