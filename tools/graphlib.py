"""
Builds the offline routing graph file (mp.graph) used by the Android app.

This module has no OSM dependency: it receives already-parsed roads
(node ids + coordinates + attributes) and writes the binary graph.
`build_graph.py` feeds it from an .osm.pbf file using pyosmium.

Binary format (all little-endian, every section 4-byte aligned)
----------------------------------------------------------------
header: 16 x int32
  [0] magic 'MPRG'   [1] version   [2] N nodes   [3] E edges
  [4] S shape pts    [5] A adjacency entries
  [6] grid rows      [7] grid cols [8] grid min lat e6 [9] grid min lon e6
  [10] cell size e6  [11] G grid entries
  [12] name count    [13] name bytes length   [14..15] reserved
sections:
  nodeLat int[N], nodeLon int[N]                (degrees * 1e6)
  adjStart int[N+1], adj int[A]                 (adj = edge*2 + dir; dir 0 = A->B, 1 = B->A)
  edgeA int[E], edgeB int[E], edgeLen float[E]  (metres)
  edgeName int[E] (-1 = none), edgeShapeStart int[E+1]
  edgeClass byte[E], edgeFlags byte[E], edgeSpeed byte[E]   (each padded to 4)
  shapeLat int[S], shapeLon int[S]              (full polyline of each edge, A..B inclusive)
  gridStart int[rows*cols+1], gridEdges int[G]
  nameOffsets int[nameCount+1], nameBytes byte[nameBytes] (UTF-8, padded)
"""
import re
import struct
import numpy as np

MAGIC = 0x4752504D  # bytes "MPRG" little-endian
VERSION = 1

# road classes (must match RoadClass in the app)
C_MOTORWAY, C_TRUNK, C_PRIMARY, C_SECONDARY, C_TERTIARY, C_UNCLASSIFIED, \
    C_RESIDENTIAL, C_SERVICE, C_TRACK = range(9)

# flags (must match Graph.F_* in the app)
F_NH = 1
F_SH = 2
F_TOLL = 4
F_UNPAVED = 8
F_EXPRESSWAY = 16
F_LINK = 32
F_FERRY = 64

HIGHWAY_CLASS = {
    'motorway': (C_MOTORWAY, 80), 'motorway_link': (C_MOTORWAY, 50),
    'trunk': (C_TRUNK, 60), 'trunk_link': (C_TRUNK, 40),
    'primary': (C_PRIMARY, 50), 'primary_link': (C_PRIMARY, 35),
    'secondary': (C_SECONDARY, 45), 'secondary_link': (C_SECONDARY, 30),
    'tertiary': (C_TERTIARY, 40), 'tertiary_link': (C_TERTIARY, 30),
    'unclassified': (C_UNCLASSIFIED, 30), 'road': (C_UNCLASSIFIED, 25),
    'residential': (C_RESIDENTIAL, 25), 'living_street': (C_RESIDENTIAL, 12),
    'service': (C_SERVICE, 15), 'track': (C_TRACK, 15),
}
UNPAVED = {'unpaved', 'dirt', 'gravel', 'ground', 'earth', 'sand', 'mud', 'compacted',
           'fine_gravel', 'grass', 'pebblestone', 'rock', 'soil', 'clay'}
NO = {'no', 'private', 'agricultural', 'forestry', 'delivery', 'customers', 'permit'}
NH_RE = re.compile(r'(^|[^A-Z])N\.?\s?H\.?[\s-]*\d', re.I)
SH_RE = re.compile(r'(^|[^A-Z])(MP\s?)?S\.?\s?H\.?[\s-]*\d', re.I)


