package com.bikeriding.mp;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
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
     * The map file must be a real file for mapsforge, so it is copied out of the APK once
     * (and again whenever a newer APK brings newer data).
     */
    public static File prepareMap(Context c, Progress progress) throws IOException {
        File dir = new File(c.getFilesDir(), "maps");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File out = new File(dir, MAP_ASSET);
        File stamp = new File(dir, MAP_ASSET + ".version");
        String version = assetVersion(c);
        if (out.exists() && version.equals(readText(stamp))) return out;
        if (!hasAsset(c, MAP_ASSET)) {
            // a map file put there manually (developer build without assets)
            return out.exists() ? out : null;
        }
        long total = -1;
        try (AssetFileDescriptor afd = c.getAssets().openFd(MAP_ASSET)) {
            total = afd.getLength();
        } catch (IOException ignored) {
            // compressed asset: size unknown
        }
        File tmp = new File(dir, MAP_ASSET + ".tmp");
        try (InputStream in = c.getAssets().open(MAP_ASSET); OutputStream os = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 20];
            long done = 0;
            int n, last = -1;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                if (total > 0 && progress != null) {
                    int p = (int) (done * 100 / total);
                    if (p != last) progress.onProgress(last = p);
                }
            }
        }
        if (out.exists() && !out.delete()) Log.w(TAG, "could not delete old map");
        if (!tmp.renameTo(out)) throw new IOException("cannot move map file");
        writeText(stamp, version);
        return out;
    }

    /** Memory-maps the routing graph straight out of the APK (it is stored uncompressed). */
    public static Graph openGraph(Context c) throws IOException {
        File manual = new File(c.getFilesDir(), "maps/" + GRAPH_ASSET);
        if (!hasAsset(c, GRAPH_ASSET)) {
            return manual.exists() ? Graph.open(manual) : null;
        }
        try {
            AssetFileDescriptor afd = c.getAssets().openFd(GRAPH_ASSET);
            FileInputStream in = new FileInputStream(afd.getFileDescriptor());
            Graph g = Graph.open(in, afd.getStartOffset(), afd.getLength());
            in.close();
            afd.close();
            return g; // the mapping stays valid after closing the file
        } catch (IOException e) {
            Log.w(TAG, "graph asset is compressed, copying it out", e);
        }
        File out = new File(c.getFilesDir(), "maps/" + GRAPH_ASSET);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("mkdir failed");
        try (InputStream in = c.getAssets().open(GRAPH_ASSET); OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        return Graph.open(out);
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
