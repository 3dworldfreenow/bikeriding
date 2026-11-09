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
ok=0
for attempt in 1 2 3 4; do
  # --download fetches water polygons / natural earth / lake lines (kept in sources/ between attempts)
  if java -Xmx"$JAVA_HEAP" -jar planetiler.jar openmaptiles \
      --osm-path="$PBF" --download --download-dir="$WORK/sources" \
      --http-retries=5 --http-timeout=180s \
      --bounds="$b,$a,$d,$c" --maxzoom=14 \
      --exclude-layers=housenumber \
      --output="$WORK/mp.pmtiles" --force; then
    ok=1; break
  fi
  echo "planetiler failed (attempt $attempt), retrying in 60 s"; sleep 60
done
[ "$ok" = 1 ] || { echo "planetiler failed"; exit 1; }
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
