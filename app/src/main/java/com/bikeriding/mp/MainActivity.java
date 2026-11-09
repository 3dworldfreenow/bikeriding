package com.bikeriding.mp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.bikeriding.mp.routing.GeoUtil;
import com.bikeriding.mp.routing.Graph;
import com.bikeriding.mp.routing.Route;
import com.bikeriding.mp.routing.RouteOptions;
import com.bikeriding.mp.routing.Router;

import org.mapsforge.core.graphics.Bitmap;
import org.mapsforge.core.graphics.Cap;
import org.mapsforge.core.graphics.Join;
import org.mapsforge.core.graphics.Paint;
import org.mapsforge.core.graphics.Style;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Point;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.layer.Layer;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.overlay.Circle;
import org.mapsforge.map.layer.overlay.Marker;
import org.mapsforge.map.layer.overlay.Polyline;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.rendertheme.XmlRenderTheme;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements LocationListener, TrackRecorder.Listener {
    private static final String TAG = "BikeRidingMP";
    private static final LatLong BHOPAL = new LatLong(23.2599, 77.4126);
    private static final int REQ_LOCATION = 1, REQ_NOTIFY = 2;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // views
    private MapView mapView;
    private View loading, routePanel, navPanel;
    private TextView loadingText, speedView, recordInfo, routeTitle, navMain, navSub, btnRecord, btnLocate;
    private LinearLayout altContainer;

    // map
    private TileCache tileCache;
    private TileRendererLayer tileLayer;
    private Polyline altLine, mainLine, trackLine;
    private Circle accuracyCircle;
    private Marker meMarker, startMarker, destMarker, searchMarker;
    private final List<Marker> viaMarkers = new ArrayList<>();
    private Bitmap meBitmap;

    // data
    private Router router;
    private PlacesIndex places;

    // state
    private LocationManager lm;
    private Location myLoc;
    private boolean follow = true;
    private LatLong startPt, destPt;
    private final List<LatLong> vias = new ArrayList<>();
    private final List<Route> routes = new ArrayList<>();
    private final List<String> routeNames = new ArrayList<>();
    private int selected = 0;
    private boolean navigating, rerouting;
    private int offRouteCount, navIndex;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AndroidGraphicFactory.createInstance(getApplication());
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        setContentView(R.layout.activity_main);

        mapView = findViewById(R.id.mapView);
        loading = findViewById(R.id.loading);
        loadingText = findViewById(R.id.loadingText);
        speedView = findViewById(R.id.speedView);
        recordInfo = findViewById(R.id.recordInfo);
        routePanel = findViewById(R.id.routePanel);
        routeTitle = findViewById(R.id.routeTitle);
        altContainer = findViewById(R.id.altContainer);
        navPanel = findViewById(R.id.navPanel);
        navMain = findViewById(R.id.navMain);
        navSub = findViewById(R.id.navSub);
        btnRecord = findViewById(R.id.btnRecord);
        btnLocate = findViewById(R.id.btnLocate);

        applyInsets();

        mapView.setClickable(true);
        mapView.getMapScaleBar().setVisible(true);
        mapView.setBuiltInZoomControls(false);
        mapView.setZoomLevelMin((byte) 5);
        mapView.setZoomLevelMax((byte) 20);
        mapView.setCenter(new LatLong(prefs.getFloat("lat", (float) BHOPAL.latitude), prefs.getFloat("lon", (float) BHOPAL.longitude)));
        mapView.setZoomLevel((byte) prefs.getInt("zoom", 8));
        mapView.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_MOVE && follow) {
                follow = false;
                updateLocateButton();
            }
            return false;
        });

        findViewById(R.id.searchBox).setOnClickListener(v -> showSearch());
        findViewById(R.id.menuBtn).setOnClickListener(v -> showMenu());
        btnLocate.setOnClickListener(v -> onLocateClicked());
        btnRecord.setOnClickListener(v -> toggleRecording());
        findViewById(R.id.btnRoute).setOnClickListener(v -> onRouteButton());
        findViewById(R.id.btnOptions).setOnClickListener(v -> showRouteOptions());
        findViewById(R.id.btnStart).setOnClickListener(v -> startRide());
        findViewById(R.id.btnClear).setOnClickListener(v -> clearRoute());

        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
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
            return insets;
        });
    }

    private void loadData() {
        loading.setVisibility(View.VISIBLE);
        bg.execute(() -> {
            File map = null;
            String error = null;
            try {
                map = DataStore.prepareMap(this, p -> ui.post(() ->
                        loadingText.setText("Unpacking offline map of Madhya Pradesh…\n(first start only)\n" + p + " MB")));
            } catch (Exception e) {
                Log.e(TAG, "map", e);
                error = "Map error: " + e.getMessage();
            }
            try {
                Graph g = DataStore.openGraph(this, p -> ui.post(() ->
                        loadingText.setText("Unpacking road network for trip planning…\n(first start only)\n" + p + " MB")));
                if (g != null) router = new Router(g);
            } catch (Exception e) {
                Log.e(TAG, "graph", e);
            }
            try {
                places = DataStore.openPlaces(this);
            } catch (Exception e) {
                Log.e(TAG, "places", e);
            }
            final File mapFile = map;
            final String err = error;
            ui.post(() -> onDataReady(mapFile, err));
        });
    }

    private void onDataReady(File mapFile, String error) {
        if (isFinishing() || isDestroyed()) return;
        loading.setVisibility(View.GONE);
        if (mapFile != null && mapFile.exists()) {
            try {
                tileCache = AndroidUtil.createTileCache(this, "mapcache", mapView.getModel().displayModel.getTileSize(),
                        1f, mapView.getModel().frameBufferModel.getOverdrawFactor());
                String lang = prefs.getString("lang", "en");
                MapFile mf = new MapFile(mapFile, lang);
                tileLayer = new TileRendererLayer(tileCache, mf, mapView.getModel().mapViewPosition,
                        AndroidGraphicFactory.INSTANCE) {
                    @Override
                    public boolean onLongPress(LatLong tapLatLong, Point layerXY, Point tapXY) {
                        showPointMenu(tapLatLong);
                        return true;
                    }
                };
                XmlRenderTheme theme = findTheme();
                if (theme != null) tileLayer.setXmlRenderTheme(theme);
                mapView.getLayerManager().getLayers().add(tileLayer);
            } catch (Exception e) {
                Log.e(TAG, "map layer", e);
                error = "Cannot open map: " + e.getMessage();
            }
        }
        addOverlayLayers();
        if (tileLayer == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Offline map missing")
                    .setMessage((error != null ? error + "\n\n" : "")
                            + "This APK was built without map data. Download the full APK from the GitHub "
                            + "Releases page (built by GitHub Actions), or run tools/build_data.sh before building.")
                    .setPositiveButton("OK", null).show();
        } else if (router == null) {
            Toast.makeText(this, "Routing data missing – trip planning disabled", Toast.LENGTH_LONG).show();
        }
        TrackRecorder.get().addListener(this);
        onTrackChanged();
    }

    /** Motorider theme (good for bikers) if this mapsforge version has it, else the default theme. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static XmlRenderTheme findTheme() {
        String[] classes = {"org.mapsforge.map.rendertheme.internal.MapsforgeThemes",
                "org.mapsforge.map.rendertheme.InternalRenderTheme"};
        String[] names = {"MOTORIDER", "DEFAULT", "OSMARENDER"};
        for (String name : names) {
            for (String cls : classes) {
                try {
                    Class c = Class.forName(cls);
                    return (XmlRenderTheme) Enum.valueOf(c, name);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private Paint stroke(int color, float widthDp) {
        Paint p = AndroidGraphicFactory.INSTANCE.createPaint();
        p.setColor(color);
        p.setStrokeWidth(dp(widthDp));
        p.setStyle(Style.STROKE);
        p.setStrokeCap(Cap.ROUND);
        p.setStrokeJoin(Join.ROUND);
        return p;
    }

    private void addOverlayLayers() {
        altLine = new Polyline(stroke(0xAA78909C, 6), AndroidGraphicFactory.INSTANCE);
        mainLine = new Polyline(stroke(0xDD1565C0, 7), AndroidGraphicFactory.INSTANCE);
        trackLine = new Polyline(stroke(0xCCD32F2F, 4), AndroidGraphicFactory.INSTANCE);
        mapView.getLayerManager().getLayers().add(altLine);
        mapView.getLayerManager().getLayers().add(mainLine);
        mapView.getLayerManager().getLayers().add(trackLine);
        meBitmap = toBitmap(drawDot());
    }

    @Override
    protected void onResume() {
        super.onResume();
        startLocation();
    }

    @Override
    protected void onPause() {
        if (!navigating) stopLocation();
        LatLong c = mapView.getModel().mapViewPosition.getCenter();
        prefs.edit().putFloat("lat", (float) c.latitude).putFloat("lon", (float) c.longitude)
                .putInt("zoom", mapView.getModel().mapViewPosition.getZoomLevel()).apply();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopLocation();
        TrackRecorder.get().removeListener(this);
        bg.shutdownNow();
        mapView.destroyAll();
        AndroidGraphicFactory.clearResourceMemoryCache();
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

    // ------------------------------------------------------------------ location

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
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
        // prefer GPS: ignore network fixes when a recent GPS fix exists
        if (LocationManager.NETWORK_PROVIDER.equals(l.getProvider()) && myLoc != null
                && LocationManager.GPS_PROVIDER.equals(myLoc.getProvider()) && l.getTime() - myLoc.getTime() < 15000) {
            return;
        }
        myLoc = l;
        LatLong p = new LatLong(l.getLatitude(), l.getLongitude());
        if (meMarker == null && meBitmap != null) {
            Paint fill = AndroidGraphicFactory.INSTANCE.createPaint();
            fill.setColor(0x301565C0);
            fill.setStyle(Style.FILL);
            Paint st = stroke(0x601565C0, 1);
            accuracyCircle = new Circle(p, l.getAccuracy(), fill, st);
            meMarker = new Marker(p, meBitmap, 0, 0);
            mapView.getLayerManager().getLayers().add(accuracyCircle);
            mapView.getLayerManager().getLayers().add(meMarker);
        } else if (meMarker != null) {
            meMarker.setLatLong(p);
            accuracyCircle.setLatLong(p);
            accuracyCircle.setRadius(l.getAccuracy());
            meMarker.requestRedraw();
        }
        if (l.hasSpeed() && LocationManager.GPS_PROVIDER.equals(l.getProvider())) {
            speedView.setVisibility(View.VISIBLE);
            speedView.setText(String.format(Locale.US, "%d\nkm/h", Math.round(l.getSpeed() * 3.6)));
        }
        if (follow) {
            mapView.setCenter(p);
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

    private void onLocateClicked() {
        if (myLoc == null) {
            Toast.makeText(this, hasLocationPermission() ? "Waiting for GPS signal… (go under open sky)" :
                    "Location permission needed", Toast.LENGTH_SHORT).show();
            if (!hasLocationPermission()) startLocation();
            return;
        }
        follow = true;
        LatLong p = new LatLong(myLoc.getLatitude(), myLoc.getLongitude());
        mapView.setCenter(p);
        if (mapView.getModel().mapViewPosition.getZoomLevel() < 14) mapView.setZoomLevel((byte) 15);
        updateLocateButton();
    }

    private void updateLocateButton() {
        btnLocate.setBackgroundResource(follow ? R.drawable.bg_fab_active : R.drawable.bg_fab);
        btnLocate.setTextColor(follow ? Color.WHITE : 0xFF1565C0);
    }

    // ------------------------------------------------------------------ points & markers

    private void showPointMenu(LatLong p) {
        String[] items = {"🧭 Route here (from my location)", "🟢 Set as start", "🏁 Set as destination",
                "➕ Add stop on the way", "📍 What is here?"};
        new AlertDialog.Builder(this)
                .setTitle(String.format(Locale.US, "%.5f, %.5f", p.latitude, p.longitude))
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: startPt = null; destPt = p; vias.clear(); updatePointMarkers(); computeRoutes(); break;
                        case 1: startPt = p; updatePointMarkers(); if (destPt != null) computeRoutes(); break;
                        case 2: destPt = p; updatePointMarkers(); computeRoutes(); break;
                        case 3: vias.add(p); updatePointMarkers(); if (destPt != null) computeRoutes(); break;
                        default: whatIsHere(p);
                    }
                }).show();
    }

    private void whatIsHere(LatLong p) {
        StringBuilder sb = new StringBuilder();
        if (places != null) {
            for (String cat : new String[]{"city", "town", "village"}) {
                List<PlacesIndex.Place> l = places.nearest(cat, p.latitude, p.longitude, 1);
                if (!l.isEmpty()) {
                    PlacesIndex.Place pl = l.get(0);
                    sb.append(PlacesIndex.categoryLabel(cat)).append(": ").append(pl.title()).append(" – ")
                            .append(GeoUtil.formatDistance(GeoUtil.distance(p.latitude, p.longitude, pl.lat, pl.lon))).append('\n');
                }
            }
            List<PlacesIndex.Place> fuel = places.nearest("fuel", p.latitude, p.longitude, 1);
            if (!fuel.isEmpty()) {
                PlacesIndex.Place f = fuel.get(0);
                sb.append("Nearest petrol: ").append(f.title()).append(" – ")
                        .append(GeoUtil.formatDistance(GeoUtil.distance(p.latitude, p.longitude, f.lat, f.lon))).append('\n');
            }
        }
        if (router != null) {
            Router.Snap s = router.snap(p.latitude, p.longitude, 2000);
            if (s != null) {
                String name = router.graph().edgeName(s.edge);
                sb.append("Road: ").append(name == null ? "unnamed road" : name)
                        .append(" (").append(GeoUtil.formatDistance(s.offRoadM)).append(" away)\n");
            }
        }
        if (sb.length() == 0) sb.append("No data");
        new AlertDialog.Builder(this).setTitle("What is here").setMessage(sb.toString().trim())
                .setPositiveButton("OK", null).show();
    }

    private Marker addMarker(LatLong p, int color, String letter) {
        Marker m = new Marker(p, toBitmap(drawPin(color, letter)), 0, -dp(18));
        mapView.getLayerManager().getLayers().add(m);
        return m;
    }

    private void removeLayer(Layer l) {
        if (l != null) mapView.getLayerManager().getLayers().remove(l);
    }

    private void updatePointMarkers() {
        removeLayer(startMarker);
        removeLayer(destMarker);
        for (Marker m : viaMarkers) removeLayer(m);
        viaMarkers.clear();
        startMarker = destMarker = null;
        if (startPt != null) startMarker = addMarker(startPt, 0xFF2E7D32, "A");
        for (int i = 0; i < vias.size(); i++) viaMarkers.add(addMarker(vias.get(i), 0xFFF9A825, String.valueOf(i + 1)));
        if (destPt != null) destMarker = addMarker(destPt, 0xFFC62828, "B");
    }

    // ------------------------------------------------------------------ routing

    private void onRouteButton() {
        if (destPt == null) {
            new AlertDialog.Builder(this).setTitle("Plan a trip")
                    .setMessage("• Long-press on the map to choose where you want to go\n"
                            + "• Or search a village / town and tap \"Route here\"\n\n"
                            + "You will get two routes to compare: with National Highways (NH) and without NH. "
                            + "Use ⚙ Options to also avoid State Highways, toll or kachcha roads.")
                    .setPositiveButton("Search place", (d, w) -> showSearch())
                    .setNegativeButton("OK", null).show();
        } else {
            computeRoutes();
        }
    }

    private RouteOptions savedOptions() {
        RouteOptions o = new RouteOptions();
        o.fastest = prefs.getBoolean("fastest", true);
        o.avoidNH = prefs.getBoolean("avoidNH", false);
        o.avoidSH = prefs.getBoolean("avoidSH", false);
        o.avoidToll = prefs.getBoolean("avoidToll", false);
        o.avoidUnpaved = prefs.getBoolean("avoidUnpaved", false);
        o.avoidExpressway = !prefs.getBoolean("allowExpressway", false);
        return o;
    }

    private double[][] routePoints(Location from) {
        List<LatLong> pts = new ArrayList<>();
        if (from != null) pts.add(new LatLong(from.getLatitude(), from.getLongitude()));
        else if (startPt != null) pts.add(startPt);
        else if (myLoc != null) pts.add(new LatLong(myLoc.getLatitude(), myLoc.getLongitude()));
        else return null;
        if (from == null) pts.addAll(vias);
        pts.add(destPt);
        double[][] r = new double[2][pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            r[0][i] = pts.get(i).latitude;
            r[1][i] = pts.get(i).longitude;
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
            try {
                Route a = router.route(pts[0], pts[1], withNH);
                found.add(a);
                names.add("Via highways" + (withNH.avoidSH ? " (NH ok, no SH)" : " (NH allowed)"));
                Route b = router.route(pts[0], pts[1], noNH);
                if (Math.abs(b.distanceM - a.distanceM) > 200 || Math.abs(b.nhM - a.nhM) > 200) {
                    found.add(b);
                    names.add("Avoid NH" + (noNH.avoidSH ? " & SH" : ""));
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
            if (r.unpavedM > 300) body += String.format(Locale.US, " · kachcha %.1f km", r.unpavedM / 1000);
            if (r.tollM > 300) body += String.format(Locale.US, " · toll %.0f km", r.tollM / 1000);
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

    private static List<LatLong> toLatLongs(Route r) {
        List<LatLong> l = new ArrayList<>(r.size());
        for (int i = 0; i < r.size(); i++) l.add(new LatLong(r.lats[i], r.lons[i]));
        return l;
    }

    private void drawRoutes() {
        if (mainLine == null) return;
        List<LatLong> empty = new ArrayList<>();
        mainLine.setPoints(routes.isEmpty() ? empty : toLatLongs(routes.get(selected)));
        altLine.setPoints(routes.size() > 1 && !navigating ? toLatLongs(routes.get(1 - selected)) : empty);
        mainLine.requestRedraw();
        altLine.requestRedraw();
    }

    private void zoomTo(Route r) {
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (int i = 0; i < r.size(); i++) {
            minLat = Math.min(minLat, r.lats[i]);
            maxLat = Math.max(maxLat, r.lats[i]);
            minLon = Math.min(minLon, r.lons[i]);
            maxLon = Math.max(maxLon, r.lons[i]);
        }
        follow = false;
        updateLocateButton();
        mapView.setCenter(new LatLong((minLat + maxLat) / 2, (minLon + maxLon) / 2));
        int w = Math.max(1, mapView.getWidth()), h = Math.max(1, mapView.getHeight() / 2);
        double spanLon = Math.max(maxLon - minLon, 0.002), spanLat = Math.max(maxLat - minLat, 0.002) * 1.3;
        double tile = mapView.getModel().displayModel.getTileSize();
        double zLon = Math.log(w * 360.0 / (spanLon * tile)) / Math.log(2);
        double zLat = Math.log(h * 360.0 / (spanLat * tile)) / Math.log(2);
        int z = (int) Math.floor(Math.min(zLon, zLat));
        mapView.setZoomLevel((byte) Math.max(6, Math.min(17, z)));
    }

    private void clearRoute() {
        if (navigating) stopRide();
        routes.clear();
        routeNames.clear();
        startPt = destPt = null;
        vias.clear();
        updatePointMarkers();
        drawRoutes();
        routePanel.setVisibility(View.GONE);
    }

    private void showRouteOptions() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        RadioGroup rg = new RadioGroup(this);
        RadioButton fast = new RadioButton(this);
        fast.setText("Fastest (less time)");
        fast.setId(View.generateViewId());
        RadioButton shortest = new RadioButton(this);
        shortest.setText("Shortest (less km)");
        shortest.setId(View.generateViewId());
        rg.addView(fast);
        rg.addView(shortest);
        rg.check(prefs.getBoolean("fastest", true) ? fast.getId() : shortest.getId());
        box.addView(rg);
        CheckBox nh = check(box, "Prefer avoiding National Highways (NH)", prefs.getBoolean("avoidNH", false));
        CheckBox sh = check(box, "Avoid State Highways (SH)", prefs.getBoolean("avoidSH", false));
        CheckBox toll = check(box, "Avoid toll roads", prefs.getBoolean("avoidToll", false));
        CheckBox kachcha = check(box, "Avoid kachcha / unpaved roads", prefs.getBoolean("avoidUnpaved", false));
        CheckBox exp = check(box, "Allow expressways (two-wheelers usually not allowed)", prefs.getBoolean("allowExpressway", false));
        TextView note = new TextView(this);
        note.setText("Both routes (with and without NH) are always shown so you can compare.");
        note.setTextColor(0xFF666666);
        note.setPadding(0, dp(8), 0, 0);
        box.addView(note);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle("Route options").setView(sv)
                .setPositiveButton("Apply", (d, w) -> {
                    prefs.edit().putBoolean("fastest", rg.getCheckedRadioButtonId() == fast.getId())
                            .putBoolean("avoidNH", nh.isChecked()).putBoolean("avoidSH", sh.isChecked())
                            .putBoolean("avoidToll", toll.isChecked()).putBoolean("avoidUnpaved", kachcha.isChecked())
                            .putBoolean("allowExpressway", exp.isChecked()).apply();
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

    // ------------------------------------------------------------------ navigation

    private void startRide() {
        if (routes.isEmpty()) return;
        navigating = true;
        follow = true;
        offRouteCount = 0;
        navIndex = 0;
        updateLocateButton();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        routePanel.setVisibility(View.GONE);
        navPanel.setVisibility(View.VISIBLE);
        drawRoutes();
        navMain.setText("Ride started");
        navSub.setText(myLoc == null ? "Waiting for GPS…" : "");
        mapView.setZoomLevel((byte) 16);
        if (myLoc != null) {
            mapView.setCenter(new LatLong(myLoc.getLatitude(), myLoc.getLongitude()));
            updateNavigation(myLoc);
        }
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
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        navPanel.setVisibility(View.GONE);
        if (!routes.isEmpty()) {
            routePanel.setVisibility(View.VISIBLE);
            showRoutes(false);
        }
    }

    private void updateNavigation(Location l) {
        if (routes.isEmpty() || rerouting) return;
        Route r = routes.get(selected);
        double lat = l.getLatitude(), lon = l.getLongitude();
        // nearest point on the route, searching around the last position first
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
            stopRideLater();
            return;
        }
        float acc = l.hasAccuracy() ? l.getAccuracy() : 50;
        if (bestD > Math.max(60, acc * 1.5)) {
            offRouteCount++;
            if (offRouteCount >= 4) {
                reroute(l);
                return;
            }
        } else {
            offRouteCount = 0;
        }
        Route.Step cur = null, next = null;
        for (int i = 0; i < r.steps.size(); i++) {
            Route.Step s = r.steps.get(i);
            if (s.startPoint <= bestI) cur = s;
            else {
                next = s;
                break;
            }
        }
        navMain.setText(GeoUtil.formatDistance(remaining) + "  ·  " + GeoUtil.formatDuration(remainingTime) + " left");
        StringBuilder sub = new StringBuilder();
        sub.append("On: ").append(cur == null || cur.name == null ? "unnamed road" : cur.name);
        if (next != null) {
            double toNext = r.cumDist[Math.min(next.startPoint, r.size() - 1)] - done;
            sub.append("\nNext: ").append(next.name == null ? "unnamed road" : next.name)
                    .append(" in ").append(GeoUtil.formatDistance(Math.max(0, toNext)));
        }
        if (offRouteCount > 0) sub.append("\n⚠ Off route?");
        navSub.setText(sub.toString());
    }

    private void stopRideLater() {
        ui.postDelayed(() -> {
            if (navigating) stopRide();
        }, 8000);
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
                updatePointMarkers();
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
        if (trackLine != null) {
            List<LatLong> pts = new ArrayList<>();
            if (rec) for (double[] p : t.points()) pts.add(new LatLong(p[0], p[1]));
            trackLine.setPoints(pts);
            trackLine.requestRedraw();
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
        input.setHint("Village, town, place… (English or हिंदी)");
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
        LatLong c = mapView.getModel().mapViewPosition.getCenter();
        return new double[]{c.latitude, c.longitude};
    }

    private void showPlace(PlacesIndex.Place p) {
        LatLong ll = new LatLong(p.lat, p.lon);
        removeLayer(searchMarker);
        searchMarker = addMarker(ll, 0xFF6A1B9A, "•");
        follow = false;
        updateLocateButton();
        mapView.setCenter(ll);
        mapView.setZoomLevel((byte) (p.category.equals("city") ? 12 : p.category.equals("town") ? 13 : 15));
        double[] ref = refPoint();
        new AlertDialog.Builder(this).setTitle(p.title())
                .setMessage(p.label() + "\n" + GeoUtil.formatDistance(GeoUtil.distance(ref[0], ref[1], p.lat, p.lon)) + " away (straight line)")
                .setPositiveButton("🧭 Route here", (d, w) -> {
                    destPt = ll;
                    removeLayer(searchMarker);
                    searchMarker = null;
                    updatePointMarkers();
                    computeRoutes();
                })
                .setNeutralButton("Set as start", (d, w) -> {
                    startPt = ll;
                    removeLayer(searchMarker);
                    searchMarker = null;
                    updatePointMarkers();
                    if (destPt != null) computeRoutes();
                })
                .setNegativeButton("Close", null).show();
    }

    // ------------------------------------------------------------------ menu

    private void showMenu() {
        String lang = prefs.getString("lang", "en");
        String[] items = {
                "🔤 Map labels: " + ("hi".equals(lang) ? "हिंदी → switch to English" : "English → switch to हिंदी"),
                "🗺 Clear route & markers",
                "📁 Where are my rides saved?",
                "ℹ About & offline data",
        };
        new AlertDialog.Builder(this).setItems(items, (d, which) -> {
            switch (which) {
                case 0:
                    prefs.edit().putString("lang", "hi".equals(lang) ? "en" : "hi").apply();
                    recreate();
                    break;
                case 1:
                    clearRoute();
                    removeLayer(searchMarker);
                    searchMarker = null;
                    break;
                case 2:
                    new AlertDialog.Builder(this).setTitle("Saved rides (GPX)")
                            .setMessage("Rides are saved as GPX files in:\n\nDownload/BikeRidingMP/\n\n(and a copy in "
                                    + TrackRecorder.tracksDir(this).getAbsolutePath() + ")\n\nOpen them in any GPX app or share on WhatsApp.")
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
        sb.append("\nMap data © OpenStreetMap contributors (ODbL). Rendering: mapsforge (LGPL).");
        new AlertDialog.Builder(this).setTitle(getString(R.string.app_name)).setMessage(sb.toString())
                .setPositiveButton("OK", null).show();
    }

    // ------------------------------------------------------------------ drawing helpers

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private Bitmap toBitmap(android.graphics.Bitmap b) {
        return AndroidGraphicFactory.convertToBitmap(new BitmapDrawable(getResources(), b));
    }

    private android.graphics.Bitmap drawPin(int color, String letter) {
        int w = dp(30), h = dp(38);
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
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

    private android.graphics.Bitmap drawDot() {
        int s = dp(22);
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        c.drawCircle(s / 2f, s / 2f, s / 2f, p);
        p.setColor(0xFF1565C0);
        c.drawCircle(s / 2f, s / 2f, s / 2f - dp(3), p);
        return bmp;
    }
}
