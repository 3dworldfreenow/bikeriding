package com.bikeriding.mp.routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Offline A* router over {@link Graph}. One query at a time (methods are synchronized). */
public final class Router {

    /** A point snapped onto the nearest road. */
    public static final class Snap {
        public int edge, seg;
        public double t;           // position inside segment seg (0..1)
        public double lat, lon;     // snapped position
        public double distA;        // metres along the edge from node A
        public double offRoadM;     // distance from the requested point to the road
    }

    public static final class NoRouteException extends Exception {
        public NoRouteException(String msg) {
            super(msg);
        }
    }

    private final Graph g;
    private final int[] edgeStamp;
    private int edgeGen = 0;
    private final double[] dist;
    private final int[] parent;
    private final int[] nodeStamp;
    private final boolean[] closed;
    private int nodeGen = 0;
    private int[] heapNode = new int[1024];
    private double[] heapKey = new double[1024];
    private int heapSize;

    private static final int START_FWD = -10, START_BWD = -11;

    public Router(Graph graph) {
        g = graph;
        edgeStamp = new int[g.edgeCount];
        dist = new double[g.nodeCount];
        parent = new int[g.nodeCount];
        nodeStamp = new int[g.nodeCount];
        closed = new boolean[g.nodeCount];
    }

    public Graph graph() {
        return g;
    }

    // ------------------------------------------------------------------ snapping

    /** Nearest road within maxMeters, or null. */
    public synchronized Snap snap(double lat, double lon, double maxMeters) {
        return snap(lat, lon, maxMeters, null);
    }

    /** Nearest road usable with these options (e.g. skips expressways for bikes). */
    public synchronized Snap snap(double lat, double lon, double maxMeters, RouteOptions o) {
        if (g.edgeCount == 0) return null;
        int r0 = (int) Math.floor((lat * 1e6 - g.gridMinLat) / g.cellSize);
        int c0 = (int) Math.floor((lon * 1e6 - g.gridMinLon) / g.cellSize);
        double cellM = g.cellSize / 1e6 * Math.PI / 180 * GeoUtil.R * Math.cos(Math.toRadians(lat));
        int maxRing = (int) Math.ceil(maxMeters / cellM) + 1;
        if (++edgeGen == Integer.MAX_VALUE) {
            Arrays.fill(edgeStamp, 0);
            edgeGen = 1;
        }
        Snap best = null;
        for (int ring = 0; ring <= maxRing; ring++) {
            if (best != null && best.offRoadM < (ring - 1) * cellM) break;
            for (int r = r0 - ring; r <= r0 + ring; r++) {
                if (r < 0 || r >= g.gridRows) continue;
                for (int c = c0 - ring; c <= c0 + ring; c++) {
                    if (c < 0 || c >= g.gridCols) continue;
                    if (Math.abs(r - r0) != ring && Math.abs(c - c0) != ring) continue; // ring border only
                    int cell = r * g.gridCols + c;
                    for (int i = g.gridStart.get(cell), z = g.gridStart.get(cell + 1); i < z; i++) {
                        int e = g.gridEdges.get(i);
                        if (edgeStamp[e] == edgeGen) continue;
                        edgeStamp[e] = edgeGen;
                        if (!g.allowed(e, 0) && !g.allowed(e, 1)) continue;
                        if (o != null && Double.isInfinite(cost(e, o))) continue;
                        best = snapEdge(e, lat, lon, best);
                    }
                }
            }
        }
        if (best == null || best.offRoadM > maxMeters) return null;
        // along-distance
        int s0 = g.shapeStart(best.edge);
        double along = 0;
        for (int i = s0; i < s0 + best.seg; i++) {
            along += GeoUtil.distance(g.shapeLat(i), g.shapeLon(i), g.shapeLat(i + 1), g.shapeLon(i + 1));
        }
        int i = s0 + best.seg;
        along += best.t * GeoUtil.distance(g.shapeLat(i), g.shapeLon(i), g.shapeLat(i + 1), g.shapeLon(i + 1));
        best.distA = Math.min(along, g.edgeLength(best.edge));
        return best;
    }

