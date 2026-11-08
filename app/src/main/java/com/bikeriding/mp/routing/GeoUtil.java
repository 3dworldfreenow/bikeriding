package com.bikeriding.mp.routing;

import java.util.Locale;

public final class GeoUtil {
    public static final double R = 6371008.8;

    private GeoUtil() {}

    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * R * Math.asin(Math.sqrt(Math.min(1, a)));
    }

    /**
     * Projects point P on segment AB in a local flat approximation.
     * Returns {t (0..1), distance metres}.
     */
    public static double[] project(double plat, double plon, double alat, double alon, double blat, double blon) {
        double k = Math.cos(Math.toRadians(plat));
        double ax = (alon - plon) * k, ay = alat - plat;
        double bx = (blon - plon) * k, by = blat - plat;
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : -(ax * dx + ay * dy) / len2;
        if (t < 0) t = 0;
        else if (t > 1) t = 1;
        double x = ax + t * dx, y = ay + t * dy;
        double d = Math.sqrt(x * x + y * y) * Math.PI / 180 * R;
        return new double[]{t, d};
    }

    public static String formatDistance(double m) {
        if (m < 1000) return String.format(Locale.US, "%.0f m", m);
        if (m < 20000) return String.format(Locale.US, "%.1f km", m / 1000);
        return String.format(Locale.US, "%.0f km", m / 1000);
    }

    public static String formatDuration(double s) {
        long min = Math.round(s / 60);
        if (min < 60) return min + " min";
        return (min / 60) + " h " + (min % 60) + " min";
    }
}
