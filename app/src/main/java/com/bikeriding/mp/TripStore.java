package com.bikeriding.mp;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import com.bikeriding.mp.routing.Route;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Saved trip plans (start, stops, destination, route options) in files/trips.json. */
public final class TripStore {
    private static final String TAG = "TripStore";

    public static final class Trip {
        public String name;
        public long created;
        /** null = start from my current location */
        public double[] start;
        public final List<double[]> vias = new ArrayList<>();
        public double[] dest;
        public String options = "";
        public double distanceM, timeS;

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("created", created);
            if (start != null) o.put("start", pt(start));
            JSONArray v = new JSONArray();
            for (double[] p : vias) v.put(pt(p));
            o.put("vias", v);
            o.put("dest", pt(dest));
            o.put("options", options);
            o.put("distanceM", distanceM);
            o.put("timeS", timeS);
            return o;
        }

        static Trip fromJson(JSONObject o) throws JSONException {
            Trip t = new Trip();
            t.name = o.optString("name", "Trip");
            t.created = o.optLong("created");
            if (o.has("start")) t.start = pt(o.getJSONArray("start"));
            JSONArray v = o.optJSONArray("vias");
            if (v != null) for (int i = 0; i < v.length(); i++) t.vias.add(pt(v.getJSONArray(i)));
            t.dest = pt(o.getJSONArray("dest"));
            t.options = o.optString("options", "");
            t.distanceM = o.optDouble("distanceM", 0);
            t.timeS = o.optDouble("timeS", 0);
            return t;
        }

        private static JSONArray pt(double[] p) throws JSONException {
            return new JSONArray().put(p[0]).put(p[1]);
        }

        private static double[] pt(JSONArray a) throws JSONException {
            return new double[]{a.getDouble(0), a.getDouble(1)};
        }
    }

    private final File file;
    private final List<Trip> trips = new ArrayList<>();

    public TripStore(Context c) {
        file = new File(c.getFilesDir(), "trips.json");
        load();
    }

    public List<Trip> all() {
        return trips;
    }

    private void load() {
        trips.clear();
        String s = readAll();
        if (s == null) return;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) trips.add(Trip.fromJson(a.getJSONObject(i)));
        } catch (JSONException e) {
            Log.w(TAG, "bad trips.json", e);
        }
    }

    public void save() {
        try {
            JSONArray a = new JSONArray();
            for (Trip t : trips) a.put(t.toJson());
            File tmp = new File(file.getPath() + ".tmp");
            try (OutputStream os = new FileOutputStream(tmp)) {
                os.write(a.toString(1).getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file)) Log.w(TAG, "rename failed");
        } catch (Exception e) {
            Log.w(TAG, "save failed", e);
        }
    }

    public void add(Trip t) {
        trips.add(0, t);
        save();
    }

    public void remove(Trip t) {
        trips.remove(t);
        save();
    }

    private String readAll() {
        if (!file.exists()) return null;
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] b = new byte[(int) file.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return new String(b, 0, off, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ GPX export

    public static String routeGpx(String name, Route r) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<gpx version=\"1.1\" creator=\"Bike Riding MP\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
                .append("<rte><name>").append(xml(name)).append("</name>\n");
        // route points every ~200 m are enough for other apps
        double last = -1e9;
        for (int i = 0; i < r.size(); i++) {
            if (i != 0 && i != r.size() - 1 && r.cumDist[i] - last < 200) continue;
            last = r.cumDist[i];
            sb.append(String.format(Locale.US, "<rtept lat=\"%.6f\" lon=\"%.6f\"/>\n", r.lats[i], r.lons[i]));
        }
        sb.append("</rte>\n<trk><name>").append(xml(name)).append("</name><trkseg>\n");
        for (int i = 0; i < r.size(); i++) {
            sb.append(String.format(Locale.US, "<trkpt lat=\"%.6f\" lon=\"%.6f\"/>\n", r.lats[i], r.lons[i]));
        }
        sb.append("</trkseg></trk>\n</gpx>\n");
        return sb.toString();
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Saves text to Download/BikeRidingMP/fileName. Returns a user-visible location or null. */
    public static String saveToDownloads(Context c, String fileName, String content) {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                v.put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BikeRidingMP");
                Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return null;
                try (OutputStream os = c.getContentResolver().openOutputStream(uri)) {
                    if (os == null) return null;
                    os.write(data);
                }
                return "Download/BikeRidingMP/" + fileName;
            }
            File dir = new File(c.getExternalFilesDir(null), "routes");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File f = new File(dir, fileName);
            try (OutputStream os = new FileOutputStream(f)) {
                os.write(data);
            }
            return f.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "export failed", e);
            return null;
        }
    }
}
