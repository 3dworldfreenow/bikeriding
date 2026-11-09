package com.bikeriding.mp;

import com.bikeriding.mp.routing.GeoUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Offline search over places.tsv (category, name, Hindi name, lat, lon, extra). */
public final class PlacesIndex {

    public static final class Place {
        public final String category, name, nameHi, extra;
        public final double lat, lon;
        double score;

        Place(String category, String name, String nameHi, double lat, double lon, String extra) {
            this.category = category;
            this.name = name;
            this.nameHi = nameHi;
            this.lat = lat;
            this.lon = lon;
            this.extra = extra;
        }

        /** English name (Hindi only if the place has no English name at all). */
        public String title() {
            return name.isEmpty() ? nameHi : name;
        }

        public String label() {
            return categoryLabel(category) + (extra.isEmpty() ? "" : " · " + extra);
        }
    }

    /** Quick categories shown as buttons in search. */
    public static final String[][] QUICK = {
            {"fuel", "⛽ Petrol"}, {"hospital", "🏥 Hospital"}, {"repair", "🔧 Bike repair"},
            {"police", "🚓 Police"}, {"atm", "🏧 ATM/Bank"}, {"food", "🍽 Food"},
            {"hotel", "🏨 Stay"}, {"sight", "📷 Sights"}, {"fort", "🏰 Forts"}, {"water", "💧 Waterfalls"},
    };

    public static String categoryLabel(String c) {
        switch (c) {
            case "city": return "City";
            case "town": return "Town";
            case "village": return "Village";
            case "hamlet": return "Hamlet";
            case "suburb": case "neighbourhood": return "Area";
            case "locality": return "Locality";
            case "fuel": return "Petrol pump";
            case "hospital": return "Hospital / clinic";
            case "police": return "Police";
            case "atm": return "ATM / bank";
            case "food": return "Food";
            case "hotel": return "Hotel / stay";
            case "sight": return "Sight";
            case "repair": return "Repair shop";
            case "temple": return "Temple / worship";
            case "fort": return "Fort / monument";
            case "water": return "Waterfall";
            default: return c;
        }
    }

    private static int rank(String c) {
        switch (c) {
            case "city": return 0;
            case "town": return 1;
            case "suburb": return 2;
            case "village": return 3;
            case "fort": case "sight": case "water": return 3;
            case "neighbourhood": case "locality": return 4;
            case "hamlet": return 5;
            default: return 6;
        }
    }

    private final List<Place> places = new ArrayList<>();
    private String[] lowerNames = new String[0];

    public static PlacesIndex load(InputStream in) throws IOException {
        PlacesIndex idx = new PlacesIndex();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length < 5) continue;
                try {
                    idx.places.add(new Place(p[0].intern(), p[1], p[2], Double.parseDouble(p[3]),
                            Double.parseDouble(p[4]), p.length > 5 ? p[5] : ""));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        idx.lowerNames = new String[idx.places.size()];
        for (int i = 0; i < idx.lowerNames.length; i++) {
            Place pl = idx.places.get(i);
            idx.lowerNames[i] = pl.name.toLowerCase(Locale.ROOT);
        }
        return idx;
    }

    public int size() {
        return places.size();
    }

    /** Text search; results near (lat, lon) are preferred. */
    public List<Place> search(String query, double lat, double lon, int limit) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Place> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        for (int i = 0; i < places.size(); i++) {
            Place p = places.get(i);
            String n = lowerNames[i];
            int m;
            if (n.equals(q) || p.nameHi.equals(query.trim())) m = 0;
            else if (n.startsWith(q) || p.nameHi.startsWith(query.trim())) m = 1;
            else if (n.contains(" " + q)) m = 2;
            else if (n.contains(q) || (!p.nameHi.isEmpty() && p.nameHi.contains(query.trim()))) m = 3;
            else continue;
            double km = Double.isNaN(lat) ? 0 : GeoUtil.distance(lat, lon, p.lat, p.lon) / 1000;
            p.score = m * 100 + rank(p.category) * 25 + Math.min(km, 400) / 8;
            out.add(p);
        }
        Collections.sort(out, (a, b) -> Double.compare(a.score, b.score));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** Nearest places of a category. */
    public List<Place> nearest(String category, double lat, double lon, int limit) {
        List<Place> out = new ArrayList<>();
        for (Place p : places) {
            if (!p.category.equals(category)) continue;
            p.score = GeoUtil.distance(lat, lon, p.lat, p.lon);
            out.add(p);
        }
        Collections.sort(out, (a, b) -> Double.compare(a.score, b.score));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }
}
