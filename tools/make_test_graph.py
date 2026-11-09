#!/usr/bin/env python3
"""Writes a tiny synthetic graph used by the Java unit tests (app/src/test/resources/test.graph).

    V1 ---------- Gaon Road (village) ---------- V2
    |                                             |
    X ============ NH46 (trunk) ======= M ======== Y
     \\_____________ expressway (motorway) _______/
                                        |
                                        S (residential spur)
"""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
import graphlib  # noqa: E402

X = (1, 23.000, 77.000)
M = (2, 23.000, 77.250)
Y = (3, 23.000, 77.500)
V1 = (10, 23.080, 77.050)
V2 = (11, 23.080, 77.450)
S = (20, 22.950, 77.250)


def way(rc, nodes, tags):
    info = graphlib.classify_way(tags)
    assert info is not None, tags
    rc.add([n[0] for n in nodes], [int(n[1] * 1e6) for n in nodes], [int(n[2] * 1e6) for n in nodes], info)


def main(out):
    rc = graphlib.RoadCollector()
    # NH with an intermediate shape point (id 100)
    way(rc, [X, (100, 23.0005, 77.125), M, Y], {'highway': 'trunk', 'ref': 'NH46'})
    # village road: wiggly so it is "curvy"
    wig = [(1000 + i, 23.080 + (0.0015 if i % 2 else -0.0015), 77.06 + i * 0.002) for i in range(195)]
    way(rc, [X, V1] + wig + [V2, Y], {'highway': 'unclassified', 'name': 'Gaon Road'})
    # a ford on a short side road from M going north
    way(rc, [M, (400, 23.02, 77.25)], {'highway': 'unclassified', 'name': 'Nala Crossing', 'ford': 'yes'})
    way(rc, [X, (102, 22.990, 77.25), Y], {'highway': 'motorway', 'ref': 'NE3', 'name': 'Test Expressway'})
    way(rc, [M, S], {'highway': 'residential', 'name': 'Spur Lane', 'oneway': 'yes'})
    # an island road that must be dropped (not connected)
    way(rc, [(200, 24.0, 78.0), (201, 24.01, 78.01)], {'highway': 'residential'})
    g = graphlib.build_graph(rc, log=lambda *a: None, urban_centers=[(23.0, 77.0, 3000.0), (23.0005, 77.125, 2000.0)])
    graphlib.write_graph(g, out)
    print('wrote', out, g['N'], 'nodes', g['E'], 'edges')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'test.graph')