def classify_way(tags):
    """tags: dict-like. Returns (cls, flags, speed_kmh, oneway, label) or None if not routable
    for a motorcycle. oneway: 0 both ways, 1 forward only, -1 backward only."""
    hw = tags.get('highway')
    if hw not in HIGHWAY_CLASS:
        return None
    if tags.get('area') == 'yes':
        return None
    if hw == 'service' and tags.get('service') in ('parking_aisle', 'driveway', 'drive-through', 'emergency_access'):
        return None
    if hw == 'track' and tags.get('tracktype') in ('grade4', 'grade5'):
        return None
    moto = tags.get('motorcycle') or tags.get('motor_vehicle') or tags.get('vehicle') or tags.get('access')
    if moto in NO:
        return None
    cls, speed = HIGHWAY_CLASS[hw]
    flags = 0
    ref = (tags.get('ref') or '') + ';' + (tags.get('nat_ref') or '')
    if NH_RE.search(ref):
        flags |= F_NH
    elif SH_RE.search(ref):
        flags |= F_SH
    elif cls == C_TRUNK:
        flags |= F_NH          # in India highway=trunk is used for National Highways
    if cls == C_MOTORWAY or 'expressway' in (tags.get('name') or '').lower() or tags.get('expressway') == 'yes':
        flags |= F_EXPRESSWAY
    if hw.endswith('_link'):
        flags |= F_LINK
    if tags.get('toll') == 'yes':
        flags |= F_TOLL
    surface = tags.get('surface')
    if hw == 'track' or surface in UNPAVED:
        flags |= F_UNPAVED
        speed = min(speed, 20)
    ms = tags.get('maxspeed') or ''
    m = re.match(r'^\s*(\d+)', ms)
    if m:
        v = int(m.group(1))
        if 'mph' in ms:
            v = int(v * 1.609)
        if 5 <= v < speed:
            speed = v
    ow = tags.get('oneway')
    oneway = 0
    if ow in ('yes', '1', 'true'):
        oneway = 1
    elif ow == '-1' or ow == 'reverse':
        oneway = -1
    elif ow not in ('no', 'false', '0') and (hw == 'motorway' or tags.get('junction') in ('roundabout', 'circular')):
        oneway = 1
    if tags.get('oneway:motorcycle') == 'no':
        oneway = 0
    label = (tags.get('ref') or '').strip()
    name = (tags.get('name') or '').strip()
    if label and name and name.upper() != label.upper():
        label = label + ' · ' + name
    elif not label:
        label = name
    return cls, flags, max(5, min(speed, 120)), oneway, label


class RoadCollector:
    """Accumulates roads in compact arrays."""

    def __init__(self):
        from array import array
        self.ids = array('q')
        self.lat = array('i')
        self.lon = array('i')
        self.way_len = array('i')
        self.cls = array('b')
        self.flags = array('B')
        self.speed = array('B')
        self.oneway = array('b')
        self.label = array('i')
        self.labels = {}
        self.label_list = []

    def add(self, node_ids, lats_e6, lons_e6, info):
        if len(node_ids) < 2:
            return
        cls, flags, speed, oneway, label = info
        self.ids.extend(node_ids)
        self.lat.extend(lats_e6)
        self.lon.extend(lons_e6)
        self.way_len.append(len(node_ids))
        self.cls.append(cls)
        self.flags.append(flags)
        self.speed.append(speed)
        self.oneway.append(oneway)
        if label:
            idx = self.labels.get(label)
            if idx is None:
                idx = len(self.label_list)
                self.labels[label] = idx
                self.label_list.append(label)
            self.label.append(idx)
        else:
            self.label.append(-1)


def _haversine(lat1, lon1, lat2, lon2):
    r = 6371008.8
    p1 = np.radians(lat1)
    p2 = np.radians(lat2)
    dp = p2 - p1
    dl = np.radians(lon2 - lon1)
    a = np.sin(dp / 2) ** 2 + np.cos(p1) * np.cos(p2) * np.sin(dl / 2) ** 2
    return 2 * r * np.arcsin(np.sqrt(np.minimum(1.0, a)))


def _largest_component(n, a, b):
    """Union-find (vectorised pointer jumping) → boolean mask of nodes in the largest component."""
    parent = np.arange(n, dtype=np.int64)
    a = a.astype(np.int64)
    b = b.astype(np.int64)
    while True:
        pa = parent[a]
        pb = parent[b]
        lo = np.minimum(pa, pb)
        changed = (pa != pb)
        if not changed.any():
            break
        # hook larger root onto smaller
        np.minimum.at(parent, pa, lo)
        np.minimum.at(parent, pb, lo)
        # compress
        while True:
            pp = parent[parent]
            if np.array_equal(pp, parent):
                break
            parent = pp
    counts = np.bincount(parent, minlength=n)
    biggest = int(np.argmax(counts))
    return parent == biggest


