package com.bikeriding.mp;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

/**
 * Turn alerts you can follow with the phone in your pocket:
 * <pre>
 *   LEFT  : beep ....... beep        (two beeps with a pause)   + buzz ..... buzz
 *   RIGHT : beep-beep-beep           (three fast beeps)         + buzz-buzz-buzz
 *   ARRIVED: one long beep + long buzz
 *   OFF ROUTE: one long buzz
 * </pre>
 * Sound uses the media stream, so it also plays in earphones / Bluetooth helmet headsets.
 */
public final class TurnAlerts {
    private static final String TAG = "TurnAlerts";
    private final Handler h = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final Vibrator vibrator;
    private ToneGenerator tone;

    public TurnAlerts(Context c, SharedPreferences prefs) {
        this.prefs = prefs;
        vibrator = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME);
        } catch (RuntimeException e) {
            Log.w(TAG, "no tone generator", e);
        }
    }

    public boolean soundOn() {
        return prefs.getBoolean("alertSound", true);
    }

    public boolean vibrateOn() {
        return prefs.getBoolean("alertVibrate", true);
    }

    public void left() {
        beeps(new long[]{0, 750}, 180);
        vibrate(new long[]{0, 350, 750, 350});
    }

    public void right() {
        beeps(new long[]{0, 230, 460}, 150);
        vibrate(new long[]{0, 150, 80, 150, 80, 150});
    }

    public void arrived() {
        if (soundOn() && tone != null) tone.startTone(ToneGenerator.TONE_DTMF_0, 1000);
        vibrate(new long[]{0, 1200});
    }

    public void offRoute() {
        vibrate(new long[]{0, 800});
    }

    private void beeps(long[] at, int durationMs) {
        if (!soundOn() || tone == null) return;
        for (long t : at) {
            h.postDelayed(() -> {
                try {
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, durationMs);
                } catch (RuntimeException e) {
                    Log.w(TAG, "beep", e);
                }
            }, t);
        }
    }

    @SuppressWarnings("deprecation")
    private void vibrate(long[] pattern) {
        if (!vibrateOn() || vibrator == null || !vibrator.hasVibrator()) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            else vibrator.vibrate(pattern, -1);
        } catch (RuntimeException e) {
            Log.w(TAG, "vibrate", e);
        }
    }

    public void release() {
        h.removeCallbacksAndMessages(null);
        if (tone != null) tone.release();
        tone = null;
    }
}
