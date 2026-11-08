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

    public final RouteOptions options;
    public double[] lats = new double[0], lons = new double[0];
    /** cumulative distance from start at each point, metres */
    public double[] cumDist = new double[0];
    public double distanceM, timeS;
    public double nhM, shM, tollM, unpavedM, expresswayM;
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
        }
        return r;
    }
}