    private Snap snapEdge(int e, double lat, double lon, Snap best) {
        int s0 = g.shapeStart(e), s1 = g.shapeEnd(e);
        for (int i = s0; i < s1 - 1; i++) {
            double alat = g.shapeLat(i), alon = g.shapeLon(i), blat = g.shapeLat(i + 1), blon = g.shapeLon(i + 1);
            double[] p = GeoUtil.project(lat, lon, alat, alon, blat, blon);
            if (best == null || p[1] < best.offRoadM) {
                if (best == null) best = new Snap();
                best.edge = e;
                best.seg = i - s0;
                best.t = p[0];
                best.offRoadM = p[1];
                best.lat = alat + (blat - alat) * p[0];
                best.lon = alon + (blon - alon) * p[0];
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ costs

    /** Cost of the whole edge, or +Infinity if not allowed by the options. */
    private double cost(int e, RouteOptions o) {
        int f = g.edgeFlags(e);
        if (o.avoidExpressway && (f & Graph.F_EXPRESSWAY) != 0) return Double.POSITIVE_INFINITY;
        double len = g.edgeLength(e);
        double c = o.fastestLike() ? len / (g.edgeSpeedKmh(e) / 3.6) : len;
        int cls = g.edgeClass(e);
        boolean urban = (f & Graph.F_URBAN) != 0;
        double m = 1;
        if (o.avoidNH && (f & Graph.F_NH) != 0) m *= o.avoidFactor;
        if (o.avoidSH && (f & Graph.F_SH) != 0) m *= o.avoidFactor;
        if (o.avoidToll && (f & Graph.F_TOLL) != 0) m *= o.avoidFactor;
        if (o.avoidUnpaved && (f & Graph.F_UNPAVED) != 0) m *= o.avoidFactor;
        if (o.avoidFords && (f & Graph.F_FORD) != 0) m *= o.avoidFactor * 3;
        if (o.avoidMainRoads && (cls == Graph.C_TRUNK || cls == Graph.C_PRIMARY)) m *= 4;
        if (o.avoidSmallRoads && cls >= Graph.C_RESIDENTIAL) m *= 4;
        if (o.avoidCities && urban) m *= 3;
        switch (o.mode) {
            case RouteOptions.MODE_CURVY:
            case RouteOptions.MODE_EXTRA_CURVY: {
                boolean extra = o.mode == RouteOptions.MODE_EXTRA_CURVY;
                // like Kurviger: straight roads cost more, twisty roads cost less (relatively)
                double curvy = Math.min(1.0, g.edgeCurvDegPerKm(e) / 150.0);
                m *= 1 + (extra ? 2.0 : 0.8) * (1 - curvy);
                if (urban) m *= extra ? 2.5 : 1.5;
                if (cls <= Graph.C_TRUNK) m *= extra ? 1.8 : 1.3;
                if (cls >= Graph.C_SERVICE) m *= 1.5;
                break;
            }
            case RouteOptions.MODE_SHORTEST:
                if (cls == Graph.C_TRACK) m *= 1.5;
                else if (cls == Graph.C_SERVICE) m *= 1.2;
                break;
            default:
                break;
        }
        return c * m;
    }

    private double heuristic(int n, double tlat, double tlon, RouteOptions o) {
        double d = GeoUtil.distance(g.nodeLat(n), g.nodeLon(n), tlat, tlon) * 0.99;
        return o.fastestLike() ? d / (g.maxSpeedKmh / 3.6) : d;
    }

    // ------------------------------------------------------------------ routing

    /** Route through all points (start, optional vias, destination). */
    public synchronized Route route(double[] lats, double[] lons, RouteOptions o) throws NoRouteException {
        if (lats.length < 2) throw new IllegalArgumentException("need at least 2 points");
        List<Route> legs = new ArrayList<>();
        for (int i = 0; i + 1 < lats.length; i++) {
            Snap a = snap(lats[i], lons[i], 5000, o);
            if (a == null) throw new NoRouteException(i == 0 ? "No road near the start point" : "No road near via point " + i);
            Snap b = snap(lats[i + 1], lons[i + 1], 5000, o);
            if (b == null) throw new NoRouteException("No road near the destination");
            legs.add(leg(a, b, o));
        }
        return legs.size() == 1 ? legs.get(0) : Route.join(o, legs);
    }

    public synchronized Route route(double fromLat, double fromLon, double toLat, double toLon, RouteOptions o)
            throws NoRouteException {
        return route(new double[]{fromLat, toLat}, new double[]{fromLon, toLon}, o);
    }

    private void touch(int n) {
        if (nodeStamp[n] != nodeGen) {
            nodeStamp[n] = nodeGen;
            dist[n] = Double.POSITIVE_INFINITY;
            parent[n] = -1;
            closed[n] = false;
        }
    }

    private Route leg(Snap s, Snap t, RouteOptions o) throws NoRouteException {
        if (++nodeGen == Integer.MAX_VALUE) {
            Arrays.fill(nodeStamp, 0);
            nodeGen = 1;
        }
        heapSize = 0;
        int es = s.edge, et = t.edge;
        double Ls = Math.max(g.edgeLength(es), 0.01), Lt = Math.max(g.edgeLength(et), 0.01);
        double cs = cost(es, o), ct = cost(et, o);
        boolean sF = g.allowed(es, 0), sB = g.allowed(es, 1);
        boolean tF = g.allowed(et, 0), tB = g.allowed(et, 1);

        double best = Double.POSITIVE_INFINITY;
        int bestNode = -1;   // -1 = direct on same edge
        boolean direct = false;

        if (es == et) {
            if (sF && t.distA >= s.distA) {
                double c = cs * (t.distA - s.distA) / Ls;
                if (c < best) { best = c; direct = true; }
            }
            if (sB && t.distA <= s.distA) {
                double c = cs * (s.distA - t.distA) / Ls;
                if (c < best) { best = c; direct = true; }
            }
        }
        int sa = g.edgeA(es), sb = g.edgeB(es);
        if (sF && !Double.isInfinite(cs)) {
            touch(sb);
            double c = cs * (Ls - s.distA) / Ls;
            if (c < dist[sb]) { dist[sb] = c; parent[sb] = START_FWD; push(sb, c + heuristic(sb, t.lat, t.lon, o)); }
        }
        if (sB && !Double.isInfinite(cs)) {
            touch(sa);
            double c = cs * s.distA / Ls;
            if (c < dist[sa]) { dist[sa] = c; parent[sa] = START_BWD; push(sa, c + heuristic(sa, t.lat, t.lon, o)); }
        }
        // if start edge is avoided entirely (e.g. expressway) allow leaving it anyway
        if (Double.isInfinite(cs)) {
            double c0 = Ls / (g.edgeSpeedKmh(es) / 3.6);
            if (sF) { touch(sb); dist[sb] = c0; parent[sb] = START_FWD; push(sb, c0); }
            if (sB) { touch(sa); dist[sa] = c0; parent[sa] = START_BWD; push(sa, c0); }
        }
        int ta = g.edgeA(et), tb = g.edgeB(et);
        double ctUse = Double.isInfinite(ct) ? Lt / (g.edgeSpeedKmh(et) / 3.6) : ct;
        double extraA = tF ? ctUse * t.distA / Lt : Double.POSITIVE_INFINITY;        // reach A, drive A->point
        double extraB = tB ? ctUse * (Lt - t.distA) / Lt : Double.POSITIVE_INFINITY; // reach B, drive B->point

        while (heapSize > 0) {
            double f = heapKey[0];
            int n = pop();
            if (closed[n]) continue;
            if (f >= best) break;
            closed[n] = true;
            double dn = dist[n];
            if (n == ta && dn + extraA < best) { best = dn + extraA; bestNode = ta; direct = false; }
            if (n == tb && dn + extraB < best) { best = dn + extraB; bestNode = tb; direct = false; }
            for (int i = g.adjStart.get(n), z = g.adjStart.get(n + 1); i < z; i++) {
                int entry = g.adj.get(i);
                int e = entry >>> 1;
                double c = cost(e, o);
                if (Double.isInfinite(c)) continue;
                int m = (entry & 1) == 0 ? g.edgeB(e) : g.edgeA(e);
                touch(m);
                if (closed[m]) continue;
                double nd = dn + c;
                if (nd < dist[m]) {
                    dist[m] = nd;
                    parent[m] = entry;
                    push(m, nd + heuristic(m, t.lat, t.lon, o));
                }
            }
        }
        if (Double.isInfinite(best)) throw new NoRouteException("No route found between these points");
        return build(s, t, o, direct, bestNode);
    }

    // ------------------------------------------------------------------ result

    private static final class Builder {
        double[] lat = new double[256], lon = new double[256];
        int n;

        void add(double la, double lo) {
            if (n > 0 && lat[n - 1] == la && lon[n - 1] == lo) return;
            if (n == lat.length) {
                lat = Arrays.copyOf(lat, n * 2);
                lon = Arrays.copyOf(lon, n * 2);
            }
            lat[n] = la;
            lon[n] = lo;
            n++;
        }
    }

    private void addPiece(Route r, int startIdx, int e, double meters) {
        int f = g.edgeFlags(e);
        r.distanceM += meters;
        r.timeS += meters / (g.edgeSpeedKmh(e) / 3.6);
        if ((f & Graph.F_NH) != 0) r.nhM += meters;
        if ((f & Graph.F_SH) != 0) r.shM += meters;
        if ((f & Graph.F_TOLL) != 0) r.tollM += meters;
        if ((f & Graph.F_UNPAVED) != 0) r.unpavedM += meters;
        if ((f & Graph.F_EXPRESSWAY) != 0) r.expresswayM += meters;
        if ((f & Graph.F_FORD) != 0) r.fordM += meters;
        if (g.edgeCurvDegPerKm(e) >= 60) r.curvyM += meters;
        String name = g.edgeName(e);
        Route.Step last = r.steps.isEmpty() ? null : r.steps.get(r.steps.size() - 1);
        if (last != null && (name == null ? last.name == null : name.equals(last.name))) {
            last.meters += meters;
        } else {
            Route.Step st = new Route.Step();
            st.name = name;
            st.meters = meters;
            st.flags = f;
            st.roadClass = g.edgeClass(e);
            st.startPoint = Math.max(0, startIdx);
            r.steps.add(st);
        }
    }

    /** shape points of edge e strictly after segment index `seg` going toward B. */
    private void shapeForwardFrom(Builder b, int e, int seg) {
        int s0 = g.shapeStart(e), s1 = g.shapeEnd(e);
        for (int i = s0 + seg + 1; i < s1; i++) b.add(g.shapeLat(i), g.shapeLon(i));
    }

    private void shapeBackwardFrom(Builder b, int e, int seg) {
        int s0 = g.shapeStart(e);
        for (int i = s0 + seg; i >= s0; i--) b.add(g.shapeLat(i), g.shapeLon(i));
    }

    private Route build(Snap s, Snap t, RouteOptions o, boolean direct, int endNode) {
        Route r = new Route(o);
        Builder b = new Builder();
        b.add(s.lat, s.lon);
        int es = s.edge, et = t.edge;
        if (direct) {
            int st0 = b.n - 1;
            int s0 = g.shapeStart(es);
            if (t.distA >= s.distA) {
                for (int i = s0 + s.seg + 1; i <= s0 + t.seg; i++) b.add(g.shapeLat(i), g.shapeLon(i));
            } else {
                for (int i = s0 + s.seg; i > s0 + t.seg; i--) b.add(g.shapeLat(i), g.shapeLon(i));
            }
            addPiece(r, st0, es, Math.abs(t.distA - s.distA));
        } else {
            // collect path entries from end back to start
            int[] path = new int[64];
            int k = 0;
            int n = endNode;
            int startKind;
            while (true) {
                int p = parent[n];
                if (p == START_FWD || p == START_BWD) { startKind = p; break; }
                if (k == path.length) path = Arrays.copyOf(path, k * 2);
                path[k++] = p;
                int e = p >>> 1;
                n = (p & 1) == 0 ? g.edgeA(e) : g.edgeB(e);
            }
            double Ls = g.edgeLength(es);
            int st1 = b.n - 1;
            if (startKind == START_FWD) {
                shapeForwardFrom(b, es, s.seg);
                addPiece(r, st1, es, Ls - s.distA);
            } else {
                shapeBackwardFrom(b, es, s.seg);
                addPiece(r, st1, es, s.distA);
            }
            for (int i = k - 1; i >= 0; i--) {
                int p = path[i];
                int e = p >>> 1;
                int s0 = g.shapeStart(e), s1 = g.shapeEnd(e);
                int stE = b.n - 1;
                if ((p & 1) == 0) for (int j = s0; j < s1; j++) b.add(g.shapeLat(j), g.shapeLon(j));
                else for (int j = s1 - 1; j >= s0; j--) b.add(g.shapeLat(j), g.shapeLon(j));
                addPiece(r, stE, e, g.edgeLength(e));
            }
            double Lt = g.edgeLength(et);
            int s0 = g.shapeStart(et);
            int stT = b.n - 1;
            if (endNode == g.edgeA(et) && g.allowed(et, 0)) {
                for (int i = s0; i <= s0 + t.seg; i++) b.add(g.shapeLat(i), g.shapeLon(i));
                addPiece(r, stT, et, t.distA);
            } else {
                for (int i = g.shapeEnd(et) - 1; i > s0 + t.seg; i--) b.add(g.shapeLat(i), g.shapeLon(i));
                addPiece(r, stT, et, Lt - t.distA);
            }
        }
        b.add(t.lat, t.lon);
        r.lats = Arrays.copyOf(b.lat, b.n);
        r.lons = Arrays.copyOf(b.lon, b.n);
        r.cumDist = new double[b.n];
        for (int i = 1; i < b.n; i++) {
            r.cumDist[i] = r.cumDist[i - 1] + GeoUtil.distance(r.lats[i - 1], r.lons[i - 1], r.lats[i], r.lons[i]);
        }
        // remove zero-length steps
        for (int i = r.steps.size() - 1; i >= 0; i--) if (r.steps.get(i).meters < 1 && r.steps.size() > 1) r.steps.remove(i);
        return r;
    }

    // ------------------------------------------------------------------ binary heap

    private void push(int node, double key) {
        if (heapSize == heapNode.length) {
            heapNode = Arrays.copyOf(heapNode, heapSize * 2);
            heapKey = Arrays.copyOf(heapKey, heapSize * 2);
        }
        int i = heapSize++;
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (heapKey[p] <= key) break;
            heapNode[i] = heapNode[p];
            heapKey[i] = heapKey[p];
            i = p;
        }
        heapNode[i] = node;
        heapKey[i] = key;
    }

    private int pop() {
        int top = heapNode[0];
        int lastN = heapNode[--heapSize];
        double lastK = heapKey[heapSize];
        int i = 0;
        while (true) {
            int c = 2 * i + 1;
            if (c >= heapSize) break;
            if (c + 1 < heapSize && heapKey[c + 1] < heapKey[c]) c++;
            if (heapKey[c] >= lastK) break;
            heapNode[i] = heapNode[c];
            heapKey[i] = heapKey[c];
            i = c;
        }
        if (heapSize > 0) {
            heapNode[i] = lastN;
            heapKey[i] = lastK;
        }
        return top;
    }
}
