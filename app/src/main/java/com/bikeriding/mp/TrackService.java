package com.bikeriding.mp;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.bikeriding.mp.routing.GeoUtil;

/** Foreground service so the ride keeps recording with the screen off. GPS only – works fully offline. */
public class TrackService extends Service implements LocationListener, TrackRecorder.Listener {
    private static final String TAG = "TrackService";
    public static final String ACTION_START = "start", ACTION_STOP = "stop";
    private static final String CHANNEL = "ride";
    private static final int NOTIF_ID = 7;
    private LocationManager lm;
    private long lastNotify;

    public static void start(Context c) {
        Intent i = new Intent(c, TrackService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.startService(new Intent(c, TrackService.class).setAction(ACTION_STOP));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            String where = TrackRecorder.get().stop(this);
            if (where != null) Toast.makeText(this, "Ride saved: " + where, Toast.LENGTH_LONG).show();
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = buildNotification("Recording your ride…");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, n);
        }
        try {
            TrackRecorder.get().start(this);
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            Toast.makeText(this, "Cannot record: " + e.getMessage(), Toast.LENGTH_LONG).show();
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }
        TrackRecorder.get().addListener(this);
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Ride recording", NotificationManager.IMPORTANCE_LOW));
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, TrackService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Bike Riding MP")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop & save", stop).build());
        return b.build();
    }

    @Override
    public void onTrackChanged() {
        TrackRecorder t = TrackRecorder.get();
        if (!t.isRecording()) return;
        long now = System.currentTimeMillis();
        if (now - lastNotify < 10000) return;
        lastNotify = now;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        long min = t.elapsedMs() / 60000;
        nm.notify(NOTIF_ID, buildNotification(GeoUtil.formatDistance(t.distanceM()) + " · " + (min / 60) + "h " + (min % 60) + "m"));
    }

    @Override
    public void onLocationChanged(Location location) {
        TrackRecorder.get().onLocation(location);
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

    @Override
    public void onDestroy() {
        TrackRecorder.get().removeListener(this);
        if (lm != null) lm.removeUpdates(this);
        if (TrackRecorder.get().isRecording()) TrackRecorder.get().stop(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
