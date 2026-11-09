package com.bikeriding.mp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.bikeriding.mp.routing.GeoUtil;
import com.bikeriding.mp.routing.Graph;
import com.bikeriding.mp.routing.Route;
import com.bikeriding.mp.routing.RouteOptions;
import com.bikeriding.mp.routing.Router;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.location.LocationComponent;
import org.maplibre.android.location.LocationComponentActivationOptions;
import org.maplibre.android.location.OnCameraTrackingChangedListener;
import org.maplibre.android.location.modes.CameraMode;
import org.maplibre.android.location.modes.RenderMode;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.maps.UiSettings;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.BackgroundLayer;
import org.maplibre.android.style.layers.FillExtrusionLayer;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements LocationListener, TrackRecorder.Listener {
    private static final String TAG = "BikeRidingMP";
    private static final LatLng BHOPAL = new LatLng(23.2599, 77.4126);
    private static final int REQ_LOCATION = 1, REQ_NOTIFY = 2;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TripStore trips;

    // views
    private MapView mapView;
    private View loading, routePanel, navPanel;
    private TextView loadingText, speedView, recordInfo, routeTitle, navMain, navSub, btnRecord, btnLocate, btnLayers;
    private LinearLayout altContainer, modeChips;

    // map
    private MapLibreMap map;
    private Style style;
    private String styleJson;
    private boolean dataReady;
    private GeoJsonSource srcAlt, srcMain, srcTrack, srcPins;
    private LocationComponent lc;
    private boolean satellite;
    private final Set<String> hiddenForSatellite = new HashSet<>();

    // data
    private Router router;
    private PlacesIndex places;

    // state
    private LocationManager lm;
    private Location myLoc;
    private boolean follow = true, headingUp;
    private LatLng startPt, destPt, searchPt;
    private final List<LatLng> vias = new ArrayList<>();
    private final List<Route> routes = new ArrayList<>();
    private final List<String> routeNames = new ArrayList<>();
    private int selected = 0;
    private boolean navigating, rerouting;
    private int offRouteCount, navIndex;
    private TurnAlerts alerts;
    private int alertTurnPoint = -1, alertStage;
    private boolean arrivedAlerted;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MapLibre.getInstance(this);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        trips = new TripStore(this);
        alerts = new TurnAlerts(this, prefs);
        satellite = prefs.getBoolean("satellite", false);
        setContentView(R.layout.activity_main);

        mapView = findViewById(R.id.mapView);
        loading = findViewById(R.id.loading);
        loadingText = findViewById(R.id.loadingText);
        speedView = findViewById(R.id.speedView);
        recordInfo = findViewById(R.id.recordInfo);
        routePanel = findViewById(R.id.routePanel);
        routeTitle = findViewById(R.id.routeTitle);
        altContainer = findViewById(R.id.altContainer);
        modeChips = findViewById(R.id.modeChips);
        navPanel = findViewById(R.id.navPanel);
        navMain = findViewById(R.id.navMain);
        navSub = findViewById(R.id.navSub);
        btnRecord = findViewById(R.id.btnRecord);
        btnLocate = findViewById(R.id.btnLocate);
        btnLayers = findViewById(R.id.btnLayers);

        applyInsets();

        findViewById(R.id.searchBox).setOnClickListener(v -> showSearch());
        findViewById(R.id.menuBtn).setOnClickListener(v -> showMenu());
        btnLocate.setOnClickListener(v -> onLocateClicked());
        btnRecord.setOnClickListener(v -> toggleRecording());
        btnLayers.setOnClickListener(v -> setSatellite(!satellite, true));
        findViewById(R.id.btnRoute).setOnClickListener(v -> onRouteButton());
        findViewById(R.id.btnOptions).setOnClickListener(v -> showRouteOptions());
        findViewById(R.id.btnStart).setOnClickListener(v -> startRide());
        findViewById(R.id.btnSave).setOnClickListener(v -> saveTrip());
        findViewById(R.id.btnClear).setOnClickListener(v -> clearRoute());
        buildModeChips();
        updateLayersButton();

        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(m -> {
            map = m;
            setupMap();
            loadStyleIfReady();
        });
        loadData();
    }

    private void applyInsets() {
        View top = findViewById(R.id.topArea), bottom = findViewById(R.id.bottomArea);
        int pad = dp(10);
        findViewById(R.id.root).setOnApplyWindowInsetsListener((v, insets) -> {
            int t = insets.getSystemWindowInsetTop(), b = insets.getSystemWindowInsetBottom();
            int l = insets.getSystemWindowInsetLeft(), r = insets.getSystemWindowInsetRight();
            top.setPadding(pad + l, pad + t, pad + r, pad);
            bottom.setPadding(pad + l, pad, pad + r, pad + b);
            if (map != null) {
                map.getUiSettings().setCompassMargins(0, t + dp(140), r + dp(16), 0);
                map.getUiSettings().setAttributionMargins(l + dp(8), 0, 0, b + dp(4));
            }
            return insets;
        });
    }

    private void loadData() {
        loading.setVisibility(View.VISIBLE);
        bg.execute(() -> {
            String json = null, error = null;
            try {
                DataStore.prepare(this, (what, mb) -> ui.post(() ->
                        loadingText.setText("Unpacking offline " + what + " of Madhya Pradesh…\n(first start after install)\n" + mb + " MB")));
                json = DataStore.styleJson(this);
            } catch (Exception e) {
                Log.e(TAG, "data", e);
                error = e.getMessage();
            }
            try {
                Graph g = DataStore.openGraph(this);
                if (g != null) router = new Router(g);
            } catch (Exception e) {
                Log.e(TAG, "graph", e);
            }
            try {
                places = DataStore.openPlaces(this);
            } catch (Exception e) {
                Log.e(TAG, "places", e);
            }
            final String sj = json, err = error;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                styleJson = sj;
                dataReady = true;
                loading.setVisibility(View.GONE);
                if (sj == null) {
                    new AlertDialog.Builder(this).setTitle("Offline map missing")
                            .setMessage((err != null ? err + "\n\n" : "")
                                    + "This APK was built without map data. Download the full APK from the GitHub "
                                    + "Releases page (built by GitHub Actions), or run tools/build_data.sh before building.")
                            .setPositiveButton("OK", null).show();
                } else if (router == null) {
                    Toast.makeText(this, "Routing data missing – trip planning disabled", Toast.LENGTH_LONG).show();
                }
                loadStyleIfReady();
            });
        });
    }

    private void setupMap() {
        UiSettings u = map.getUiSettings();
        u.setCompassEnabled(true);
        u.setCompassGravity(Gravity.TOP | Gravity.END);
        u.setCompassMargins(0, dp(170), dp(16), 0);
        u.setLogoEnabled(false);
        u.setAttributionEnabled(true);
        u.setAttributionGravity(Gravity.BOTTOM | Gravity.START);
        u.setRotateGesturesEnabled(true);
        u.setTiltGesturesEnabled(true);
        map.setMinZoomPreference(5);
        map.setMaxZoomPreference(19);
        map.setCameraPosition(new CameraPosition.Builder()
                .target(new LatLng(prefs.getFloat("lat", (float) BHOPAL.getLatitude()), prefs.getFloat("lon", (float) BHOPAL.getLongitude())))
                .zoom(prefs.getFloat("zoomf", 8f)).build());
        map.addOnMapLongClickListener(p -> {
            showPointMenu(p);
            return true;
        });
    }

    private void loadStyleIfReady() {
        if (map == null || !dataReady) return;
        Style.Builder b = styleJson != null ? new Style.Builder().fromJson(styleJson)
                : new Style.Builder().fromJson("{\"version\":8,\"sources\":{},\"layers\":[{\"id\":\"bg\",\"type\":\"background\",\"paint\":{\"background-color\":\"#eef0ea\"}}]}");
        map.setStyle(b, s -> {
            style = s;
            addOverlayLayers(s);
            enableLocationComponent();
            if (styleJson != null) setSatellite(satellite, false);
            drawRoutes();
            updatePins();
            TrackRecorder.get().addListener(this);
            onTrackChanged();
        });
    }

    /** id of the first label layer – our lines go below it so names stay readable */
    private String firstSymbolLayer(Style s) {
        for (Layer l : s.getLayers()) {
            if (l instanceof SymbolLayer) return l.getId();
        }
        return null;
    }

    private void addOverlayLayers(Style s) {
        srcAlt = new GeoJsonSource("route-alt");
        srcMain = new GeoJsonSource("route-main");
        srcTrack = new GeoJsonSource("track");
        srcPins = new GeoJsonSource("pins");
        s.addSource(srcAlt);
        s.addSource(srcMain);
        s.addSource(srcTrack);
        s.addSource(srcPins);
        String below = firstSymbolLayer(s);
        Layer[] lines = {
                new LineLayer("route-alt-line", "route-alt").withProperties(
                        PropertyFactory.lineColor(0xFF90A4AE), PropertyFactory.lineWidth(6f), PropertyFactory.lineOpacity(0.85f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)),
                new LineLayer("route-main-casing", "route-main").withProperties(
                        PropertyFactory.lineColor(0xFFFFFFFF), PropertyFactory.lineWidth(10f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)),
                new LineLayer("route-main-line", "route-main").withProperties(
                        PropertyFactory.lineColor(0xFF1A73E8), PropertyFactory.lineWidth(7f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)),
                new LineLayer("track-line", "track").withProperties(
                        PropertyFactory.lineColor(0xDDD32F2F), PropertyFactory.lineWidth(4f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)),
        };
        for (Layer l : lines) {
            if (below != null) s.addLayerBelow(l, below);
            else s.addLayer(l);
        }
        s.addImage("pin-A", drawPin(0xFF2E7D32, "A"));
        s.addImage("pin-B", drawPin(0xFFC62828, "B"));
        s.addImage("pin-S", drawPin(0xFF6A1B9A, "•"));
        for (int i = 1; i <= 9; i++) s.addImage("pin-" + i, drawPin(0xFFF9A825, String.valueOf(i)));
        s.addLayer(new SymbolLayer("pins-layer", "pins").withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM)));
    }

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        startLocation();
    }

    @Override
    protected void onPause() {
        if (!navigating) stopLocation();
        if (map != null) {
            CameraPosition c = map.getCameraPosition();
            if (c.target != null) {
                prefs.edit().putFloat("lat", (float) c.target.getLatitude()).putFloat("lon", (float) c.target.getLongitude())
                        .putFloat("zoomf", (float) c.zoom).apply();
            }
        }
        mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        mapView.onStop();
        super.onStop();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        stopLocation();
        TrackRecorder.get().removeListener(this);
        bg.shutdownNow();
        alerts.release();
        if (navigating) TrackService.stopNav(this);
        mapView.onDestroy();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (navigating) {
            new AlertDialog.Builder(this).setMessage("Stop the ride navigation?")
                    .setPositiveButton("Stop", (d, w) -> stopRide()).setNegativeButton("Continue", null).show();
        } else if (!routes.isEmpty()) {
            clearRoute();
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------------ satellite

    private void setSatellite(boolean on, boolean userAction) {
        satellite = on;
        prefs.edit().putBoolean("satellite", on).apply();
        updateLayersButton();
        if (style == null) return;
        Layer sat = style.getLayer("satellite");
        if (sat == null) return;
        if (on) {
            hiddenForSatellite.clear();
            for (Layer l : style.getLayers()) {
                if (l instanceof FillLayer || l instanceof FillExtrusionLayer || l instanceof BackgroundLayer) {
                    if (!Property.NONE.equals(l.getVisibility().getValue())) {
                        hiddenForSatellite.add(l.getId());
                        l.setProperties(PropertyFactory.visibility(Property.NONE));
                    }
                }
            }
            sat.setProperties(PropertyFactory.visibility(Property.VISIBLE));
            if (userAction) {
                Toast.makeText(this, "Satellite view (needs internet, or save an area: menu ⋮)", Toast.LENGTH_LONG).show();
            }
        } else {
            sat.setProperties(PropertyFactory.visibility(Property.NONE));
            for (String id : hiddenForSatellite) {
                Layer l = style.getLayer(id);
                if (l != null) l.setProperties(PropertyFactory.visibility(Property.VISIBLE));
            }
            hiddenForSatellite.clear();
        }
    }

    private void updateLayersButton() {
        btnLayers.setText(satellite ? "🗺" : "🛰");
        btnLayers.setBackgroundResource(satellite ? R.drawable.bg_fab_active : R.drawable.bg_fab);
    }

    private void saveSatellite(boolean forRoute) {
        LatLngBounds b;
        String name;
        if (forRoute && !routes.isEmpty()) {
            Route r = routes.get(selected);
            LatLngBounds.Builder bb = new LatLngBounds.Builder();
            for (int i = 0; i < r.size(); i += Math.max(1, r.size() / 200)) bb.include(new LatLng(r.lats[i], r.lons[i]));
            bb.include(new LatLng(r.lats[r.size() - 1], r.lons[r.size() - 1]));
            try {
                b = pad(bb.build(), 0.05);
            } catch (Exception e) {
                b = null;
            }
            name = routeNames.get(selected);
        } else {
            b = map == null ? null : map.getProjection().getVisibleRegion().latLngBounds;
            name = "Area " + new SimpleDateFormat("d MMM HH:mm", Locale.US).format(new Date());
        }
        if (b == null) {
            Toast.makeText(this, "Plan a route first, or choose \"visible area\"", Toast.LENGTH_LONG).show();
            return;
        }
        final LatLngBounds bounds = b;
        final String regionName = name;
        String[] levels = {"Normal detail (up to zoom 12)", "High detail (up to zoom 13)", "Max detail (zoom 14 – big!)"};
        int[] zooms = {12, 13, 14};
        String[] items = new String[3];
        for (int i = 0; i < 3; i++) {
            long tiles = OfflineSatellite.estimateTiles(bounds, OfflineSatellite.MIN_ZOOM, zooms[i]);
            items[i] = levels[i] + String.format(Locale.US, " – about %d MB", Math.max(1, tiles * OfflineSatellite.BYTES_PER_TILE >> 20));
        }
        new AlertDialog.Builder(this).setTitle("Save satellite for offline use (use Wi‑Fi)")
                .setItems(items, (d, which) -> {
                    recordInfo.setVisibility(View.VISIBLE);
                    recordInfo.setText("🛰 Saving satellite… 0%");
                    OfflineSatellite.download(this, regionName, bounds, zooms[which], new OfflineSatellite.Listener() {
                        @Override
                        public void onProgress(long done, long total, long bytes) {
                            if (total > 0) recordInfo.setText(String.format(Locale.US, "🛰 Saving satellite… %d%% (%d MB)",
                                    done * 100 / total, bytes >> 20));
                        }

                        @Override
                        public void onDone(boolean ok, String message) {
                            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                            onTrackChanged();
                        }
                    });
                }).setNegativeButton("Cancel", null).show();
    }

    private static LatLngBounds pad(LatLngBounds b, double deg) {
        return LatLngBounds.from(b.getLatNorth() + deg, b.getLonEast() + deg, b.getLatSouth() - deg, b.getLonWest() - deg);
    }

    // ------------------------------------------------------------------ location

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private void enableLocationComponent() {
        if (map == null || style == null || lc != null) return;
        try {
            lc = map.getLocationComponent();
            lc.activateLocationComponent(LocationComponentActivationOptions.builder(this, style)
                    .useDefaultLocationEngine(false).build());
            lc.setLocationComponentEnabled(true);
            lc.setRenderMode(RenderMode.COMPASS);
            lc.setCameraMode(follow ? CameraMode.TRACKING : CameraMode.NONE);
            lc.addOnCameraTrackingChangedListener(new OnCameraTrackingChangedListener() {
                @Override
                public void onCameraTrackingDismissed() {
                    follow = false;
                    headingUp = false;
                    updateLocateButton();
                }

                @Override
                public void onCameraTrackingChanged(int currentMode) {
                }
            });
            if (myLoc != null) lc.forceLocationUpdate(myLoc);
        } catch (Exception e) {
            Log.w(TAG, "location component", e);
            lc = null;
        }
    }

    private void startLocation() {
        if (!hasLocationPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        try {
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Toast.makeText(this, "Please turn on Location (GPS)", Toast.LENGTH_LONG).show();
            }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
            if (lm.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 0, this, Looper.getMainLooper());
            }
            Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null && myLoc == null) onLocationChanged(last);
        } catch (SecurityException e) {
            Log.w(TAG, "location", e);
        }
    }

    private void stopLocation() {
        lm.removeUpdates(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && hasLocationPermission()) startLocation();
        else if (requestCode == REQ_LOCATION) Toast.makeText(this, "Location permission is needed to show where you are", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onLocationChanged(Location l) {
        if (LocationManager.NETWORK_PROVIDER.equals(l.getProvider()) && myLoc != null
                && LocationManager.GPS_PROVIDER.equals(myLoc.getProvider()) && l.getTime() - myLoc.getTime() < 15000) {
            return;
        }
        myLoc = l;
        if (lc != null) {
            try {
                lc.forceLocationUpdate(l);
            } catch (Exception e) {
                Log.w(TAG, "forceLocationUpdate", e);
            }
        }
        if (l.hasSpeed() && LocationManager.GPS_PROVIDER.equals(l.getProvider())) {
            speedView.setVisibility(View.VISIBLE);
            speedView.setText(String.format(Locale.US, "%d\nkm/h", Math.round(l.getSpeed() * 3.6)));
        }
        if (navigating) updateNavigation(l);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    /** 1st tap: follow me (north up). 2nd tap: follow + map turns with my direction. */
    private void onLocateClicked() {
        if (myLoc == null) {
            Toast.makeText(this, hasLocationPermission() ? "Waiting for GPS signal… (go under open sky)" :
                    "Location permission needed", Toast.LENGTH_SHORT).show();
            if (!hasLocationPermission()) startLocation();
            return;
        }
        if (follow && !headingUp && !navigating) {
            headingUp = true;
        } else if (follow && headingUp && !navigating) {
            headingUp = false;
        } else {
            follow = true;
        }
        if (lc != null && map != null) {
            int mode = navigating ? CameraMode.TRACKING_GPS : headingUp ? CameraMode.TRACKING_COMPASS : CameraMode.TRACKING;
            double zoom = Math.max(map.getCameraPosition().zoom, navigating ? 16.5 : 15);
            lc.setCameraMode(mode, 700, zoom, headingUp || navigating ? null : 0.0, navigating ? 45.0 : 0.0, null);
        }
        updateLocateButton();
    }

    private void updateLocateButton() {
        btnLocate.setBackgroundResource(follow ? R.drawable.bg_fab_active : R.drawable.bg_fab);
        btnLocate.setTextColor(follow ? Color.WHITE : 0xFF1565C0);
        btnLocate.setText(headingUp ? "➤" : "◎");
    }

    private void stopFollowing() {
        follow = false;
        headingUp = false;
        if (lc != null) lc.setCameraMode(CameraMode.NONE);
        updateLocateButton();
    }

    // ------------------------------------------------------------------ points & pins

    private void showPointMenu(LatLng p) {
        String[] items = {"🧭 Route here (from my location)", "🟢 Set as start", "🏁 Set as destination",
                "➕ Add stop on the way", "📍 What is here?"};
        new AlertDialog.Builder(this)
                .setTitle(String.format(Locale.US, "%.5f, %.5f", p.getLatitude(), p.getLongitude()))
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: startPt = null; destPt = p; vias.clear(); updatePins(); computeRoutes(); break;
                        case 1: startPt = p; updatePins(); if (destPt != null) computeRoutes(); break;
                        case 2: destPt = p; updatePins(); computeRoutes(); break;
                        case 3: if (vias.size() < 9) vias.add(p); updatePins(); if (destPt != null) computeRoutes(); break;
                        default: whatIsHere(p);
                    }
                }).show();
    }

    private void whatIsHere(LatLng p) {
        double la = p.getLatitude(), lo = p.getLongitude();
        StringBuilder sb = new StringBuilder();
        if (places != null) {
            for (String cat : new String[]{"city", "town", "village"}) {
                List<PlacesIndex.Place> l = places.nearest(cat, la, lo, 1);
                if (!l.isEmpty()) {
                    PlacesIndex.Place pl = l.get(0);
                    sb.append(PlacesIndex.categoryLabel(cat)).append(": ").append(pl.title()).append(" – ")
                            .append(GeoUtil.formatDistance(GeoUtil.distance(la, lo, pl.lat, pl.lon))).append('\n');
                }
            }
            List<PlacesIndex.Place> fuel = places.nearest("fuel", la, lo, 1);
            if (!fuel.isEmpty()) {
                PlacesIndex.Place f = fuel.get(0);
                sb.append("Nearest petrol: ").append(f.title()).append(" – ")
                        .append(GeoUtil.formatDistance(GeoUtil.distance(la, lo, f.lat, f.lon))).append('\n');
            }
        }
        if (router != null) {
            Router.Snap s = router.snap(la, lo, 2000);
            if (s != null) {
                Graph g = router.graph();
                String name = g.edgeName(s.edge);
                sb.append("Road: ").append(name == null ? "unnamed road" : name)
                        .append(" (").append(GeoUtil.formatDistance(s.offRoadM)).append(" away)\n");
                int f = g.edgeFlags(s.edge);
                if ((f & Graph.F_UNPAVED) != 0) sb.append("Road surface: kachcha / unpaved\n");
                if ((f & Graph.F_FORD) != 0) sb.append("⚠ Water crossing on this road\n");
                if (g.edgeCurvDegPerKm(s.edge) >= 60) sb.append("Twisty road 🌀\n");
            }
        }
        if (sb.length() == 0) sb.append("No data");
        new AlertDialog.Builder(this).setTitle("What is here").setMessage(sb.toString().trim())
                .setPositiveButton("OK", null).show();
    }

    private static Feature pin(LatLng p, String icon) {
        Feature f = Feature.fromGeometry(Point.fromLngLat(p.getLongitude(), p.getLatitude()));
        f.addStringProperty("icon", icon);
        return f;
    }

    private void updatePins() {
        if (srcPins == null) return;
        List<Feature> fs = new ArrayList<>();
        if (searchPt != null) fs.add(pin(searchPt, "pin-S"));
        if (startPt != null) fs.add(pin(startPt, "pin-A"));
        for (int i = 0; i < vias.size(); i++) fs.add(pin(vias.get(i), "pin-" + (i + 1)));
        if (destPt != null) fs.add(pin(destPt, "pin-B"));
        srcPins.setGeoJson(FeatureCollection.fromFeatures(fs));
    }

    // ------------------------------------------------------------------ routing

    private void onRouteButton() {
        if (destPt == null) {
            String[] items = {"🔍 Search a place", "📂 My saved trips", "ℹ How to plan a trip"};
            new AlertDialog.Builder(this).setTitle("Plan a trip").setItems(items, (d, w) -> {
                if (w == 0) showSearch();
                else if (w == 1) showTrips();
                else new AlertDialog.Builder(this).setTitle("Plan a trip")
                            .setMessage("• Long-press on the map where you want to go\n"
                                    + "• Or search a village / town and tap \"Route here\"\n"
                                    + "• Long-press again → \"Add stop on the way\" for stops\n\n"
                                    + "You get two routes to compare: with NH and without NH. Choose a riding style "
                                    + "(Fastest, Curvy, Extra curvy, Shortest) and use ⚙ Avoid for SH, toll, kachcha roads, "
                                    + "water crossings and city centres. 💾 saves the plan.")
                            .setPositiveButton("OK", null).show();
            }).show();
        } else {
            computeRoutes();
        }
    }

    private RouteOptions savedOptions() {
        RouteOptions o = new RouteOptions();
        o.mode = prefs.getInt("mode", RouteOptions.MODE_FASTEST);
        o.avoidNH = prefs.getBoolean("avoidNH", false);
        o.avoidSH = prefs.getBoolean("avoidSH", false);
        o.avoidToll = prefs.getBoolean("avoidToll", false);
        o.avoidUnpaved = prefs.getBoolean("avoidUnpaved", false);
        o.avoidExpressway = !prefs.getBoolean("allowExpressway", false);
        o.avoidMainRoads = prefs.getBoolean("avoidMain", false);
        o.avoidSmallRoads = prefs.getBoolean("avoidSmall", false);
        o.avoidFords = prefs.getBoolean("avoidFords", false);
        o.avoidCities = prefs.getBoolean("avoidCities", false);
        return o;
    }

    private void storeOptions(RouteOptions o) {
        prefs.edit().putInt("mode", o.mode).putBoolean("avoidNH", o.avoidNH).putBoolean("avoidSH", o.avoidSH)
                .putBoolean("avoidToll", o.avoidToll).putBoolean("avoidUnpaved", o.avoidUnpaved)
                .putBoolean("allowExpressway", !o.avoidExpressway).putBoolean("avoidMain", o.avoidMainRoads)
                .putBoolean("avoidSmall", o.avoidSmallRoads).putBoolean("avoidFords", o.avoidFords)
                .putBoolean("avoidCities", o.avoidCities).apply();
    }

    private void buildModeChips() {
        modeChips.removeAllViews();
        int mode = prefs.getInt("mode", RouteOptions.MODE_FASTEST);
        String[] icons = {"⚡ ", "🌀 ", "🏔 ", "📏 "};
        for (int i = 0; i < RouteOptions.MODE_NAMES.length; i++) {
            final int m = i;
            TextView chip = new TextView(this);
            chip.setText(icons[i] + RouteOptions.MODE_NAMES[i]);
            boolean sel = i == mode;
            chip.setBackgroundResource(sel ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            chip.setTextColor(sel ? Color.WHITE : 0xFF1565C0);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                prefs.edit().putInt("mode", m).apply();
                buildModeChips();
                if (destPt != null) computeRoutes();
            });
            modeChips.addView(chip);
        }
    }

    private double[][] routePoints(Location from) {
        List<LatLng> pts = new ArrayList<>();
        if (from != null) pts.add(new LatLng(from.getLatitude(), from.getLongitude()));
        else if (startPt != null) pts.add(startPt);
        else if (myLoc != null) pts.add(new LatLng(myLoc.getLatitude(), myLoc.getLongitude()));
        else return null;
        if (from == null) pts.addAll(vias);
        pts.add(destPt);
        double[][] r = new double[2][pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            r[0][i] = pts.get(i).getLatitude();
            r[1][i] = pts.get(i).getLongitude();
        }
        return r;
    }

    private void computeRoutes() {
        if (router == null) {
            Toast.makeText(this, "No routing data in this build", Toast.LENGTH_LONG).show();
            return;
        }
        if (destPt == null) return;
        double[][] pts = routePoints(null);
        if (pts == null) {
            Toast.makeText(this, "No GPS fix yet – long-press the map and choose \"Set as start\"", Toast.LENGTH_LONG).show();
            return;
        }
        routePanel.setVisibility(View.VISIBLE);
        routeTitle.setText("Finding routes…");
        altContainer.removeAllViews();
        findViewById(R.id.routeButtons).setVisibility(View.GONE);
        final RouteOptions base = savedOptions();
        bg.execute(() -> {
            List<Route> found = new ArrayList<>();
            List<String> names = new ArrayList<>();
            String error = null;
            RouteOptions withNH = base.copy();
            withNH.avoidNH = false;
            RouteOptions noNH = base.copy();
            noNH.avoidNH = true;
            String mode = RouteOptions.MODE_NAMES[base.mode];
            try {
                Route a = router.route(pts[0], pts[1], withNH);
                found.add(a);
                names.add(mode + " · NH allowed");
                Route b = router.route(pts[0], pts[1], noNH);
                if (Math.abs(b.distanceM - a.distanceM) > 200 || Math.abs(b.nhM - a.nhM) > 200) {
                    found.add(b);
                    names.add(mode + " · avoid NH");
                }
            } catch (Router.NoRouteException e) {
                error = e.getMessage();
            } catch (Exception e) {
                Log.e(TAG, "route", e);
                error = "Routing failed: " + e;
            }
            final String err = error;
            ui.post(() -> {
                if (isFinishing()) return;
                routes.clear();
                routeNames.clear();
                routes.addAll(found);
                routeNames.addAll(names);
                if (err != null || routes.isEmpty()) {
                    routeTitle.setText(err != null ? err : "No route");
                    altContainer.removeAllViews();
                    drawRoutes();
                    findViewById(R.id.routeButtons).setVisibility(View.VISIBLE);
                    findViewById(R.id.btnStart).setVisibility(View.GONE);
                    return;
                }
                selected = (base.avoidNH && routes.size() > 1) ? 1 : 0;
                showRoutes(true);
            });
        });
    }

    private void showRoutes(boolean zoomToFit) {
        routeTitle.setText(routes.size() > 1 ? "Choose your route – tap to select" : "Route  (" + savedOptions().describe() + ")");
        altContainer.removeAllViews();
        for (int i = 0; i < routes.size(); i++) {
            final int idx = i;
            Route r = routes.get(i);
            TextView tv = new TextView(this);
            tv.setPadding(dp(12), dp(8), dp(12), dp(8));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tv.setTextColor(0xFF222222);
            String head = (i == selected ? "● " : "○ ") + routeNames.get(i) + "  —  "
                    + GeoUtil.formatDistance(r.distanceM) + ", " + GeoUtil.formatDuration(r.timeS);
            String body = String.format(Locale.US, "NH %.0f km · SH %.0f km · other %.0f km",
                    r.nhM / 1000, r.shM / 1000, Math.max(0, r.distanceM - r.nhM - r.shM) / 1000);
            List<String> extra = new ArrayList<>();
            if (r.curvyM > 1000) extra.add(String.format(Locale.US, "🌀 twisty %.0f km", r.curvyM / 1000));
            if (r.unpavedM > 300) extra.add(String.format(Locale.US, "kachcha %.1f km", r.unpavedM / 1000));
            if (r.tollM > 300) extra.add(String.format(Locale.US, "toll %.0f km", r.tollM / 1000));
            if (r.fordM > 0) extra.add("⚠ water crossing");
            if (!extra.isEmpty()) body += "\n" + android.text.TextUtils.join(" · ", extra);
            String roads = r.mainRoads(4);
            if (!roads.isEmpty()) body += "\nvia " + roads;
            tv.setText(head + "\n" + body);
            tv.setTypeface(null, i == selected ? Typeface.BOLD : Typeface.NORMAL);
            tv.setBackgroundResource(i == selected ? R.drawable.bg_card_selected : R.drawable.bg_card_plain);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> {
                selected = idx;
                showRoutes(false);
            });
            altContainer.addView(tv);
        }
        findViewById(R.id.routeButtons).setVisibility(View.VISIBLE);
        findViewById(R.id.btnStart).setVisibility(View.VISIBLE);
        drawRoutes();
        if (zoomToFit) zoomTo(routes.get(selected));
    }

    private static Feature line(Route r) {
        List<Point> pts = new ArrayList<>(r.size());
        for (int i = 0; i < r.size(); i++) pts.add(Point.fromLngLat(r.lons[i], r.lats[i]));
        return Feature.fromGeometry(LineString.fromLngLats(pts));
    }

    private static FeatureCollection empty() {
        return FeatureCollection.fromFeatures(new ArrayList<>());
    }

    private void drawRoutes() {
        if (srcMain == null) return;
        srcMain.setGeoJson(routes.isEmpty() ? empty() : FeatureCollection.fromFeature(line(routes.get(selected))));
        srcAlt.setGeoJson(routes.size() > 1 && !navigating ? FeatureCollection.fromFeature(line(routes.get(1 - selected))) : empty());
    }

    private void zoomTo(Route r) {
        if (map == null || r.size() < 2) return;
        LatLngBounds.Builder b = new LatLngBounds.Builder();
        for (int i = 0; i < r.size(); i += Math.max(1, r.size() / 300)) b.include(new LatLng(r.lats[i], r.lons[i]));
        b.include(new LatLng(r.lats[r.size() - 1], r.lons[r.size() - 1]));
        stopFollowing();
        try {
            int panel = routePanel.getHeight() > 0 ? routePanel.getHeight() : dp(260);
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), dp(50), dp(130), dp(80), panel + dp(40)), 800);
        } catch (Exception e) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(r.lats[0], r.lons[0]), 14));
        }
    }

    private void clearRoute() {
        if (navigating) stopRide();
        routes.clear();
        routeNames.clear();
        startPt = destPt = null;
        vias.clear();
        updatePins();
        drawRoutes();
        routePanel.setVisibility(View.GONE);
    }

    private void showRouteOptions() {
        RouteOptions o = savedOptions();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        CheckBox nh = check(box, "Prefer avoiding National Highways (NH)", o.avoidNH);
        CheckBox sh = check(box, "Avoid State Highways (SH)", o.avoidSH);
        CheckBox main = check(box, "Avoid all main roads (big & busy)", o.avoidMainRoads);
        CheckBox small = check(box, "Avoid very small lanes & tracks", o.avoidSmallRoads);
        CheckBox kachcha = check(box, "Avoid kachcha / unpaved roads", o.avoidUnpaved);
        CheckBox fords = check(box, "Avoid water crossings / roads that flood (monsoon)", o.avoidFords);
        CheckBox cities = check(box, "Avoid busy city & town centres", o.avoidCities);
        CheckBox toll = check(box, "Avoid toll roads", o.avoidToll);
        CheckBox exp = check(box, "Allow expressways (two-wheelers usually not allowed)", !o.avoidExpressway);
        TextView note = new TextView(this);
        note.setText("Riding style (Fastest / Curvy / Extra curvy / Shortest) is chosen with the chips above the routes. "
                + "Avoid = soft: the road is still used if there is no other way.");
        note.setTextColor(0xFF666666);
        note.setPadding(0, dp(8), 0, 0);
        box.addView(note);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle("Avoid roads").setView(sv)
                .setPositiveButton("Apply", (d, w) -> {
                    o.avoidNH = nh.isChecked();
                    o.avoidSH = sh.isChecked();
                    o.avoidMainRoads = main.isChecked();
                    o.avoidSmallRoads = small.isChecked();
                    o.avoidUnpaved = kachcha.isChecked();
                    o.avoidFords = fords.isChecked();
                    o.avoidCities = cities.isChecked();
                    o.avoidToll = toll.isChecked();
                    o.avoidExpressway = !exp.isChecked();
                    storeOptions(o);
                    if (destPt != null) computeRoutes();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private CheckBox check(LinearLayout parent, String text, boolean checked) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setChecked(checked);
        parent.addView(c);
        return c;
    }

    // ------------------------------------------------------------------ saved trips

    private String placeName(LatLng p) {
        if (p == null) return "My location";
        if (places == null) return String.format(Locale.US, "%.3f,%.3f", p.getLatitude(), p.getLongitude());
        PlacesIndex.Place best = null;
        double bestD = 1e18;
        for (String cat : new String[]{"city", "town", "village"}) {
            List<PlacesIndex.Place> l = places.nearest(cat, p.getLatitude(), p.getLongitude(), 1);
            if (l.isEmpty()) continue;
            PlacesIndex.Place pl = l.get(0);
            double d = GeoUtil.distance(p.getLatitude(), p.getLongitude(), pl.lat, pl.lon);
            double limit = cat.equals("city") ? 12000 : cat.equals("town") ? 5000 : 3000;
            if (d < limit && d < bestD) {
                best = pl;
                bestD = d;
            }
        }
        return best != null ? (best.name.isEmpty() ? best.nameHi : best.name)
                : String.format(Locale.US, "%.3f,%.3f", p.getLatitude(), p.getLongitude());
    }

    private void saveTrip() {
        if (destPt == null || routes.isEmpty()) return;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(placeName(startPt) + " → " + placeName(destPt));
        input.setSelectAllOnFocus(true);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this).setTitle("Save this trip").setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    TripStore.Trip t = new TripStore.Trip();
                    t.name = input.getText().toString().trim().isEmpty() ? "Trip" : input.getText().toString().trim();
                    t.created = System.currentTimeMillis();
                    t.start = startPt == null ? null : new double[]{startPt.getLatitude(), startPt.getLongitude()};
                    for (LatLng v : vias) t.vias.add(new double[]{v.getLatitude(), v.getLongitude()});
                    t.dest = new double[]{destPt.getLatitude(), destPt.getLongitude()};
                    Route r = routes.get(selected);
                    t.options = r.options.encode();
                    t.distanceM = r.distanceM;
                    t.timeS = r.timeS;
                    trips.add(t);
                    Toast.makeText(this, "Trip saved – open it from 🧭 or menu ⋮ → My trips", Toast.LENGTH_LONG).show();
                })
                .setNeutralButton("Export GPX", (d, w) -> exportGpx(input.getText().toString(), routes.get(selected)))
                .setNegativeButton("Cancel", null).show();
    }

    private void exportGpx(String name, Route r) {
        String safe = name.replaceAll("[^A-Za-z0-9 _-]", "").trim().replace(' ', '_');
        if (safe.isEmpty()) safe = "route";
        String where = TripStore.saveToDownloads(this, safe + ".gpx", TripStore.routeGpx(name, r));
        Toast.makeText(this, where != null ? "Saved: " + where : "Could not save GPX", Toast.LENGTH_LONG).show();
    }

    private void showTrips() {
        List<TripStore.Trip> all = trips.all();
        if (all.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("My trips")
                    .setMessage("No saved trips yet.\n\nPlan a route and tap 💾 to save it.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] items = new String[all.size()];
        for (int i = 0; i < all.size(); i++) {
            TripStore.Trip t = all.get(i);
            items[i] = t.name + "\n" + GeoUtil.formatDistance(t.distanceM) + " · " + GeoUtil.formatDuration(t.timeS)
                    + " · " + RouteOptions.decode(t.options).describe();
        }
        new AlertDialog.Builder(this).setTitle("My trips (tap to open)")
                .setItems(items, (d, which) -> tripActions(all.get(which)))
                .setNegativeButton("Close", null).show();
    }

    private void tripActions(TripStore.Trip t) {
        String[] items = {"🧭 Open & show route", "▶ Open & start ride", "✏ Rename", "🗑 Delete"};
        new AlertDialog.Builder(this).setTitle(t.name).setItems(items, (d, w) -> {
            switch (w) {
                case 0:
                case 1:
                    openTrip(t, w == 1);
                    break;
                case 2: {
                    EditText input = new EditText(this);
                    input.setText(t.name);
                    new AlertDialog.Builder(this).setTitle("Rename").setView(input)
                            .setPositiveButton("OK", (d2, w2) -> {
                                t.name = input.getText().toString().trim();
                                trips.save();
                            }).setNegativeButton("Cancel", null).show();
                    break;
                }
                default:
                    new AlertDialog.Builder(this).setMessage("Delete \"" + t.name + "\"?")
                            .setPositiveButton("Delete", (d2, w2) -> trips.remove(t))
                            .setNegativeButton("Cancel", null).show();
            }
        }).show();
    }

    private void openTrip(TripStore.Trip t, boolean ride) {
        if (navigating) stopRide();
        startPt = t.start == null ? null : new LatLng(t.start[0], t.start[1]);
        vias.clear();
        for (double[] v : t.vias) vias.add(new LatLng(v[0], v[1]));
        destPt = new LatLng(t.dest[0], t.dest[1]);
        RouteOptions o = RouteOptions.decode(t.options);
        storeOptions(o);
        buildModeChips();
        updatePins();
        computeRoutes();
        if (ride) ui.postDelayed(() -> {
            if (!routes.isEmpty()) startRide();
        }, 2500);
    }

    // ------------------------------------------------------------------ navigation

    private void startRide() {
        if (routes.isEmpty()) return;
        navigating = true;
        follow = true;
        headingUp = false;
        offRouteCount = 0;
        navIndex = 0;
        alertTurnPoint = -1;
        alertStage = 0;
        arrivedAlerted = false;
        updateLocateButton();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // keeps GPS + turn beeps running with the screen off / phone in pocket
        TrackService.startNav(this);
        routePanel.setVisibility(View.GONE);
        navPanel.setVisibility(View.VISIBLE);
        drawRoutes();
        navMain.setText("Ride started");
        navSub.setText(myLoc == null ? "Waiting for GPS…" : "");
        if (lc != null) {
            lc.setRenderMode(RenderMode.GPS);
            // close-up, tilted, map turns with the riding direction
            lc.setCameraMode(CameraMode.TRACKING_GPS, 900, 16.5, null, 45.0, null);
        }
        if (myLoc != null) updateNavigation(myLoc);
        navPanel.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("Stop the ride navigation?")
                .setPositiveButton("Stop", (d, w) -> stopRide()).setNegativeButton("Continue", null).show());
        if (!TrackRecorder.get().isRecording() && prefs.getBoolean("autoRecord", true)) {
            new AlertDialog.Builder(this).setMessage("Also record this ride (GPX track)?")
                    .setPositiveButton("Record", (d, w) -> toggleRecording())
                    .setNegativeButton("No", (d, w) -> prefs.edit().putBoolean("autoRecord", false).apply()).show();
        }
    }

    private void stopRide() {
        navigating = false;
        TrackService.stopNav(this);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        navPanel.setVisibility(View.GONE);
        if (lc != null) {
            lc.setRenderMode(RenderMode.COMPASS);
            lc.setCameraMode(follow ? CameraMode.TRACKING : CameraMode.NONE);
            lc.tiltWhileTracking(0);
        }
        if (!routes.isEmpty()) {
            routePanel.setVisibility(View.VISIBLE);
            showRoutes(false);
        }
    }

    private void updateNavigation(Location l) {
        if (routes.isEmpty() || rerouting) return;
        Route r = routes.get(selected);
        double lat = l.getLatitude(), lon = l.getLongitude();
        int bestI = -1;
        double bestD = Double.MAX_VALUE, bestT = 0;
        int from = Math.max(0, navIndex - 30), to = Math.min(r.size() - 1, navIndex + 600);
        for (int pass = 0; pass < 2 && (bestI < 0 || bestD > 80); pass++) {
            for (int i = from; i < to; i++) {
                double[] p = GeoUtil.project(lat, lon, r.lats[i], r.lons[i], r.lats[i + 1], r.lons[i + 1]);
                if (p[1] < bestD) {
                    bestD = p[1];
                    bestI = i;
                    bestT = p[0];
                }
            }
            from = 0;
            to = r.size() - 1;
        }
        if (bestI < 0) return;
        navIndex = bestI;
        double done = r.cumDist[bestI] + bestT * (r.cumDist[bestI + 1] - r.cumDist[bestI]);
        double remaining = Math.max(0, r.distanceM - done);
        double remainingTime = r.distanceM > 0 ? r.timeS * remaining / r.distanceM : 0;

        if (remaining < 40 || GeoUtil.distance(lat, lon, r.lats[r.size() - 1], r.lons[r.size() - 1]) < 30) {
            navMain.setText("🏁 You have arrived!");
            navSub.setText("");
            if (!arrivedAlerted) {
                arrivedAlerted = true;
                alerts.arrived();
            }
            ui.postDelayed(() -> {
                if (navigating) stopRide();
            }, 8000);
            return;
        }
        float acc = l.hasAccuracy() ? l.getAccuracy() : 50;
        if (bestD > Math.max(60, acc * 1.5)) {
            offRouteCount++;
            if (offRouteCount >= 4) {
                alerts.offRoute();
                reroute(l);
                return;
            }
        } else {
            offRouteCount = 0;
        }
        Route.Step cur = null;
        for (Route.Step s : r.steps) {
            if (s.startPoint <= bestI) cur = s;
            else break;
        }
        String left = GeoUtil.formatDistance(remaining) + " · " + GeoUtil.formatDuration(remainingTime) + " left";
        String on = "on " + (cur == null || cur.name == null ? "local road" : cur.name);
        Route.Turn turn = r.nextTurn(done);
        if (turn != null) {
            double toTurn = Math.max(0, r.cumDist[turn.point] - done);
            float speed = l.hasSpeed() ? l.getSpeed() : 8f;
            double early = Math.min(400, Math.max(150, speed * 15));
            double now = Math.max(40, speed * 4);
            if (turn.point != alertTurnPoint) {
                alertTurnPoint = turn.point;
                alertStage = 0;
            }
            if (toTurn <= now && alertStage < 2) {
                playTurn(turn);
                alertStage = 2;
            } else if (toTurn <= early && toTurn > now && alertStage < 1) {
                playTurn(turn);
                alertStage = 1;
            }
            navMain.setText(turn.arrow() + " " + turn.text() + " in " + GeoUtil.formatDistance(toTurn));
            navSub.setText((turn.onto != null ? "onto " + turn.onto + "\n" : "") + left + "\n" + on
                    + (offRouteCount > 0 ? "\n⚠ Off route?" : ""));
        } else {
            navMain.setText("⬆ Straight on to the destination");
            navSub.setText(left + "\n" + on + (offRouteCount > 0 ? "\n⚠ Off route?" : ""));
        }
    }

    private void playTurn(Route.Turn t) {
        if (t.isRight()) alerts.right();
        else alerts.left();
    }

    private void reroute(Location l) {
        if (router == null || destPt == null) return;
        rerouting = true;
        navSub.setText("Off route – finding a new way…");
        RouteOptions o = routes.get(selected).options;
        String name = routeNames.get(selected);
        double[][] pts = routePoints(l);
        bg.execute(() -> {
            Route nr = null;
            try {
                nr = router.route(pts[0], pts[1], o);
            } catch (Exception e) {
                Log.w(TAG, "reroute", e);
            }
            final Route fr = nr;
            ui.post(() -> {
                rerouting = false;
                offRouteCount = 0;
                if (fr == null || !navigating) return;
                routes.clear();
                routeNames.clear();
                routes.add(fr);
                routeNames.add(name);
                selected = 0;
                navIndex = 0;
                vias.clear();
                updatePins();
                drawRoutes();
                Toast.makeText(this, "New route: " + GeoUtil.formatDistance(fr.distanceM), Toast.LENGTH_SHORT).show();
            });
        });
    }

    // ------------------------------------------------------------------ track recording

    private void toggleRecording() {
        if (TrackRecorder.get().isRecording()) {
            new AlertDialog.Builder(this).setTitle("Stop recording?")
                    .setMessage(trackText())
                    .setPositiveButton("Stop & save", (d, w) -> TrackService.stop(this))
                    .setNegativeButton("Keep recording", null).show();
            return;
        }
        if (!hasLocationPermission()) {
            startLocation();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
        TrackService.start(this);
        Toast.makeText(this, "Recording ride – works with screen off", Toast.LENGTH_SHORT).show();
    }

    private String trackText() {
        TrackRecorder t = TrackRecorder.get();
        long min = t.elapsedMs() / 60000;
        double hours = t.movingMs() / 3600000.0;
        String avg = hours > 0.01 ? String.format(Locale.US, " · avg %.0f km/h", t.distanceM() / 1000 / hours) : "";
        return GeoUtil.formatDistance(t.distanceM()) + " · " + (min / 60) + "h " + (min % 60) + "m"
                + avg + String.format(Locale.US, " · max %.0f km/h", t.maxSpeedKmh());
    }

    @Override
    public void onTrackChanged() {
        TrackRecorder t = TrackRecorder.get();
        boolean rec = t.isRecording();
        btnRecord.setBackgroundResource(rec ? R.drawable.bg_fab_rec : R.drawable.bg_fab);
        btnRecord.setTextColor(rec ? Color.WHITE : 0xFFD32F2F);
        btnRecord.setText(rec ? "■" : "⏺");
        recordInfo.setVisibility(rec ? View.VISIBLE : View.GONE);
        if (rec) recordInfo.setText("⏺ " + trackText());
        if (srcTrack != null) {
            List<double[]> pts = rec ? t.points() : new ArrayList<>();
            if (pts.size() >= 2) {
                List<Point> ps = new ArrayList<>(pts.size());
                for (double[] p : pts) ps.add(Point.fromLngLat(p[1], p[0]));
                srcTrack.setGeoJson(FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(ps))));
            } else {
                srcTrack.setGeoJson(empty());
            }
        }
    }

    // ------------------------------------------------------------------ search

    private void showSearch() {
        if (places == null) {
            Toast.makeText(this, "No search data in this build", Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), 0);
        EditText input = new EditText(this);
        input.setHint("Village, town, place…");
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        box.addView(input);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(0, dp(6), 0, dp(6));
        hs.addView(chips);
        box.addView(hs);

        ListView list = new ListView(this);
        list.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));
        box.addView(list);
        final List<PlacesIndex.Place> results = new ArrayList<>();
        final double[] ref = refPoint();
        ArrayAdapter<PlacesIndex.Place> adapter = new ArrayAdapter<PlacesIndex.Place>(this,
                android.R.layout.simple_list_item_2, android.R.id.text1, results) {
            @Override
            public View getView(int pos, View convert, ViewGroup parent) {
                View v = super.getView(pos, convert, parent);
                PlacesIndex.Place p = getItem(pos);
                if (p != null) {
                    ((TextView) v.findViewById(android.R.id.text1)).setText(p.title());
                    String dist = GeoUtil.formatDistance(GeoUtil.distance(ref[0], ref[1], p.lat, p.lon));
                    ((TextView) v.findViewById(android.R.id.text2)).setText(p.label() + " · " + dist);
                }
                return v;
            }
        };
        list.setAdapter(adapter);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(box).setNegativeButton("Close", null).create();

        for (String[] q : PlacesIndex.QUICK) {
            TextView chip = new TextView(this);
            chip.setText(q[1]);
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            chip.setTextColor(0xFF1565C0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                results.clear();
                results.addAll(places.nearest(q[0], ref[0], ref[1], 60));
                adapter.notifyDataSetChanged();
                if (results.isEmpty()) Toast.makeText(this, "None found nearby", Toast.LENGTH_SHORT).show();
            });
            chips.addView(chip);
        }
        final Runnable[] pending = new Runnable[1];
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (pending[0] != null) ui.removeCallbacks(pending[0]);
                String q = s.toString();
                pending[0] = () -> {
                    results.clear();
                    if (q.trim().length() >= 2) results.addAll(places.search(q, ref[0], ref[1], 80));
                    adapter.notifyDataSetChanged();
                };
                ui.postDelayed(pending[0], 250);
            }
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            showPlace(results.get(position));
        });
        dialog.show();
        input.requestFocus();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
    }

    private double[] refPoint() {
        if (myLoc != null) return new double[]{myLoc.getLatitude(), myLoc.getLongitude()};
        if (map != null && map.getCameraPosition().target != null) {
            LatLng c = map.getCameraPosition().target;
            return new double[]{c.getLatitude(), c.getLongitude()};
        }
        return new double[]{BHOPAL.getLatitude(), BHOPAL.getLongitude()};
    }

    private void showPlace(PlacesIndex.Place p) {
        LatLng ll = new LatLng(p.lat, p.lon);
        searchPt = ll;
        updatePins();
        stopFollowing();
        if (map != null) {
            double z = p.category.equals("city") ? 12 : p.category.equals("town") ? 13 : 15;
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(ll, z), 900);
        }
        double[] ref = refPoint();
        new AlertDialog.Builder(this).setTitle(p.title())
                .setMessage(p.label() + "\n" + GeoUtil.formatDistance(GeoUtil.distance(ref[0], ref[1], p.lat, p.lon)) + " away (straight line)")
                .setPositiveButton("🧭 Route here", (d, w) -> {
                    destPt = ll;
                    searchPt = null;
                    updatePins();
                    computeRoutes();
                })
                .setNeutralButton("Set as start", (d, w) -> {
                    startPt = ll;
                    searchPt = null;
                    updatePins();
                    if (destPt != null) computeRoutes();
                })
                .setNegativeButton("Close", null).show();
    }

    // ------------------------------------------------------------------ menu

    private void showMenu() {
        String[] items = {
                "📂 My saved trips",
                satellite ? "🗺 Switch to normal map" : "🛰 Switch to satellite view",
                "🛰 Save satellite for my route area (offline)",
                "🛰 Save satellite for the visible area (offline)",
                "🗑 Delete saved satellite",
                "🔊 Turn beeps: " + (alerts.soundOn() ? "ON (tap to turn off)" : "OFF (tap to turn on)"),
                "📳 Turn vibration: " + (alerts.vibrateOn() ? "ON (tap to turn off)" : "OFF (tap to turn on)"),
                "🔔 Test LEFT turn alert (beep … beep)",
                "🔔 Test RIGHT turn alert (beep-beep-beep)",
                "🗺 Clear route & markers",
                "📁 Where are my rides saved?",
                "ℹ About & offline data",
        };
        new AlertDialog.Builder(this).setItems(items, (d, which) -> {
            switch (which) {
                case 0: showTrips(); break;
                case 1: setSatellite(!satellite, true); break;
                case 2: saveSatellite(true); break;
                case 3: saveSatellite(false); break;
                case 4:
                    OfflineSatellite.deleteAll(this, () -> Toast.makeText(this, "Saved satellite deleted", Toast.LENGTH_SHORT).show());
                    break;
                case 5: prefs.edit().putBoolean("alertSound", !alerts.soundOn()).apply(); break;
                case 6: prefs.edit().putBoolean("alertVibrate", !alerts.vibrateOn()).apply(); break;
                case 7: alerts.left(); break;
                case 8: alerts.right(); break;
                case 9:
                    clearRoute();
                    searchPt = null;
                    updatePins();
                    break;
                case 10:
                    new AlertDialog.Builder(this).setTitle("Saved rides & routes (GPX)")
                            .setMessage("Recorded rides and exported routes are saved as GPX files in:\n\nDownload/BikeRidingMP/\n\n"
                                    + "Open them in any GPX app or share on WhatsApp.")
                            .setPositiveButton("OK", null).show();
                    break;
                default:
                    showAbout();
            }
        }).show();
    }

    private void showAbout() {
        String v = DataStore.assetVersion(this);
        StringBuilder sb = new StringBuilder();
        sb.append("Offline maps, GPS tracking and trip planning for Madhya Pradesh. No internet needed.\n\n");
        sb.append("Map data version: ").append(v.isEmpty() ? "none" : v).append('\n');
        if (router != null) sb.append("Road network: ").append(router.graph().nodeCount).append(" junctions\n");
        if (places != null) sb.append("Searchable places: ").append(places.size()).append('\n');
        sb.append("Saved trips: ").append(trips.all().size()).append('\n');
        sb.append("\nTips: 2 fingers to rotate & tilt the map. Tap the compass to face north. "
                + "Tap ◎ twice to make the map turn with you.\n"
                + "Turn alerts while riding (phone in pocket): LEFT = beep … beep, RIGHT = beep-beep-beep "
                + "(same pattern as vibration), once ~300 m before and again just before the turn.\n");
        sb.append("\nMap data © OpenStreetMap contributors (ODbL), © OpenMapTiles. Style: OpenFreeMap Liberty. "
                + "Satellite: Sentinel-2 cloudless – s2maps.eu by EOX IT Services GmbH (contains modified Copernicus "
                + "Sentinel data). Map engine: MapLibre.");
        new AlertDialog.Builder(this).setTitle(getString(R.string.app_name)).setMessage(sb.toString())
                .setPositiveButton("OK", null).show();
    }

    // ------------------------------------------------------------------ drawing helpers

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private Bitmap drawPin(int color, String letter) {
        int w = dp(30), h = dp(38);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float r = w / 2f - dp(2);
        Path path = new Path();
        path.addCircle(w / 2f, r + dp(2), r, Path.Direction.CW);
        path.moveTo(w / 2f - r * 0.6f, r + dp(2) + r * 0.7f);
        path.lineTo(w / 2f, h);
        path.lineTo(w / 2f + r * 0.6f, r + dp(2) + r * 0.7f);
        path.close();
        p.setColor(color);
        c.drawPath(path, p);
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(dp(2));
        p.setColor(Color.WHITE);
        c.drawCircle(w / 2f, r + dp(2), r - dp(1), p);
        p.setStyle(android.graphics.Paint.Style.FILL);
        p.setTextSize(dp(14));
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        c.drawText(letter, w / 2f, r + dp(2) + dp(5), p);
        return bmp;
    }
}
