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
    public static final String KEY_PUBLISHER_ID = "publisher_id";
    public static final String KEY_EMAIL = "email";
    public static final String KEY_TIME_ZONE = "time_zone";
    public static final String KEY_CURRENCY = "currency";

    /** Names the app looks up in its encrypted DataStore. */
    private static final String DATA_STORE_CLIENT_SECRET = "web_client_secret";
    private static final String DATA_STORE_PUBLISHER_ID = "user_pub_id";
    private static final String DATA_STORE_REFRESH_TOKEN_PREFIX = "token_refresh_";

    /** Name the OkHttp authenticators look up in the pre-DataStore storage. */
    private static final String LEGACY_REFRESH_TOKEN = "user_token_refresh";

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
        return !get(KEY_CLIENT_ID).isEmpty()
                && !get(KEY_CLIENT_SECRET).isEmpty()
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

        if (DATA_STORE_CLIENT_SECRET.equals(name)) return get(KEY_CLIENT_SECRET);
        if (DATA_STORE_PUBLISHER_ID.equals(name)) return get(KEY_PUBLISHER_ID);
        if (name.startsWith(DATA_STORE_REFRESH_TOKEN_PREFIX)) return get(KEY_REFRESH_TOKEN);

        return null;
    }

    /** Answers the pre-DataStore read the OkHttp authenticators try first. */
    public static String forLegacyKey(String name) {
        if (name == null || !isConfigured()) return null;

        return LEGACY_REFRESH_TOKEN.equals(name) ? get(KEY_REFRESH_TOKEN) : null;
    }

    /**
     * The client id is read once, when the app store is constructed, so it is substituted there
     * rather than on every read.
     *
     * @param original the value the app was built with.
     */
    public static String clientIdOrOriginal(String original) {
        String clientId = get(KEY_CLIENT_ID);
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
