#!/usr/bin/env python3
"""Picks the state polygon (admin_level=4) out of an osmium GeoJSON export.
usage: pick_boundary.py boundary-all.geojson "Madhya Pradesh" region.geojson
prints the bbox as minLat,minLon,maxLat,maxLon"""
import json
import sys


def coords(geom):
    if geom['type'] == 'Polygon':
        for ring in geom['coordinates']:
            yield from ring
    elif geom['type'] == 'MultiPolygon':
        for poly in geom['coordinates']:
            for ring in poly:
                yield from ring


def main():
    src, name, out = sys.argv[1], sys.argv[2], sys.argv[3]
    with open(src, encoding='utf-8') as f:
        data = json.load(f)
    best, best_n = None, 0
    for feat in data.get('features', []):
        p = feat.get('properties') or {}
        g = feat.get('geometry') or {}
        if g.get('type') not in ('Polygon', 'MultiPolygon'):
            continue
        if p.get('name') != name or p.get('boundary') != 'administrative':
            continue
        if str(p.get('admin_level')) != '4':
            continue
        n = sum(1 for _ in coords(g))
        if n > best_n:
            best, best_n = feat, n
    if best is None:
        sys.exit(1)
    with open(out, 'w', encoding='utf-8') as f:
        json.dump({'type': 'FeatureCollection', 'features': [best]}, f)
    pts = list(coords(best['geometry']))
    lons = [c[0] for c in pts]
    lats = [c[1] for c in pts]
    m = 0.02
    print(f'{min(lats) - m:.4f},{min(lons) - m:.4f},{max(lats) + m:.4f},{max(lons) + m:.4f}')


if __name__ == '__main__':
    main()
