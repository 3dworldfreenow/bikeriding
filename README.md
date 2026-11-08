# 🏍️ Bike Riding MP – offline maps for Madhya Pradesh

My own OsmAnd-style app, starting with **Madhya Pradesh only**.
Everything is **inside the APK**, so after you install it you need **no internet**.

## What it does

| Feature | How |
|---|---|
| 🗺 Offline map of all of MP | Vector map (mapsforge, *Motorider* style for bikers), zoom from state level down to village lanes |
| 📍 Live location | GPS only – works with mobile data off and in no-network areas. Speed shown in km/h |
| 🧭 Trip planning | Long-press the map (or search) → **two routes side by side: "Via highways (NH allowed)" and "Avoid NH"**. Shows km, time, how many km on NH / SH, and main roads used |
| ⚙ Route options | Fastest / shortest, avoid NH, avoid State Highways (SH), avoid toll, avoid kachcha (unpaved) roads. Expressways are skipped by default (two-wheelers usually not allowed) |
| ➕ Stops on the way | Long-press → "Add stop on the way" |
| ▶ Ride mode | Distance & time left, current road, next road, auto re-route if you go off route, screen stays on |
| ⏺ Ride recording | Records your ride as GPX (works with screen off). Saved to `Download/BikeRidingMP/` |
| 🔍 Offline search | Cities, towns, villages (English or हिंदी) + nearest petrol pump, hospital, bike repair, police, ATM, food, hotels, sights, forts, waterfalls |
| 🔤 Hindi map labels | Menu ⋮ → Map labels: हिंदी |

## Get the APK

1. Open the **Actions** tab of this repo → workflow **"Build offline APK"** runs on every push to `main`
   (or click **Run workflow**). The first run takes ~1 hour because it builds the MP map.
2. When it is green, open **Releases** (right side of the repo page) → download `BikeRidingMP-xxxx.apk`.
3. On the phone open the APK → allow *Install unknown apps* → install.
4. First start copies the map inside the phone (~1 minute). After that it opens instantly.

> The APK is big (a few hundred MB) because the whole state's map, road network and places are inside.

## How it works

```
OpenStreetMap India  ──►  cut Madhya Pradesh boundary  ──►  mp.map      (mapsforge vector map, drawn on phone)
(Geofabrik, weekly)        (osmium)                     ├─►  mp.graph    (road graph for routing, our own format)
                                                        └─►  places.tsv  (search index)
                                     all three are packed into the APK (app/src/main/assets)
```

* `tools/build_data.sh` – downloads OSM data and builds the 3 files (runs on GitHub Actions).
* `tools/graphlib.py`, `tools/build_graph.py` – road graph builder. Detects **NH** from `ref=NH…`
  (and `highway=trunk`), **SH** from `ref=SH…`, toll, unpaved, one-way, expressways.
* `app/src/main/java/com/bikeriding/mp/routing/` – offline A\* router (pure Java, unit-tested).
  "Avoid" is a soft avoid: NH is used only if there is no other road.
* `app/src/main/java/com/bikeriding/mp/` – the Android app (map, GPS, search, ride mode, recording).

Map data is refreshed every week automatically (cache key changes weekly), or run the workflow
with **rebuild_data = true**.

## Build on your own PC (optional)

```bash
sudo apt install osmium-tool && pip install osmium numpy   # Linux, Java 17
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
rendering by [mapsforge](https://github.com/mapsforge/mapsforge) (LGPL) · inspired by [OsmAnd](https://osmand.net/).
