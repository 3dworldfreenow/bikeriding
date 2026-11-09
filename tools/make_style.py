#!/usr/bin/env python3
"""
Turns the OpenFreeMap "Liberty" style into a fully offline style for the app.

usage: make_style.py liberty.json out_dir
  out_dir/style.json  – style with placeholders the app fills in at runtime:
                        {PMTILES_URL} -> pmtiles://file:///…/mp.pmtiles
                        {FILES}       -> file:///…/mapstyle   (fonts + sprites)
  prints "font name<TAB>folder id" for each font used so the caller can download glyphs.

Changes made:
  * vector source -> our offline mp.pmtiles; other (online) sources removed
  * labels use English / Latin names (MapLibre Native can't shape Devanagari)
  * one font per text-font (so glyph files are simple folders)
  * adds a hidden "satellite" raster layer (Sentinel-2 cloudless by EOX, online + cacheable)
"""
import json
import sys

SAT_URL = "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2024_3857/default/g/{z}/{y}/{x}.jpg"
SAT_ATTR = ("Sentinel-2 cloudless - https://s2maps.eu by EOX IT Services GmbH "
            "(Contains modified Copernicus Sentinel data 2024)")
EN_NAME = ["coalesce", ["get", "name:en"], ["get", "name_en"], ["get", "name:latin"], ["get", "name"]]


def mentions_name(v):
    if isinstance(v, str):
        return "name" in v
    if isinstance(v, list):
        return any(mentions_name(x) for x in v)
    return False


def first_font(tf):
    if isinstance(tf, list) and tf and tf[0] == "literal" and len(tf) > 1:
        tf = tf[1]
    if isinstance(tf, list) and tf and all(isinstance(x, str) for x in tf):
        return [tf[0]]
    return None


def main():
    src, out = sys.argv[1], sys.argv[2]
    with open(src, encoding="utf-8") as f:
        style = json.load(f)

    vector_ids = [k for k, v in style["sources"].items() if v.get("type") == "vector"]
    if not vector_ids:
        sys.exit("no vector source in style")
    vid = vector_ids[0]
    attribution = style["sources"][vid].get("attribution",
                                             '© OpenMapTiles © OpenStreetMap contributors')
    style["sources"] = {
        vid: {"type": "vector", "url": "{PMTILES_URL}", "attribution": attribution},
        "satellite": {"type": "raster", "tiles": [SAT_URL], "tileSize": 256, "maxzoom": 14,
                      "attribution": SAT_ATTR},
    }
    fonts = set()
    layers = []
    for layer in style["layers"]:
        if "source" in layer and layer["source"] not in style["sources"]:
            continue  # e.g. online hillshade
        lay = layer.get("layout", {})
        if "text-field" in lay and mentions_name(lay["text-field"]):
            lay["text-field"] = EN_NAME
        if "text-font" in lay:
            ff = first_font(lay["text-font"])
            if ff:
                # folder-safe id (no spaces, so file:// glyph URLs need no escaping)
                safe = ff[0].replace(" ", "-")
                lay["text-font"] = [safe]
                fonts.add((ff[0], safe))
        layers.append(layer)
    # satellite goes right above the background
    idx = 1 if layers and layers[0].get("type") == "background" else 0
    layers.insert(idx, {"id": "satellite", "type": "raster", "source": "satellite",
                        "layout": {"visibility": "none"}})
    style["layers"] = layers
    style["glyphs"] = "{FILES}/fonts/{fontstack}/{range}.pbf"
    style["sprite"] = "{FILES}/sprites/sprite"
    style.pop("center", None)
    style.pop("zoom", None)
    style["name"] = "Bike Riding MP (offline Liberty)"

    with open(out + "/style.json", "w", encoding="utf-8") as f:
        json.dump(style, f, ensure_ascii=False, separators=(",", ":"))
    for name, safe in sorted(fonts):
        print(name + "\t" + safe)


def sprite_url(path):
    """prints the sprite base URL of the original style"""
    with open(path, encoding="utf-8") as f:
        s = json.load(f)
    sp = s.get("sprite")
    if isinstance(sp, list):
        sp = sp[0]["url"]
    print(sp)


def glyphs_url(path):
    with open(path, encoding="utf-8") as f:
        print(json.load(f)["glyphs"])


if __name__ == "__main__":
    if sys.argv[1] == "--sprite":
        sprite_url(sys.argv[2])
    elif sys.argv[1] == "--glyphs":
        glyphs_url(sys.argv[2])
    else:
        main()
