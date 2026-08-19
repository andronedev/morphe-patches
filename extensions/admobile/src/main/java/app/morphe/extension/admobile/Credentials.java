package app.morphe.extension.admobile;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.util.Currency;
import java.util.Locale;

/**
 * Credentials the patched AdMobile signs its AdMob API calls with.
 *
 * <p>AdMobile obtains an authorization code through Google Sign-In, exchanges it for tokens at
 * {@code oauth2.googleapis.com/token}, and downloads the OAuth client secret from the developer's
 * Firestore once Firebase has accepted the sign-in. A re-signed APK cannot complete Google Sign-In,
 * which leaves every step after it without inputs.
 *
 * <p>Everything after the authorization code is plain HTTPS that does not care how the APK is
 * signed, so supplying the client and a refresh token directly is enough. The patch redirects the
 * app's reads of the client id, client secret, publisher id and refresh token here.
 *
 * <p>Values live in the app's own private preferences and are entered in {@link
 * CredentialsActivity}. Nothing is compiled into the APK, so a patched build carries no secret and
 * one build works for everybody.
 */
public final class Credentials {

    private static final String TAG = "MorpheCredentials";

    private static final String PREFERENCES_NAME = "morphe_admobile_credentials";

    /** The app's Room database, whose users table the rest of the app reads. */
    private static final String DATABASE_NAME = "main.db";

    public static final String KEY_CLIENT_ID = "client_id";
    public static final String KEY_CLIENT_SECRET = "client_secret";
    public static final String KEY_REFRESH_TOKEN = "refresh_token";

    /**
     * Short lived, but the app refuses to fetch anything while it looks absent, so it is served
     * alongside the refresh token and kept current from the app's own writes.
     */
    public static final String KEY_ACCESS_TOKEN = "access_token";
    public static final String KEY_PUBLISHER_ID = "publisher_id";
    public static final String KEY_TIME_ZONE = "time_zone";
    public static final String KEY_CURRENCY = "currency";

    /** What the last seeded database row was built from, so the next launch can skip the write. */
    private static final String KEY_SEEDED = "seeded";

    /**
     * Outcome of the last sign in. Kept because the browser holds the foreground while the flow
     * runs, and the system is free to tear the form down behind it, so a toast would be lost.
     */
    public static final String KEY_LAST_STATUS = "last_status";

    /**
     * Authorization code captured from the redirect, held until the app is back in the foreground.
     *
     * <p>The exchange is not done as soon as the code arrives: the browser owns the screen at that
     * moment, and several vendor builds cut background apps off the network, which surfaces as a
     * DNS failure on the token endpoint. Waiting until the form resumes avoids that entirely.
     */
    public static final String KEY_PENDING_CODE = "pending_code";
    public static final String KEY_PENDING_VERIFIER = "pending_verifier";
    public static final String KEY_PENDING_REDIRECT = "pending_redirect";

    /** Names the app looks up in its encrypted DataStore. */
    private static final String DATA_STORE_CLIENT_SECRET = "web_client_secret";
    private static final String DATA_STORE_PUBLISHER_ID = "user_pub_id";
    private static final String DATA_STORE_REFRESH_TOKEN_PREFIX = "token_refresh_";
    private static final String DATA_STORE_ACCESS_TOKEN_PREFIX = "token_access_";

    /**
     * The currency symbol the report formatter prefixes every amount with. It lives in the app's
     * settings store, written only when an account is selected through the app's own sign in, so in
     * a patched build it is never set and the formatter prints the word "null" instead.
     */
    private static final String SETTING_CURRENCY_SYMBOL = "config_currency_code";

    /** Names the OkHttp authenticators look up in the pre-DataStore storage. */
    private static final String LEGACY_REFRESH_TOKEN = "user_token_refresh";
    private static final String LEGACY_ACCESS_TOKEN = "user_token_access";

    private static Context context;
    private static SharedPreferences preferences;

    private Credentials() {
    }

    /**
     * Replaced by the patch with a method returning true, so the app can tell whether the patch that
     * ships this class was applied.
     */
    public static boolean isPatchIncluded() {
        return false;
    }

    /**
     * OAuth client the in-app sign in runs against. Both are replaced by the patch when the
     * clientId and clientSecret options are set, which is what lets a build sign in with no setup
     * at all. Left empty, the form asks for a client instead.
     */
    public static String bundledClientId() {
        return "";
    }

    public static String bundledClientSecret() {
        return "";
    }

    /** The client to sign in with: the one already stored, else the one built into the patch. */
    public static String effectiveClientId() {
        String stored = get(KEY_CLIENT_ID);
        return stored.isEmpty() ? bundledClientId() : stored;
    }

