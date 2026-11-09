# 🏍️ Bike Riding MP – offline maps for Madhya Pradesh

My own OsmAnd-style app, starting with **Madhya Pradesh only**.
Everything is **inside the APK**, so after you install it you need **no internet**.

## What it does

| Feature | How |
|---|---|
| 🗺 Smooth offline map of all of MP | **MapLibre** GPU vector map (like Google Maps): smooth zoom, **2-finger rotate & tilt**, compass. OpenFreeMap *Liberty* style |
| 🛰 Satellite / hybrid view | Sentinel-2 cloudless imagery (EOX) with roads & names on top. Needs internet – or **save satellite for your route / visible area** on Wi‑Fi and use it offline |
| 📍 Live location | GPS only – works with mobile data off. Speed in km/h. Tap ◎ once = follow, twice = map turns with your direction |
| 🧭 Trip planning | Long-press the map (or search) → **two routes side by side: NH allowed vs avoid NH**, with km, time, NH / SH km, twisty km, main roads |
| 🌀 Riding styles | **Fastest · Curvy · Extra curvy · Shortest** (Curvy = fun winding roads, avoids cities & highways – idea from Kurviger) |
| ⚙ Avoid roads | NH, SH, all main roads, very small lanes, kachcha roads, **water crossings (monsoon)**, city centres, toll. Expressways skipped by default |
| ➕ Stops on the way | Long-press → "Add stop on the way" |
| 💾 Saved trips | Save plans, open / rename / delete them, start a ride from a saved trip, export the route as GPX |
| ▶ Ride mode | Map turns with you (3D tilt), distance & time left, current & next road, auto re-route |
| ⏺ Ride recording | GPX track (works with screen off), saved to `Download/BikeRidingMP/` |
| 🔍 Offline search | Cities, towns, villages + nearest petrol pump, hospital, bike repair, police, ATM, food, hotels, sights, forts, waterfalls |

## Get the APK

1. Open the **Actions** tab of this repo → workflow **"Build offline APK"** runs on every push to `main`
   (or click **Run workflow**). The first run takes ~1 hour because it builds the MP map.
2. When it is green, open **Releases** (right side of the repo page) → download `BikeRidingMP-xxxx.apk`.
3. On the phone open the APK → allow *Install unknown apps* → install.
4. First start copies the map inside the phone (~1 minute). After that it opens instantly.

> The APK is big (a few hundred MB) because the whole state's map, road network and places are inside.

## How it works

```
OpenStreetMap India  ──►  cut Madhya Pradesh boundary  ──►  mp.pmtiles + mapstyle/  (vector tiles by planetiler, drawn by MapLibre)
(Geofabrik, weekly)        (osmium)                     ├─►  mp.graph     (road graph for routing, our own format)
                                                        └─►  places.tsv   (search index)
                                     all are packed into the APK (app/src/main/assets)
```

* `tools/build_data.sh` – downloads OSM data and builds everything (runs on GitHub Actions).
* `tools/build_tiles.sh`, `tools/make_style.py` – vector tiles + offline map style (fonts, icons).
* `tools/graphlib.py`, `tools/build_graph.py` – road graph builder. Detects **NH** from `ref=NH…`
  (and `highway=trunk`), **SH** from `ref=SH…`, toll, unpaved, fords, one-way, expressways,
  curviness (degrees turned per km) and city areas.
* `app/src/main/java/com/bikeriding/mp/routing/` – offline A\* router (pure Java, unit-tested).
  "Avoid" is a soft avoid: NH is used only if there is no other road.
* `app/src/main/java/com/bikeriding/mp/` – the Android app (map, GPS, search, ride mode, recording).

Map data is refreshed every week automatically (cache key changes weekly), or run the workflow
with **rebuild_data = true**.

## Build on your own PC (optional)

```bash
sudo apt install osmium-tool && pip install osmium numpy   # Linux, Java 21
tools/build_data.sh          # ~30-60 min, needs ~12 GB RAM and ~10 GB disk
./gradlew assembleRelease    # APK in app/build/outputs/apk/release/
```
Without the data the app still builds and opens, but shows "Offline map missing".

## Same signing key for updates (recommended)

By default each build is signed with a temporary key, so to update you must uninstall the old app first.
To install updates on top, create a keystore once and add these repo **secrets**
(Settings → Secrets and variables → Actions):

```bash
keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias bikeriding
base64 -w0 release.jks   # → SIGNING_KEYSTORE_BASE64
```
`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` (=bikeriding), `SIGNING_KEY_PASSWORD`.

## Next states

Set `REGION_NAME` (e.g. `"Rajasthan"`) in `tools/build_data.sh` – the boundary is cut automatically.

## Credits

Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (ODbL) ·
© [OpenMapTiles](https://openmaptiles.org/) · style [OpenFreeMap Liberty](https://openfreemap.org/) · map engine [MapLibre](https://maplibre.org/) · satellite [Sentinel-2 cloudless](https://s2maps.eu) by EOX IT Services GmbH (contains modified Copernicus Sentinel data, CC BY-NC-SA 4.0) · inspired by [OsmAnd](https://osmand.net/) and [Kurviger](https://kurviger.com/).
