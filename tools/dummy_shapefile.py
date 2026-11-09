#!/usr/bin/env python3
"""Writes a tiny placeholder shapefile zip (one feature far away from India).
Used when an ocean / lake-line download server is unreachable – Madhya Pradesh
is inland, so these global layers are not needed for our map.
usage: dummy_shapefile.py polygon|line out.zip"""
import io
import sys
import zipfile

import shapefile  # pip install pyshp


WGS84_PRJ = ('GEOGCS["GCS_WGS_1984",DATUM["D_WGS_1984",SPHEROID["WGS_1984",6378137,298.257223563]],'
             'PRIMEM["Greenwich",0],UNIT["Degree",0.017453292519943295]]')


def main():
    kind, out = sys.argv[1], sys.argv[2]
    shp, shx, dbf = io.BytesIO(), io.BytesIO(), io.BytesIO()
    if kind == 'polygon':
        w = shapefile.Writer(shp=shp, shx=shx, dbf=dbf, shapeType=shapefile.POLYGON)
        w.field('x', 'N')
        # tiny square in Antarctica (lon/lat, WGS84)
        w.poly([[(-179.9, -80.0), (-179.8, -80.0), (-179.8, -79.9), (-179.9, -79.9), (-179.9, -80.0)]])
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
        z.writestr(name + '.prj', WGS84_PRJ)
    print('wrote placeholder', out)


if __name__ == '__main__':
    main()
