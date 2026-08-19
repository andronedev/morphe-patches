import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** In-memory stand-in for the app's context and its private preferences. */
public final class FakeContext extends Context {

    public final Map<String, String> stored = new HashMap<>();
    public File database = new File("/nonexistent/main.db");

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return new SharedPreferences() {
            @Override
            public String getString(String key, String fallback) {
                String value = stored.get(key);
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
                        stored.putAll(pending);
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
