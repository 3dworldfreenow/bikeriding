package com.bikeriding.mp;

import android.content.ContentValues;
import android.content.Context;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import com.bikeriding.mp.routing.GeoUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records the ride as GPX. Singleton shared by TrackService and MainActivity. */
public final class TrackRecorder {
    private static final String TAG = "TrackRecorder";
    private static final TrackRecorder INSTANCE = new TrackRecorder();

    public interface Listener {
        void onTrackChanged();
    }

    public static TrackRecorder get() {
        return INSTANCE;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<double[]> points = new ArrayList<>(); // lat, lon
    private boolean recording;
    private long startMs, movingMs, lastFixMs;
    private double distanceM, maxSpeedKmh;
    private Location last;
    private Writer gpx;
    private File file;
    private final SimpleDateFormat iso;

    private TrackRecorder() {
        iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public synchronized boolean isRecording() {
        return recording;
    }

    public synchronized List<double[]> points() {
        return new ArrayList<>(points);
    }

    public synchronized double distanceM() {
        return distanceM;
    }

    public synchronized double maxSpeedKmh() {
        return maxSpeedKmh;
    }

    public synchronized long elapsedMs() {
        return recording ? System.currentTimeMillis() - startMs : (lastFixMs - startMs);
    }

    public synchronized long movingMs() {
        return movingMs;
    }

    public synchronized File file() {
        return file;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyListeners() {
        main.post(() -> {
            for (Listener l : listeners) l.onTrackChanged();
        });
    }

    public static File tracksDir(Context c) {
        File d = new File(c.getExternalFilesDir(null), "tracks");
        if (!d.exists() && !d.mkdirs()) Log.w(TAG, "cannot create " + d);
        return d;
    }

    public synchronized void start(Context c) throws IOException {
        if (recording) return;
        points.clear();
        distanceM = 0;
        maxSpeedKmh = 0;
        movingMs = 0;
        last = null;
        startMs = lastFixMs = System.currentTimeMillis();
        String name = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date());
        file = new File(tracksDir(c), "ride_" + name + ".gpx");
        gpx = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
        gpx.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<gpx version=\"1.1\" creator=\"Bike Riding MP\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
                + "<trk><name>Ride " + name + "</name><trkseg>\n");
        gpx.flush();
        recording = true;
        notifyListeners();
    }

    public synchronized void onLocation(Location l) {
        if (!recording) return;
        if (l.hasAccuracy() && l.getAccuracy() > 35) return;
        if (last != null) {
            double d = GeoUtil.distance(last.getLatitude(), last.getLongitude(), l.getLatitude(), l.getLongitude());
            if (d < 5) return;
            long dt = l.getTime() - last.getTime();
            if (dt > 0 && dt < 60000 && d / (dt / 1000.0) > 0.8) movingMs += dt;
            distanceM += d;
        }
        if (l.hasSpeed()) maxSpeedKmh = Math.max(maxSpeedKmh, l.getSpeed() * 3.6);
        last = l;
        lastFixMs = System.currentTimeMillis();
        points.add(new double[]{l.getLatitude(), l.getLongitude()});
        try {
            gpx.write(String.format(Locale.US, "<trkpt lat=\"%.6f\" lon=\"%.6f\">%s<time>%s</time></trkpt>\n",
                    l.getLatitude(), l.getLongitude(),
                    l.hasAltitude() ? String.format(Locale.US, "<ele>%.1f</ele>", l.getAltitude()) : "",
                    iso.format(new Date(l.getTime()))));
            if (points.size() % 10 == 0) gpx.flush();
        } catch (IOException e) {
            Log.w(TAG, "write failed", e);
        }
        notifyListeners();
    }

    /** Stops and closes the GPX. Returns a user-visible location of the saved file. */
    public synchronized String stop(Context c) {
        if (!recording) return null;
        recording = false;
        try {
            gpx.write("</trkseg></trk>\n</gpx>\n");
            gpx.close();
        } catch (IOException e) {
            Log.w(TAG, "close failed", e);
        }
        gpx = null;
        notifyListeners();
        String where = file.getAbsolutePath();
        String exported = exportToDownloads(c, file);
        return exported != null ? exported : where;
    }

    /** Copies the GPX to Downloads/BikeRidingMP so it is easy to find and share (Android 10+). */
    private static String exportToDownloads(Context c, File f) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, f.getName());
            v.put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml");
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BikeRidingMP");
            Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return null;
            try (InputStream in = new FileInputStream(f); OutputStream os = c.getContentResolver().openOutputStream(uri)) {
                if (os == null) return null;
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            return "Download/BikeRidingMP/" + f.getName();
        } catch (Exception e) {
            Log.w(TAG, "export failed", e);
            return null;
        }
    }
}
