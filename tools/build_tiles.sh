#!/usr/bin/env bash
# Vector tiles + offline style for MapLibre.
# usage: BBOX=minLat,minLon,maxLat,maxLon build_tiles.sh region.osm.pbf work_dir assets_dir
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PBF="$1"; WORK="$2"; OUT="$3"
JAVA_HEAP="${JAVA_HEAP:-8g}"
STYLE_URL="${STYLE_URL:-https://tiles.openfreemap.org/styles/liberty}"
mkdir -p "$WORK" "$OUT"
cd "$WORK"

# 1. tiles with planetiler (OpenMapTiles schema)
[ -s planetiler.jar ] || curl -fL --retry 3 -o planetiler.jar \
  https://github.com/onthegomap/planetiler/releases/latest/download/planetiler.jar
IFS=, read -r a b c d <<< "$BBOX"
# Global helper sources, fetched with curl (planetiler's own downloader times out on slow servers).
# Madhya Pradesh is inland, so if the ocean / lake-line servers are down a placeholder is fine.
SRC="$WORK/sources"; mkdir -p "$SRC"
fetch() {  # url file [placeholder-kind]
  [ -s "$SRC/$2" ] && return 0
  if curl -fL --retry 4 --retry-delay 20 --connect-timeout 60 --max-time 1500 -o "$SRC/$2.part" "$1"; then
    mv "$SRC/$2.part" "$SRC/$2"
  elif [ -n "${3:-}" ]; then
    python3 "$ROOT/tools/dummy_shapefile.py" "$3" "$SRC/$2"
  else
    echo "download failed: $1"; return 1
  fi
}
fetch https://osmdata.openstreetmap.de/download/water-polygons-split-3857.zip water-polygons-split-3857.zip polygon
fetch https://github.com/acalcutt/osm-lakelines/releases/download/v12/lake_centerline.shp.zip lake_centerline.shp.zip line
fetch https://naciscdn.org/naturalearth/packages/natural_earth_vector.sqlite.zip natural_earth_vector.sqlite.zip
ls -lh "$SRC"

java -Xmx"$JAVA_HEAP" -jar planetiler.jar openmaptiles \
  --osm-path="$PBF" --download-dir="$SRC" \
  --water-polygons-path="$SRC/water-polygons-split-3857.zip" \
  --natural-earth-path="$SRC/natural_earth_vector.sqlite.zip" \
  --lake-centerlines-path="$SRC/lake_centerline.shp.zip" \
  --bounds="$b,$a,$d,$c" --maxzoom=14 \
  --exclude-layers=housenumber \
  --output="$WORK/mp.pmtiles" --force
ls -lh mp.pmtiles
cp mp.pmtiles "$OUT/mp.pmtiles"

# 2. style (OpenFreeMap Liberty, made offline)
curl -fL --retry 3 -o liberty.json "$STYLE_URL"
rm -rf "$OUT/mapstyle" && mkdir -p "$OUT/mapstyle/sprites" "$OUT/mapstyle/fonts"
python3 "$ROOT/tools/make_style.py" liberty.json "$OUT/mapstyle" > fonts.txt
echo "fonts:"; cat fonts.txt

# 3. sprites (icons)
SPRITE=$(python3 "$ROOT/tools/make_style.py" --sprite liberty.json)
for suf in .json .png @2x.json @2x.png; do
  curl -fL --retry 3 -o "$OUT/mapstyle/sprites/sprite$suf" "$SPRITE$suf"
done

# 4. glyphs (Latin + punctuation ranges only)
GLYPHS=$(python3 "$ROOT/tools/make_style.py" --glyphs liberty.json)
while IFS=$'\t' read -r font safe; do
  [ -n "$font" ] || continue
  enc=$(python3 -c "import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1]))" "$font")
  mkdir -p "$OUT/mapstyle/fonts/$safe"
  for start in 0 256 512 768 7680 8192 8448 8704 9472 9728; do
    range="$start-$((start + 255))"
    url="${GLYPHS//\{fontstack\}/$enc}"
    url="${url//\{range\}/$range}"
    curl -fsL --retry 3 -o "$OUT/mapstyle/fonts/$safe/$range.pbf" "$url" || echo "missing $font $range"
  done
done < fonts.txt
du -sh "$OUT/mapstyle" "$OUT/mp.pmtiles"
