package com.bikeriding.mp.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** A computed route: geometry plus summary numbers. */
public final class Route {
    /** One stretch of the route on the same named road. */
    public static final class Step {
        public String name;   // e.g. "NH46 · Bhopal Bypass", may be null for unnamed roads
        public double meters;
        public int flags;
        public int roadClass;
        public int startPoint; // index into lats/lons where this step starts

        @Override
        public String toString() {
            return (name == null ? "(unnamed road)" : name) + " " + GeoUtil.formatDistance(meters);
        }
    }

    /** A turn the rider must make (for screen + sound/vibration alerts). */
    public static final class Turn {
        public int point;        // index into lats/lons of the junction
        public double angle;     // degrees, + = right, - = left
        public String onto;      // road name after the turn, may be null

        public boolean isRight() {
            return angle > 0;
        }

        public String arrow() {
            double a = Math.abs(angle);
            if (a > 135) return angle > 0 ? "↷" : "↶";
            return angle > 0 ? "↱" : "↰";
        }

        public String text() {
            double a = Math.abs(angle);
            String side = angle > 0 ? "right" : "left";
            String how = a > 135 ? "U-turn " : a > 100 ? "Sharp " : a < 50 ? "Slight " : "Turn ";
            return how + side;
        }
    }

    public final RouteOptions options;
    public final List<Turn> turns = new ArrayList<>();
    // junctions recorded while building: point index, whether there was a real choice, road name after it
    private final List<int[]> junctions = new ArrayList<>();
    private final List<String> junctionNames = new ArrayList<>();
    public double[] lats = new double[0], lons = new double[0];
    /** cumulative distance from start at each point, metres */
    public double[] cumDist = new double[0];
    public double distanceM, timeS;
    public double nhM, shM, tollM, unpavedM, expresswayM, curvyM, fordM;
    public final List<Step> steps = new ArrayList<>();

    Route(RouteOptions options) {
        this.options = options;
    }

    public int size() {
        return lats.length;
    }

    /** Main roads used, longest first, e.g. "NH46, SH18, MDR". */
    public String mainRoads(int max) {
        java.util.Map<String, Double> byName = new java.util.HashMap<>();
        for (Step s : steps) {
            if (s.name == null) continue;
            String key = s.name;
            int dot = key.indexOf(" · ");
            if (dot > 0) key = key.substring(0, dot);
            Double v = byName.get(key);
            byName.put(key, (v == null ? 0 : v) + s.meters);
        }
        List<java.util.Map.Entry<String, Double>> list = new ArrayList<>(byName.entrySet());
        Collections.sort(list, (a, b) -> Double.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(max, list.size()); i++) {
            if (list.get(i).getValue() < 500) break;
            if (sb.length() > 0) sb.append(", ");
            sb.append(list.get(i).getKey());
        }
        return sb.toString();
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(GeoUtil.formatDistance(distanceM)).append(" · ").append(GeoUtil.formatDuration(timeS));
        sb.append(String.format(Locale.US, "\nNH %.0f km · SH %.0f km", nhM / 1000, shM / 1000));
        if (tollM > 100) sb.append(String.format(Locale.US, " · toll %.0f km", tollM / 1000));
        if (unpavedM > 100) sb.append(String.format(Locale.US, " · kachcha %.1f km", unpavedM / 1000));
        String roads = mainRoads(4);
        if (!roads.isEmpty()) sb.append("\nvia ").append(roads);
        return sb.toString();
    }

    void addJunction(int pointIndex, boolean choice, String onto) {
        junctions.add(new int[]{pointIndex, choice ? 1 : 0});
        junctionNames.add(onto);
    }

    private int pointAtDistance(int from, double meters, int dir) {
        int i = from;
        while (i + dir >= 0 && i + dir < lats.length && Math.abs(cumDist[i + dir] - cumDist[from]) < meters) i += dir;
        if (i + dir >= 0 && i + dir < lats.length) i += dir;
        return i;
    }

    private static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return Math.toDegrees(Math.atan2(y, x));
    }

    /** Turns = junctions with a real choice where the direction changes by 35° or more. */
    void computeTurns() {
        turns.clear();
        for (int k = 0; k < junctions.size(); k++) {
            int idx = junctions.get(k)[0];
            boolean choice = junctions.get(k)[1] == 1;
            if (!choice || idx <= 0 || idx >= lats.length - 1) continue;
            int a = pointAtDistance(idx, 25, -1), b = pointAtDistance(idx, 25, +1);
            if (a == idx || b == idx) continue;
            double in = bearing(lats[a], lons[a], lats[idx], lons[idx]);
            double out = bearing(lats[idx], lons[idx], lats[b], lons[b]);
            double d = ((out - in + 540) % 360) - 180;
            if (Math.abs(d) < 35) continue;
            Turn t = new Turn();
            t.point = idx;
            t.angle = d;
            t.onto = junctionNames.get(k);
            // merge with a turn a few metres before (double junctions)
            if (!turns.isEmpty() && cumDist[idx] - cumDist[turns.get(turns.size() - 1).point] < 15) continue;
            turns.add(t);
        }
    }

    /** Next turn at or after distance-along-route `done` (metres), or null. */
    public Turn nextTurn(double done) {
        for (Turn t : turns) if (cumDist[t.point] >= done - 5) return t;
        return null;
    }

    /** Joins legs (start→via→…→end) into one route. */
    static Route join(RouteOptions o, List<Route> legs) {
        Route r = new Route(o);
        int n = 0;
        for (Route l : legs) n += l.size();
        r.lats = new double[n];
        r.lons = new double[n];
        r.cumDist = new double[n];
        int k = 0;
        double base = 0;
        for (Route l : legs) {
            for (Step s : l.steps) {
                s.startPoint += k;
                r.steps.add(s);
            }
            for (Turn t : l.turns) {
                t.point += k;
                r.turns.add(t);
            }
            for (int i = 0; i < l.size(); i++) {
                r.lats[k] = l.lats[i];
                r.lons[k] = l.lons[i];
                r.cumDist[k] = base + l.cumDist[i];
                k++;
            }
            base += l.distanceM;
            r.distanceM += l.distanceM;
            r.timeS += l.timeS;
            r.nhM += l.nhM;
            r.shM += l.shM;
            r.tollM += l.tollM;
            r.unpavedM += l.unpavedM;
            r.expresswayM += l.expresswayM;
            r.curvyM += l.curvyM;
            r.fordM += l.fordM;
        }
        return r;
    }
}
