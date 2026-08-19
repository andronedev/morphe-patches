package android.content;

public interface SharedPreferences {
    String getString(String key, String fallback);

    Editor edit();

    interface Editor {
        Editor putString(String key, String value);

        /** Schedules the write. Anything still queued when the process dies is lost. */
        void apply();

        /** Writes before returning. */
        boolean commit();
    }
}
