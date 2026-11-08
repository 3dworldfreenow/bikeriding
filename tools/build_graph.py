#!/usr/bin/env python3
"""
Reads a (pre-filtered) .osm.pbf and writes:
  * mp.graph   – offline routing graph for the app (see graphlib.py)
  * places.tsv – offline search index (cities, towns, villages, fuel, hospitals ...)

usage: build_graph.py input.osm.pbf out_dir
needs: pip install osmium numpy
"""
import os
import sys
import time

import osmium

import graphlib

# category code -> (key, values)
POI = [
    ('fuel', 'amenity', {'fuel'}),
    ('hospital', 'amenity', {'hospital', 'clinic', 'doctors'}),
    ('police', 'amenity', {'police'}),
    ('atm', 'amenity', {'atm', 'bank'}),
    ('food', 'amenity', {'restaurant', 'fast_food', 'cafe', 'food_court'}),
    ('hotel', 'tourism', {'hotel', 'guest_house', 'motel', 'hostel'}),
    ('sight', 'tourism', {'attraction', 'viewpoint', 'museum'}),
    ('repair', 'shop', {'motorcycle', 'motorcycle_repair', 'car_repair', 'tyres'}),
    ('temple', 'amenity', {'place_of_worship'}),
    ('fort', 'historic', {'fort', 'castle', 'monument', 'ruins'}),
    ('water', 'natural', {'waterfall'}),
]
PLACES = {'city', 'town', 'village', 'hamlet', 'suburb', 'neighbourhood', 'locality'}


def poi_category(tags):
    p = tags.get('place')
    if p in PLACES:
        return p
    for code, key, vals in POI:
        v = tags.get(key)
        if v in vals:
            return code
    if tags.get('craft') == 'motorcycle_repair' or tags.get('service:vehicle:motorcycle') == 'yes':
        return 'repair'
    return None


def clean(s):
    return (s or '').replace('\t', ' ').replace('\n', ' ').strip()


class Handler(osmium.SimpleHandler):
    def __init__(self, rc, places_out):
        super().__init__()
        self.rc = rc
        self.places = places_out
        self.n_places = 0

    def _place(self, tags, lat, lon):
        cat = poi_category(tags)
        if not cat:
            return
        name = clean(tags.get('name') or tags.get('name:en') or tags.get('brand'))
        hi = clean(tags.get('name:hi'))
        if not name and not hi:
            if cat in ('fuel', 'atm', 'hospital', 'police', 'repair'):
                name = cat.capitalize()
            else:
                return
        extra = clean(tags.get('operator') or tags.get('brand') or tags.get('religion') or '')
        self.places.write(f'{cat}\t{name}\t{hi}\t{lat:.5f}\t{lon:.5f}\t{extra}\n')
        self.n_places += 1

    def node(self, n):
        if len(n.tags) == 0:
            return
        t = n.tags
        if 'place' in t or 'amenity' in t or 'tourism' in t or 'shop' in t or 'historic' in t or 'natural' in t or 'craft' in t:
            self._place(t, n.location.lat, n.location.lon)

    def way(self, w):
        t = w.tags
        if 'highway' in t:
            info = graphlib.classify_way({k.k: k.v for k in t})
            if info is not None:
                ids, la, lo = [], [], []
                for nd in w.nodes:
                    if not nd.location.valid():
                        continue
                    ids.append(nd.ref)
                    la.append(int(round(nd.location.lat * 1e6)))
                    lo.append(int(round(nd.location.lon * 1e6)))
                self.rc.add(ids, la, lo, info)
                return
        if w.is_closed() and ('amenity' in t or 'tourism' in t or 'shop' in t or 'historic' in t or 'place' in t):
            sx = sy = k = 0
            for nd in w.nodes:
                if nd.location.valid():
                    sx += nd.location.lat
                    sy += nd.location.lon
                    k += 1
            if k:
                self._place(t, sx / k, sy / k)


def main():
    src, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    t0 = time.time()
    rc = graphlib.RoadCollector()
    with open(os.path.join(out, 'places.tsv'), 'w', encoding='utf-8') as pf:
        h = Handler(rc, pf)
        h.apply_file(src, locations=True, idx='flex_mem')
    print(f'parsed in {time.time() - t0:.0f}s, places: {h.n_places}')
    g = graphlib.build_graph(rc)
    graphlib.write_graph(g, os.path.join(out, 'mp.graph'))
    print(f'done in {time.time() - t0:.0f}s')


if __name__ == '__main__':
    main()
