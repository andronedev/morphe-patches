package app.morphe.extension.admobile;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

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

    public static final String KEY_CLIENT_ID = "client_id";
    public static final String KEY_CLIENT_SECRET = "client_secret";
    public static final String KEY_REFRESH_TOKEN = "refresh_token";

    /**
     * Short lived, but the app refuses to fetch anything while it looks absent, so it is served
     * alongside the refresh token and kept current from the app's own writes.
     */
    public static final String KEY_ACCESS_TOKEN = "access_token";
    public static final String KEY_PUBLISHER_ID = "publisher_id";
    public static final String KEY_EMAIL = "email";
    public static final String KEY_TIME_ZONE = "time_zone";
    public static final String KEY_CURRENCY = "currency";

    /**
     * Outcome of the last sign in. Kept because the browser holds the foreground while the flow
     * runs, and the system is free to tear the form down behind it — a toast would be lost.
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

    /** Names the OkHttp authenticators look up in the pre-DataStore storage. */
    private static final String LEGACY_REFRESH_TOKEN = "user_token_refresh";
    private static final String LEGACY_ACCESS_TOKEN = "user_token_access";

    private static Context context;

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

    /** Called from the patched {@code Application.onCreate}. */
    public static void init(Context applicationContext) {
        context = applicationContext.getApplicationContext();
    }

    private static SharedPreferences preferences() {
        if (context == null) return null;
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public static String get(String key) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return "";

        String value = preferences.getString(key, "");
        return value == null ? "" : value;
    }

    public static void put(String key, String value) {
        SharedPreferences preferences = preferences();
        if (preferences == null) {
            Log.e(TAG, "put before init: " + key);
            return;
        }

        preferences.edit().putString(key, value == null ? "" : value.trim()).apply();
    }

    /** True once the four values the token requests need are present. */
    public static boolean isConfigured() {
        return hasClient()
                && !get(KEY_REFRESH_TOKEN).isEmpty()
                && !get(KEY_PUBLISHER_ID).isEmpty();
    }

    /**
     * Answers the app's decrypting DataStore read.
     *
     * @return the configured value, or null to let the app read its own storage.
     */
    public static String forDataStoreKey(String name) {
        if (name == null || !isConfigured()) return null;

        if (DATA_STORE_CLIENT_SECRET.equals(name)) return effectiveClientSecret();
        if (DATA_STORE_PUBLISHER_ID.equals(name)) return get(KEY_PUBLISHER_ID);
        if (name.startsWith(DATA_STORE_REFRESH_TOKEN_PREFIX)) return get(KEY_REFRESH_TOKEN);
        if (name.startsWith(DATA_STORE_ACCESS_TOKEN_PREFIX)) return get(KEY_ACCESS_TOKEN);

        return null;
    }

    /**
     * Answers the pre-DataStore reads the OkHttp authenticators try first.
     *
     * <p>The access token matters as much as the refresh token here: the app treats a blank one as
     * "not signed in" and skips fetching altogether, whatever else is in place.
     */
    public static String forLegacyKey(String name) {
        if (name == null || !isConfigured()) return null;

        if (LEGACY_REFRESH_TOKEN.equals(name)) return get(KEY_REFRESH_TOKEN);
        if (LEGACY_ACCESS_TOKEN.equals(name)) return get(KEY_ACCESS_TOKEN);

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
     * Substitutes the Google Sign-In intent while no credentials are stored, so the app's own sign
     * in button opens the form instead of a flow a re-signed APK cannot complete. Once they are
     * stored the account is served locally and the button is never reached again.
     *
     * @param original the intent the app built.
     */
    public static Intent signInIntentOrOriginal(Intent original) {
        if (isConfigured() || context == null) return original;

        Intent intent = new Intent();
        intent.setClassName(context.getPackageName(), CredentialsActivity.class.getName());
        return intent;
    }

    public static String publisherId() {
        return get(KEY_PUBLISHER_ID);
    }

    public static String email() {
        return get(KEY_EMAIL);
    }

    public static String timeZone() {
        String timeZone = get(KEY_TIME_ZONE);
        return timeZone.isEmpty() ? "UTC" : timeZone;
    }

    public static String currency() {
        String currency = get(KEY_CURRENCY);
        return currency.isEmpty() ? "USD" : currency;
    }
}
