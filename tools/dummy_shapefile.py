#!/usr/bin/env python3
"""Writes a tiny placeholder shapefile zip (one feature far away from India).
Used when an ocean / lake-line download server is unreachable – Madhya Pradesh
is inland, so these global layers are not needed for our map.
usage: dummy_shapefile.py polygon|line out.zip"""
import io
import sys
import zipfile

import shapefile  # pip install pyshp


def main():
    kind, out = sys.argv[1], sys.argv[2]
    shp, shx, dbf = io.BytesIO(), io.BytesIO(), io.BytesIO()
    if kind == 'polygon':
        w = shapefile.Writer(shp=shp, shx=shx, dbf=dbf, shapeType=shapefile.POLYGON)
        w.field('x', 'N')
        # tiny square near the south-west corner of web mercator (EPSG:3857)
        w.poly([[(-20000000, -19000000), (-19999000, -19000000), (-19999000, -18999000),
                 (-20000000, -18999000), (-20000000, -19000000)]])
        w.record(0)
        name = 'water_polygons'
    else:
        w = shapefile.Writer(shp=shp, shx=shx, dbf=dbf, shapeType=shapefile.POLYLINE)
        w.field('OSM_ID', 'N', size=18)
        w.line([[(-179.0, -85.0), (-178.9, -85.0)]])
        w.record(0)
        name = 'lake_centerline'
    w.close()
    with zipfile.ZipFile(out, 'w') as z:
        z.writestr(name + '.shp', shp.getvalue())
        z.writestr(name + '.shx', shx.getvalue())
        z.writestr(name + '.dbf', dbf.getvalue())
    print('wrote placeholder', out)


if __name__ == '__main__':
    main()
