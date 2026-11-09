package com.bikeriding.mp;

import android.content.Context;
import android.util.Log;

import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.offline.OfflineManager;
import org.maplibre.android.offline.OfflineRegion;
import org.maplibre.android.offline.OfflineRegionStatus;
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

/**
 * Saves satellite imagery (Sentinel-2 cloudless by EOX) for an area so it also works offline.
 * Uses MapLibre's offline database; the main map style uses the same tile URL, so saved
 * tiles are shown automatically when there is no internet.
 */
public final class OfflineSatellite {
    private static final String TAG = "OfflineSatellite";
    /** must be identical to the URL in tools/make_style.py */
    public static final String SAT_URL =
            "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2024_3857/default/g/{z}/{y}/{x}.jpg";
    public static final int MIN_ZOOM = 6;
    /** average size of one satellite tile (jpg) */
    public static final long BYTES_PER_TILE = 28_000;

    public interface Listener {
        void onProgress(long done, long total, long bytes);

        void onDone(boolean ok, String message);
    }

    private OfflineSatellite() {}

    /** small style with only the satellite source, used to define what to download */
    private static String styleUrl(Context c) throws Exception {
        File f = new File(c.getFilesDir(), "satellite_style.json");
        String json = "{\"version\":8,\"sources\":{\"satellite\":{\"type\":\"raster\",\"tiles\":[\"" + SAT_URL
                + "\"],\"tileSize\":256,\"maxzoom\":14}},\"layers\":[{\"id\":\"satellite\",\"type\":\"raster\",\"source\":\"satellite\"}]}";
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return "file://" + f.getAbsolutePath();
    }

    public static long estimateTiles(LatLngBounds b, int minZoom, int maxZoom) {
        long n = 0;
        for (int z = minZoom; z <= maxZoom; z++) {
            long x1 = tileX(b.getLonWest(), z), x2 = tileX(b.getLonEast(), z);
            long y1 = tileY(b.getLatNorth(), z), y2 = tileY(b.getLatSouth(), z);
            n += (Math.abs(x2 - x1) + 1) * (Math.abs(y2 - y1) + 1);
        }
        return n;
    }

    private static long tileX(double lon, int z) {
        return (long) Math.floor((lon + 180) / 360 * (1L << z));
    }

    private static long tileY(double lat, int z) {
        double r = Math.toRadians(lat);
        return (long) Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1L << z));
    }

    /** Implements a MapLibre callback interface by method name (robust to small API renames). */
    @SuppressWarnings("unchecked")
    static <T> T callback(Class<T> iface, InvocationHandler h) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, (proxy, m, args) -> {
            switch (m.getName()) {
                case "equals": return proxy == args[0];
                case "hashCode": return System.identityHashCode(proxy);
                case "toString": return iface.getSimpleName() + "Proxy";
                default: return h.invoke(proxy, m, args);
            }
        });
    }

    public static void download(Context c, String name, LatLngBounds bounds, int maxZoom, Listener l) {
        final OfflineRegionDefinitionHolder holder = new OfflineRegionDefinitionHolder();
        try {
            float ratio = c.getResources().getDisplayMetrics().density;
            holder.def = new OfflineTilePyramidRegionDefinition(styleUrl(c), bounds, MIN_ZOOM, maxZoom, Math.min(ratio, 1f));
        } catch (Exception e) {
            l.onDone(false, "Cannot start: " + e.getMessage());
            return;
        }
        OfflineManager.CreateOfflineRegionCallback cb = callback(OfflineManager.CreateOfflineRegionCallback.class, (p, m, args) -> {
            if (m.getName().equals("onCreate")) {
                OfflineRegion region = (OfflineRegion) args[0];
                region.setObserver(callback(OfflineRegion.OfflineRegionObserver.class, (p2, m2, a2) -> {
                    String n = m2.getName();
                    if (n.equals("onStatusChanged")) {
                        OfflineRegionStatus s = (OfflineRegionStatus) a2[0];
                        l.onProgress(s.getCompletedResourceCount(), s.getRequiredResourceCount(), s.getCompletedResourceSize());
                        if (s.isComplete()) {
                            region.setDownloadState(OfflineRegion.STATE_INACTIVE);
                            l.onDone(true, "Satellite saved for \"" + name + "\" (" + (s.getCompletedResourceSize() >> 20) + " MB)");
                        }
                    } else if (n.equals("onError")) {
                        Log.w(TAG, "offline error " + (a2 == null ? "" : a2[0]));
                    } else if (n.contains("TileCountLimit") || n.contains("tileCountLimit")) {
                        region.setDownloadState(OfflineRegion.STATE_INACTIVE);
                        l.onDone(false, "Area too big – zoom in or choose less detail");
                    }
                    return null;
                }));
                region.setDownloadState(OfflineRegion.STATE_ACTIVE);
            } else if (m.getName().equals("onError")) {
                l.onDone(false, "Cannot start download: " + (args == null ? "" : args[0]));
            }
            return null;
        });
        OfflineManager.getInstance(c).createOfflineRegion(holder.def, name.getBytes(StandardCharsets.UTF_8), cb);
    }

    /** Deletes all saved satellite areas. */
    public static void deleteAll(Context c, Runnable done) {
        OfflineManager.ListOfflineRegionsCallback cb = callback(OfflineManager.ListOfflineRegionsCallback.class, (p, m, args) -> {
            if (m.getName().equals("onList")) {
                OfflineRegion[] regions = (OfflineRegion[]) args[0];
                if (regions == null || regions.length == 0) {
                    done.run();
                    return null;
                }
                final int[] left = {regions.length};
                for (OfflineRegion r : regions) {
                    r.delete(callback(OfflineRegion.OfflineRegionDeleteCallback.class, (p2, m2, a2) -> {
                        if (--left[0] == 0) done.run();
                        return null;
                    }));
                }
            } else {
                done.run();
            }
            return null;
        });
        OfflineManager.getInstance(c).listOfflineRegions(cb);
    }

    private static final class OfflineRegionDefinitionHolder {
        OfflineTilePyramidRegionDefinition def;
    }
}
