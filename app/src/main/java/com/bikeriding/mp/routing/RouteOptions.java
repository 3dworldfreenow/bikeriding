package com.bikeriding.mp.routing;

import java.util.Locale;

/** What the rider wants from a route (ideas from Kurviger / OsmAnd). */
public final class RouteOptions {
    public static final int MODE_FASTEST = 0, MODE_CURVY = 1, MODE_EXTRA_CURVY = 2, MODE_SHORTEST = 3;
    public static final String[] MODE_NAMES = {"Fastest", "Curvy", "Extra curvy", "Shortest"};

    public int mode = MODE_FASTEST;
    public boolean avoidNH = false;
    public boolean avoidSH = false;
    public boolean avoidToll = false;
    public boolean avoidUnpaved = false;
    /** Two-wheelers are not allowed on most Indian expressways, so this is on by default. */
    public boolean avoidExpressway = true;
    /** NH/SH-class main roads (trunk, primary) */
    public boolean avoidMainRoads = false;
    /** smallest roads: residential lanes, service roads, tracks */
    public boolean avoidSmallRoads = false;
    /** fords / roads that flood (important in monsoon) */
    public boolean avoidFords = false;
    /** busy city / town centres */
    public boolean avoidCities = false;
    /** Cost multiplier for roads the rider wants to avoid (soft avoid: still used if there is no other way). */
    public double avoidFactor = 12;

    public boolean fastestLike() {
        return mode != MODE_SHORTEST;
    }

    public RouteOptions copy() {
        RouteOptions o = new RouteOptions();
        o.mode = mode;
        o.avoidNH = avoidNH;
        o.avoidSH = avoidSH;
        o.avoidToll = avoidToll;
        o.avoidUnpaved = avoidUnpaved;
        o.avoidExpressway = avoidExpressway;
        o.avoidMainRoads = avoidMainRoads;
        o.avoidSmallRoads = avoidSmallRoads;
        o.avoidFords = avoidFords;
        o.avoidCities = avoidCities;
        o.avoidFactor = avoidFactor;
        return o;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(MODE_NAMES[mode]);
        if (avoidNH) sb.append(", no NH");
        if (avoidSH) sb.append(", no SH");
        if (avoidMainRoads) sb.append(", no main roads");
        if (avoidSmallRoads) sb.append(", no small lanes");
        if (avoidToll) sb.append(", no toll");
        if (avoidUnpaved) sb.append(", no kachcha");
        if (avoidFords) sb.append(", no water crossings");
        if (avoidCities) sb.append(", avoid cities");
        return sb.toString();
    }

    /** Compact text form for saving trips: "mode;flags". */
    public String encode() {
        int b = 0;
        boolean[] f = {avoidNH, avoidSH, avoidToll, avoidUnpaved, avoidExpressway, avoidMainRoads, avoidSmallRoads, avoidFords, avoidCities};
        for (int i = 0; i < f.length; i++) if (f[i]) b |= 1 << i;
        return String.format(Locale.US, "%d;%d", mode, b);
    }

    public static RouteOptions decode(String s) {
        RouteOptions o = new RouteOptions();
        try {
            String[] p = s.split(";");
            o.mode = Math.max(0, Math.min(3, Integer.parseInt(p[0])));
            int b = Integer.parseInt(p[1]);
            o.avoidNH = (b & 1) != 0;
            o.avoidSH = (b & 2) != 0;
            o.avoidToll = (b & 4) != 0;
            o.avoidUnpaved = (b & 8) != 0;
            o.avoidExpressway = (b & 16) != 0;
            o.avoidMainRoads = (b & 32) != 0;
            o.avoidSmallRoads = (b & 64) != 0;
            o.avoidFords = (b & 128) != 0;
            o.avoidCities = (b & 256) != 0;
        } catch (RuntimeException ignored) {
        }
        return o;
    }
}
