package com.bikeriding.mp.routing;

/** What the rider wants from a route. */
public final class RouteOptions {
    /** true = fastest (by time), false = shortest (by distance). */
    public boolean fastest = true;
    public boolean avoidNH = false;
    public boolean avoidSH = false;
    public boolean avoidToll = false;
    public boolean avoidUnpaved = false;
    /** Two-wheelers are not allowed on most Indian expressways, so this is on by default. */
    public boolean avoidExpressway = true;
    /** Cost multiplier for roads the rider wants to avoid (soft avoid: still used if there is no other way). */
    public double avoidFactor = 12;

    public RouteOptions copy() {
        RouteOptions o = new RouteOptions();
        o.fastest = fastest;
        o.avoidNH = avoidNH;
        o.avoidSH = avoidSH;
        o.avoidToll = avoidToll;
        o.avoidUnpaved = avoidUnpaved;
        o.avoidExpressway = avoidExpressway;
        o.avoidFactor = avoidFactor;
        return o;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(fastest ? "Fastest" : "Shortest");
        if (avoidNH) sb.append(", no NH");
        if (avoidSH) sb.append(", no SH");
        if (avoidToll) sb.append(", no toll");
        if (avoidUnpaved) sb.append(", no kachcha");
        return sb.toString();
    }
}