    public static String effectiveClientSecret() {
        String stored = get(KEY_CLIENT_SECRET);
        return stored.isEmpty() ? bundledClientSecret() : stored;
    }

    /** True when sign in can run without the user supplying a client first. */
    public static boolean hasClient() {
        return !effectiveClientId().isEmpty() && !effectiveClientSecret().isEmpty();
    }

    /** Binds the extension to the app's context. Cheap enough for any entry point to call. */
    public static void attach(Context applicationContext) {
        if (context != null) return;

        context = applicationContext.getApplicationContext();
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /** Called from the patched {@code Application.onCreate}. */
    public static void init(Context applicationContext) {
        attach(applicationContext);
        seedAccount();
    }

    /**
     * Writes the account into the app's own database.
     *
     * <p>Answering the query for the selected account is enough to get past the startup check, but
     * the rest of the app reads the same table through other queries, the account list the home
     * screen observes among them, and those saw an empty table, so nothing was ever requested.
     *
     * <p>Done from {@code Application.onCreate}, before Room opens the file, and only when it
     * already exists: the first sign in restarts the app, so the row lands on the way back up.
     * Unchanged credentials write nothing, which keeps the database off the startup path.
     */
    public static void seedAccount() {
        if (context == null || !isConfigured()) return;

        // Clearing the app's data takes the database with it, so what was seeded into it is gone
        // too and the next launch has to write the row again.
        if (!context.getDatabasePath(DATABASE_NAME).exists()) {
            put(KEY_SEEDED, "");
            return;
        }

        String row = publisherId() + "|" + timeZone() + "|" + currency();
        if (row.equals(get(KEY_SEEDED))) return;

        boolean written = withDatabase(
                "INSERT OR REPLACE INTO users (id, sign_id, fire_id, email, name, avatar,"
                        + " time_zone, currency, is_selected) VALUES (?,?,?,?,?,?,?,?,1)",
                new Object[]{
                        publisherId(), publisherId(), publisherId(),
                        "", "", "", timeZone(), currency(),
                });

        if (written) put(KEY_SEEDED, row);
    }

    private static void clearAccount() {
        withDatabase("DELETE FROM users WHERE id = ?", new Object[]{publisherId()});
        put(KEY_SEEDED, "");
    }

    /**
     * Runs one statement against the app's database.
     *
     * <p>Write-ahead logging is asked for explicitly because Room opens the same file that way, and
     * opening it without would take it out of WAL and force a checkpoint on every launch.
     *
     * @return whether the statement ran.
     */
    private static boolean withDatabase(String statement, Object[] arguments) {
        if (context == null) return false;

        File database = context.getDatabasePath(DATABASE_NAME);
        if (!database.exists()) return false;

        SQLiteDatabase handle = null;
        try {
            handle = SQLiteDatabase.openDatabase(
                    database.getPath(),
                    null,
                    SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING);
            handle.execSQL(statement, arguments);
            return true;
        } catch (Exception exception) {
            Log.e(TAG, "could not run " + statement, exception);
            return false;
        } finally {
            closeQuietly(handle);
        }
    }

    static void closeQuietly(Closeable closeable) {
        try {
            if (closeable != null) closeable.close();
        } catch (Exception ignored) {
        }
    }

    public static String get(String key) {
        if (preferences == null) return "";

        String value = preferences.getString(key, "");
        return value == null ? "" : value;
    }

    /**
     * Written synchronously, on purpose.
     *
     * <p>{@code apply} only schedules the write, and the sign in ends by killing the process so the
     * app comes back with a fresh one. Anything still queued at that moment is lost: the publisher
     * id was being written and then dropped on every attempt, which left the account half connected
     * no matter how many times it was retried. These are a handful of short values written a
     * handful of times, so paying for the disk write here costs nothing worth measuring.
     */
    public static void put(String key, String value) {
        if (preferences == null) {
            Log.e(TAG, "put before attach: " + key);
            return;
        }

        preferences.edit().putString(key, value == null ? "" : value.trim()).commit();
    }

    /** One write for the lot, rather than one per key. */
    public static void clear(String... keys) {
        if (preferences == null) return;

        SharedPreferences.Editor editor = preferences.edit();
        for (String key : keys) editor.putString(key, "");
        editor.commit();
    }

    /** True once the values the token requests need are present. */
    public static boolean isConfigured() {
        return hasClient()
                && !get(KEY_REFRESH_TOKEN).isEmpty()
                && !get(KEY_PUBLISHER_ID).isEmpty();
    }

    /**
     * Answers the app's decrypting DataStore reads, on the store holding the secrets and on the one
     * holding the settings alike.
     *
     * <p>The name is matched before anything is read back, because these run on every read the app
     * makes, not only the ones this class has an answer for.
     *
     * @return the configured value, or null to let the app read its own storage.
     */
    public static String forDataStoreKey(String name) {
        if (name == null) return null;

        if (DATA_STORE_CLIENT_SECRET.equals(name)) {
            return isConfigured() ? effectiveClientSecret() : null;
        }
        if (DATA_STORE_PUBLISHER_ID.equals(name)) {
            return isConfigured() ? publisherId() : null;
        }
        if (SETTING_CURRENCY_SYMBOL.equals(name)) {
            return isConfigured() ? currencySymbol() : null;
        }
        if (name.startsWith(DATA_STORE_REFRESH_TOKEN_PREFIX)) {
            return isConfigured() ? get(KEY_REFRESH_TOKEN) : null;
        }
        if (name.startsWith(DATA_STORE_ACCESS_TOKEN_PREFIX)) {
            return isConfigured() ? get(KEY_ACCESS_TOKEN) : null;
        }

        return null;
    }

    /**
     * Answers the pre-DataStore reads the OkHttp authenticators try first.
     *
     * <p>The access token matters as much as the refresh token here: the app treats a blank one as
     * "not signed in" and skips fetching altogether, whatever else is in place.
     */
    public static String forLegacyKey(String name) {
        if (name == null) return null;

        if (LEGACY_REFRESH_TOKEN.equals(name)) {
            return isConfigured() ? get(KEY_REFRESH_TOKEN) : null;
        }
        if (LEGACY_ACCESS_TOKEN.equals(name)) {
            return isConfigured() ? get(KEY_ACCESS_TOKEN) : null;
        }

        return null;
    }

    /**
     * Mirrors the app's own token writes.
     *
     * <p>An access token lasts an hour, after which the app refreshes it and stores the new one.
     * Without this the reads above would keep answering with the expired one and every request
     * would fail, so what the app persists is taken as the newer truth.
     */
    public static void observeWrite(String name, String value) {
        if (name == null || value == null || value.isEmpty()) return;

        if (LEGACY_ACCESS_TOKEN.equals(name) || name.startsWith(DATA_STORE_ACCESS_TOKEN_PREFIX)) {
            put(KEY_ACCESS_TOKEN, value);
        } else if (LEGACY_REFRESH_TOKEN.equals(name)
                || name.startsWith(DATA_STORE_REFRESH_TOKEN_PREFIX)) {
            put(KEY_REFRESH_TOKEN, value);
        }
    }

    /**
     * The client id is read once, when the app store is constructed, so it is substituted there
     * rather than on every read.
     *
     * @param original the value the app was built with.
     */
    public static String clientIdOrOriginal(String original) {
        String clientId = effectiveClientId();
        return clientId.isEmpty() ? original : clientId;
    }

    /**
     * Where the app's sign in button goes in a patched build: to the form, rather than to a Google
     * flow a re-signed APK cannot complete. It stays reachable once credentials are stored, because
     * it is also where disconnecting lives.
     */
    public static Intent signInIntent() {
        Intent intent = new Intent();
        if (context != null) {
            intent.setClassName(context.getPackageName(), CredentialsActivity.class.getName());
        }
        return intent;
    }

    /**
     * Forgets the session but keeps the OAuth client, which is a property of the build rather than
     * of whoever is signed in, so signing back in is one tap with the fields already filled.
     *
     * <p>Clearing it is also what actually signs the user out: the app's own sign out leaves the
     * fabricated account in place, since that account is served from here rather than from its
     * database.
     */
    public static void signOut() {
        clearAccount();

        clear(KEY_REFRESH_TOKEN, KEY_ACCESS_TOKEN, KEY_PUBLISHER_ID, KEY_LAST_STATUS,
                KEY_PENDING_CODE, KEY_PENDING_VERIFIER, KEY_PENDING_REDIRECT);
    }

    public static String publisherId() {
        return get(KEY_PUBLISHER_ID);
    }

    public static String timeZone() {
        String timeZone = get(KEY_TIME_ZONE);
        return timeZone.isEmpty() ? "UTC" : timeZone;
    }

    public static String currency() {
        String currency = get(KEY_CURRENCY);
        return currency.isEmpty() ? "USD" : currency;
    }

    /** The symbol for {@link #currency()}, falling back to the code the app would show anyway. */
    public static String currencySymbol() {
        String code = currency();
        try {
            String symbol = Currency.getInstance(code).getSymbol(Locale.US);
            if (symbol != null && !symbol.isEmpty()) return symbol;
        } catch (Exception exception) {
            Log.w(TAG, "no symbol for " + code, exception);
        }

        return code;
    }
}
