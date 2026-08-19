import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory stand-in for the app's context and its private preferences.
 *
 * <p>Preferences are modelled as two maps rather than one, because the difference between them is
 * where this extension's hardest bug lived. {@code apply} only queues a write; {@code commit}
 * performs it. Signing in ends by killing the process so the app restarts, and everything still
 * queued at that moment is lost. {@link #killProcess()} reproduces exactly that.
 */
public final class FakeContext extends Context {

    /** Written and survives a restart. */
    public final Map<String, String> stored = new HashMap<>();

    /** Queued by apply and lost if the process dies first. */
    public final Map<String, String> queued = new HashMap<>();

    public int applyCalls;
    public int commitCalls;

    public File database = new File("/nonexistent/main.db");

    /** What the app does at the end of a sign in: exit(0), then come back in a fresh process. */
    public void killProcess() {
        queued.clear();
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return new SharedPreferences() {
            @Override
            public String getString(String key, String fallback) {
                String value = queued.containsKey(key) ? queued.get(key) : stored.get(key);
                return value == null ? fallback : value;
            }

            @Override
            public Editor edit() {
                final Map<String, String> pending = new HashMap<>();
                return new Editor() {
                    @Override
                    public Editor putString(String key, String value) {
                        pending.put(key, value);
                        return this;
                    }

                    @Override
                    public void apply() {
                        applyCalls++;
                        queued.putAll(pending);
                    }

                    @Override
                    public boolean commit() {
                        commitCalls++;
                        stored.putAll(pending);
                        return true;
                    }
                };
            }
        };
    }

    @Override
    public File getDatabasePath(String name) {
        return database;
    }

    @Override
    public String getPackageName() {
        return "io.stark.admob";
    }
}