def build_graph(rc, cell_deg=0.01, keep_largest_component=True, log=print):
    ids = np.frombuffer(rc.ids, dtype=np.int64)
    lat = np.frombuffer(rc.lat, dtype=np.int32)
    lon = np.frombuffer(rc.lon, dtype=np.int32)
    wlen = np.frombuffer(rc.way_len, dtype=np.int32).astype(np.int64)
    W = len(wlen)
    if W == 0:
        raise SystemExit('no roads found')
    wstart = np.concatenate(([0], np.cumsum(wlen)[:-1]))
    wend = wstart + wlen - 1
    way_of = np.repeat(np.arange(W, dtype=np.int64), wlen)
    log(f'roads: {W} ways, {len(ids)} node refs')

    uniq, counts = np.unique(ids, return_counts=True)
    jids = np.union1d(uniq[counts >= 2], np.concatenate((ids[wstart], ids[wend])))
    is_j = np.zeros(len(ids), dtype=bool)
    pos = np.searchsorted(jids, ids)
    pos_c = np.minimum(pos, len(jids) - 1)
    is_j = jids[pos_c] == ids
    node_of = np.where(is_j, pos_c, -1)
    N0 = len(jids)
    log(f'junction nodes: {N0}')

    # cumulative length along the global node sequence
    seg = _haversine(lat[:-1] / 1e6, lon[:-1] / 1e6, lat[1:] / 1e6, lon[1:] / 1e6)
    cum = np.concatenate(([0.0], np.cumsum(seg)))

    P = np.nonzero(is_j)[0]
    same = way_of[P[:-1]] == way_of[P[1:]]
    ag = P[:-1][same]
    bg = P[1:][same]
    A = node_of[ag]
    B = node_of[bg]
    keep = A != B
    ag, bg, A, B = ag[keep], bg[keep], A[keep], B[keep]
    w = way_of[ag]
    elen = (cum[bg] - cum[ag]).astype(np.float32)

    node_lat = np.zeros(N0, dtype=np.int32)
    node_lon = np.zeros(N0, dtype=np.int32)
    node_lat[node_of[P]] = lat[P]
    node_lon[node_of[P]] = lon[P]

    if keep_largest_component and len(A):
        mask = _largest_component(N0, A, B)
        ek = mask[A]
        log(f'largest component: {int(mask.sum())} of {N0} nodes')
        ag, bg, A, B, w, elen = ag[ek], bg[ek], A[ek], B[ek], w[ek], elen[ek]
        remap = np.full(N0, -1, dtype=np.int64)
        remap[mask] = np.arange(int(mask.sum()))
        A = remap[A]
        B = remap[B]
        node_lat = node_lat[mask]
        node_lon = node_lon[mask]

    N = len(node_lat)
    E = len(A)
    log(f'graph: {N} nodes, {E} edges')

    ecls = np.frombuffer(rc.cls, dtype=np.int8)[w]
    eflags = np.frombuffer(rc.flags, dtype=np.uint8)[w]
    espeed = np.frombuffer(rc.speed, dtype=np.uint8)[w]
    eoneway = np.frombuffer(rc.oneway, dtype=np.int8)[w]
    ename = np.frombuffer(rc.label, dtype=np.int32)[w]

    # shapes: full polyline for each edge
    slen = (bg - ag + 1)
    sstart = np.concatenate(([0], np.cumsum(slen)))
    S = int(sstart[-1])
    sidx = np.repeat(ag - sstart[:-1], slen) + np.arange(S)
    shape_lat = lat[sidx]
    shape_lon = lon[sidx]

    # adjacency
    fwd = eoneway != -1
    bwd = eoneway != 1
    eidx = np.arange(E, dtype=np.int64)
    src = np.concatenate((A[fwd], B[bwd]))
    ent = np.concatenate((eidx[fwd] * 2, eidx[bwd] * 2 + 1))
    order = np.argsort(src, kind='stable')
    src = src[order]
    adj = ent[order].astype(np.int32)
    adj_start = np.concatenate(([0], np.cumsum(np.bincount(src, minlength=N)))).astype(np.int32)

    # spatial grid over edge segments
    min_lat = int(np.floor(shape_lat.min() / 1e6 / cell_deg) * cell_deg * 1e6)
    min_lon = int(np.floor(shape_lon.min() / 1e6 / cell_deg) * cell_deg * 1e6)
    cell = int(round(cell_deg * 1e6))
    rows = int((shape_lat.max() - min_lat) // cell + 1)
    cols = int((shape_lon.max() - min_lon) // cell + 1)
    seg_edge = np.repeat(eidx, slen - 1)
    # segment i goes from shape point i to i+1, valid when i+1 is not an edge start
    starts_mask = np.zeros(S, dtype=bool)
    starts_mask[sstart[:-1]] = True
    seg_from = np.nonzero(~starts_mask[1:])[0]
    r1 = (shape_lat[seg_from] - min_lat) // cell
    c1 = (shape_lon[seg_from] - min_lon) // cell
    r2 = (shape_lat[seg_from + 1] - min_lat) // cell
    c2 = (shape_lon[seg_from + 1] - min_lon) // cell
    rlo, rhi = np.minimum(r1, r2), np.maximum(r1, r2)
    clo, chi = np.minimum(c1, c2), np.maximum(c1, c2)
    cells = [rlo.astype(np.int64) * cols + clo]
    owners = [seg_edge]
    big = np.nonzero((rhi > rlo) | (chi > clo))[0]
    extra_c, extra_e = [], []
    for i in big:
        e = seg_edge[i]
        for r in range(rlo[i], rhi[i] + 1):
            for c in range(clo[i], chi[i] + 1):
                extra_c.append(r * cols + c)
                extra_e.append(e)
    if extra_c:
        cells.append(np.array(extra_c, dtype=np.int64))
        owners.append(np.array(extra_e, dtype=np.int64))
    key = np.unique(np.concatenate(cells) * (E + 1) + np.concatenate(owners))
    gcell = key // (E + 1)
    gedge = (key % (E + 1)).astype(np.int32)
    grid_start = np.concatenate(([0], np.cumsum(np.bincount(gcell, minlength=rows * cols)))).astype(np.int32)
    log(f'grid: {rows}x{cols} cells, {len(gedge)} entries')

    return dict(N=N, E=E, S=S, node_lat=node_lat, node_lon=node_lon, adj_start=adj_start, adj=adj,
                A=A.astype(np.int32), B=B.astype(np.int32), elen=elen, ename=ename.astype(np.int32),
                sstart=sstart.astype(np.int32), ecls=ecls, eflags=eflags, espeed=espeed,
                shape_lat=shape_lat, shape_lon=shape_lon, rows=rows, cols=cols, min_lat=min_lat,
                min_lon=min_lon, cell=cell, grid_start=grid_start, grid_edges=gedge,
                names=rc.label_list)


def _pad4(b):
    return b + b'\0' * ((4 - len(b) % 4) % 4)


def write_graph(g, path):
    name_bytes = [s.encode('utf-8') for s in g['names']]
    offs = np.concatenate(([0], np.cumsum([len(b) for b in name_bytes]))).astype(np.int32)
    blob = b''.join(name_bytes)
    header = struct.pack('<16i', MAGIC, VERSION, g['N'], g['E'], g['S'], len(g['adj']),
                         g['rows'], g['cols'], g['min_lat'], g['min_lon'], g['cell'],
                         len(g['grid_edges']), len(name_bytes), len(blob), 0, 0)
    i4 = lambda a: np.ascontiguousarray(a, dtype='<i4').tobytes()
    with open(path, 'wb') as f:
        f.write(header)
        f.write(i4(g['node_lat']))
        f.write(i4(g['node_lon']))
        f.write(i4(g['adj_start']))
        f.write(i4(g['adj']))
        f.write(i4(g['A']))
        f.write(i4(g['B']))
        f.write(np.ascontiguousarray(g['elen'], dtype='<f4').tobytes())
        f.write(i4(g['ename']))
        f.write(i4(g['sstart']))
        f.write(_pad4(np.ascontiguousarray(g['ecls'], dtype=np.int8).tobytes()))
        f.write(_pad4(np.ascontiguousarray(g['eflags'], dtype=np.uint8).tobytes()))
        f.write(_pad4(np.ascontiguousarray(g['espeed'], dtype=np.uint8).tobytes()))
        f.write(i4(g['shape_lat']))
        f.write(i4(g['shape_lon']))
        f.write(i4(g['grid_start']))
        f.write(i4(g['grid_edges']))
        f.write(i4(offs))
        f.write(_pad4(blob))
