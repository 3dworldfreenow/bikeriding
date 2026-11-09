package com.bikeriding.mp;

import android.content.Context;
import android.util.Log;

import com.bikeriding.mp.routing.Graph;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Offline data that ships inside the APK (app/src/main/assets, built by tools/build_data.sh):
 * mp.map (vector map), mp.graph (routing), places.tsv (search).
 */
public final class DataStore {
    private static final String TAG = "DataStore";
    public static final String MAP_ASSET = "mp.map";
    public static final String GRAPH_ASSET = "mp.graph";
    public static final String PLACES_ASSET = "places.tsv";

    public interface Progress {
        void onProgress(int percent);
    }

    private DataStore() {}

    public static boolean hasAsset(Context c, String name) {
        try (InputStream in = c.getAssets().open(name)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    public static String assetVersion(Context c) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("data_version.txt"),
                StandardCharsets.UTF_8))) {
            String s = r.readLine();
            return s == null ? "" : s.trim();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Data files are stored compressed inside the APK (smaller download) and unpacked once
     * on first start, and again whenever a newer APK brings newer data.
     */
    private static File unpack(Context c, String asset, Progress progress) throws IOException {
        File dir = new File(c.getFilesDir(), "maps");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File out = new File(dir, asset);
        File stamp = new File(dir, asset + ".version");
        String version = assetVersion(c);
        if (!hasAsset(c, asset)) {
            // developer build without assets: use a file put there manually
            return out.exists() ? out : null;
        }
        if (out.exists() && version.equals(readText(stamp))) return out;
        File tmp = new File(dir, asset + ".tmp");
        try (InputStream in = c.getAssets().open(asset); OutputStream os = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 20];
            long done = 0;
            int n;
            long lastMb = -1;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                long mb = done >> 20;
                if (progress != null && mb != lastMb) progress.onProgress((int) (lastMb = mb));
            }
        } catch (IOException e) {
            if (!tmp.delete()) Log.w(TAG, "could not delete " + tmp);
            throw e;
        }
        if (out.exists() && !out.delete()) Log.w(TAG, "could not delete old " + out);
        if (!tmp.renameTo(out)) throw new IOException("cannot move " + asset);
        writeText(stamp, version);
        return out;
    }

    /** Vector map file (progress = MB unpacked so far). */
    public static File prepareMap(Context c, Progress progress) throws IOException {
        return unpack(c, MAP_ASSET, progress);
    }

    /** Routing graph, memory-mapped from the unpacked file. */
    public static Graph openGraph(Context c, Progress progress) throws IOException {
        File f = unpack(c, GRAPH_ASSET, progress);
        return f == null ? null : Graph.open(f);
    }

    public static PlacesIndex openPlaces(Context c) throws IOException {
        if (!hasAsset(c, PLACES_ASSET)) return null;
        try (InputStream in = c.getAssets().open(PLACES_ASSET)) {
            return PlacesIndex.load(in);
        }
    }

    private static String readText(File f) {
        if (!f.exists()) return null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String s = r.readLine();
            return s == null ? "" : s.trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeText(File f, String s) throws IOException {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
