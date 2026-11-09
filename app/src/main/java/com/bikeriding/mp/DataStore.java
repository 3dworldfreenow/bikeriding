package com.bikeriding.mp;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

import com.bikeriding.mp.routing.Graph;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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
 * mp.pmtiles + mapstyle/ (map), mp.graph (routing), places.tsv (search).
 * Big files are stored compressed in the APK (smaller download) and unpacked once
 * after each install/update.
 */
public final class DataStore {
    private static final String TAG = "DataStore";
    public static final String TILES_ASSET = "mp.pmtiles";
    public static final String STYLE_DIR = "mapstyle";
    public static final String GRAPH_ASSET = "mp.graph";
    public static final String PLACES_ASSET = "places.tsv";

    public interface Progress {
        void onProgress(String what, int mb);
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

    /** Changes with every new APK install, so data is re-unpacked after an update. */
    private static String installVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.lastUpdateTime + "/" + assetVersion(c);
        } catch (Exception e) {
            return assetVersion(c);
        }
    }

    public static File dataDir(Context c) {
        File d = new File(c.getFilesDir(), "data");
        if (!d.exists() && !d.mkdirs()) Log.w(TAG, "cannot create " + d);
        return d;
    }

    private static File unpack(Context c, String asset, File out, String label, Progress progress) throws IOException {
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
        File tmp = new File(out.getPath() + ".tmp");
        try (InputStream in = c.getAssets().open(asset); OutputStream os = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 20];
            long done = 0, lastMb = -1;
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                long mb = done >> 20;
                if (progress != null && label != null && mb != lastMb) progress.onProgress(label, (int) (lastMb = mb));
            }
        } catch (IOException e) {
            if (!tmp.delete()) Log.w(TAG, "could not delete " + tmp);
            throw e;
        }
        if (out.exists() && !out.delete()) Log.w(TAG, "could not delete old " + out);
        if (!tmp.renameTo(out)) throw new IOException("cannot move " + asset);
        return out;
    }

    private static void unpackDir(Context c, String assetDir, File outDir) throws IOException {
        String[] children = c.getAssets().list(assetDir);
        if (children == null) return;
        if (!outDir.exists() && !outDir.mkdirs()) throw new IOException("cannot create " + outDir);
        for (String ch : children) {
            String path = assetDir + "/" + ch;
            String[] sub = c.getAssets().list(path);
            if (sub != null && sub.length > 0) unpackDir(c, path, new File(outDir, ch));
            else unpack(c, path, new File(outDir, ch), null, null);
        }
    }

    /** Unpacks everything if this APK's data is not unpacked yet. Returns false if the APK has no map data. */
    public static boolean prepare(Context c, Progress progress) throws IOException {
        File dir = dataDir(c);
        File stamp = new File(dir, "version");
        String version = installVersion(c);
        boolean hasTiles = hasAsset(c, TILES_ASSET);
        if (version.equals(readText(stamp))) return hasTiles;
        if (hasTiles) unpack(c, TILES_ASSET, new File(dir, TILES_ASSET), "map", progress);
        if (hasAsset(c, GRAPH_ASSET)) unpack(c, GRAPH_ASSET, new File(dir, GRAPH_ASSET), "road network", progress);
        File styleDir = new File(dir, STYLE_DIR);
        deleteRecursive(styleDir);
        unpackDir(c, STYLE_DIR, styleDir);
        // old versions kept data elsewhere – free that space
        deleteRecursive(new File(c.getFilesDir(), "maps"));
        writeText(stamp, version);
        return hasTiles;
    }

    /** Style JSON with paths filled in, or null if there is no map data. */
    public static String styleJson(Context c) throws IOException {
        File dir = dataDir(c);
        File tiles = new File(dir, TILES_ASSET);
        if (!tiles.exists()) return null;
        String json;
        try (InputStream in = c.getAssets().open(STYLE_DIR + "/style.json")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            json = bo.toString("UTF-8");
        }
        return json.replace("{PMTILES_URL}", "pmtiles://file://" + tiles.getAbsolutePath())
                .replace("{FILES}", "file://" + new File(dir, STYLE_DIR).getAbsolutePath());
    }

    public static Graph openGraph(Context c) throws IOException {
        File f = new File(dataDir(c), GRAPH_ASSET);
        return f.exists() ? Graph.open(f) : null;
    }

    public static PlacesIndex openPlaces(Context c) throws IOException {
        if (!hasAsset(c, PLACES_ASSET)) return null;
        try (InputStream in = c.getAssets().open(PLACES_ASSET)) {
            return PlacesIndex.load(in);
        }
    }

    static void deleteRecursive(File f) {
        if (!f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        if (!f.delete()) Log.w(TAG, "could not delete " + f);
    }

    static String readText(File f) {
        if (!f.exists()) return null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String s = r.readLine();
            return s == null ? "" : s.trim();
        } catch (IOException e) {
            return null;
        }
    }

    static void writeText(File f, String s) throws IOException {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
