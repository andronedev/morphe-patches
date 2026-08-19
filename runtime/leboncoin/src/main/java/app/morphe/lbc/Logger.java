package app.morphe.lbc;

import android.util.Log;

/** Journalisation unifiée : `adb logcat -s MorpheLBC`. */
public final class Logger {

    public static final String TAG = "MorpheLBC";

    private final String prefix;

    public Logger(String prefix) {
        this.prefix = prefix;
    }

    public void i(String message) {
        Log.i(TAG, prefix + ": " + message);
    }

    public void w(String message) {
        Log.w(TAG, prefix + ": " + message);
    }

    public void e(String message, Throwable error) {
        Log.e(TAG, prefix + ": " + message, error);
    }

    public void d(String message) {
        if (Lbc.isDebug()) {
            Log.d(TAG, prefix + ": " + message);
        }
    }
}
