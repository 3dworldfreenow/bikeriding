#!/usr/bin/env bash
# Builds all offline data for the app from OpenStreetMap:
#   app/src/main/assets/mp.pmtiles  – vector tiles (OpenMapTiles schema, drawn by MapLibre)  [part "tiles"]
#   app/src/main/assets/mapstyle/   – offline map style, fonts, icons                        [part "tiles"]
#   app/src/main/assets/mp.graph    – routing graph (trip planning, avoid NH/SH ...)          [part "graph"]
#   app/src/main/assets/places.tsv  – offline search (villages, towns, fuel pumps ...)        [part "graph"]
#
# PARTS="graph tiles" (default both) chooses what to build.
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
PARTS="${PARTS:-graph tiles}"
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

has_part() { case " $PARTS " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

if has_part graph; then
  echo "== 3/5 routing graph + places"
  osmium tags-filter --overwrite region.osm.pbf w/highway nw/place nw/amenity nw/tourism nw/shop \
    nw/historic n/natural=waterfall nw/craft -o filtered.osm.pbf
  python3 "$ROOT/tools/build_graph.py" filtered.osm.pbf "$WORK/out"
  cp "$WORK/out/mp.graph" "$WORK/out/places.tsv" "$OUT/"
  date -u +%Y%m%d%H%M > "$OUT/data_version.txt"
fi

if has_part tiles; then
  echo "== 4/5 vector tiles + offline style"
  BBOX="$BBOX" JAVA_HEAP="$JAVA_HEAP" "$ROOT/tools/build_tiles.sh" "$WORK/region.osm.pbf" "$WORK/tiles" "$OUT"
fi

echo "== 5/5 done"
ls -lhR "$OUT" | head -40
