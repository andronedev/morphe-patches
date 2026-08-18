package app.morphe.extension.admobile;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Runs the Google consent flow inside the app, so nothing has to be pasted in by hand.
 *
 * <p>The app's own Google Sign-In cannot be used on a re-signed APK, because Google checks the
 * calling package against the certificate its Android OAuth client was registered with. A client of
 * type Desktop carries no such binding, which is why one is used here: the browser does the
 * consent, and the authorization code comes back over a loopback redirect.
 *
 * <p>Loopback rather than a custom scheme keeps the redirect out of the manifest, so the patch does
 * not have to know the client id at build time to declare an intent filter for it.
 *
 * <p>The flow runs in two halves. {@link #start} captures the code while the browser holds the
 * screen; {@link #completePending} does the network exchange once the form is back in the
 * foreground. They are split because several vendor builds cut background apps off the network,
 * which shows up as the token endpoint failing to resolve.
 *
 * <p>PKCE is used as Google requires for installed apps. The client secret a Desktop client is
 * issued is sent with the exchange; Google does not treat it as confidential for this client type.
 */
public final class OAuthFlow {

    private static final String TAG = "MorpheOAuth";

    private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String ACCOUNTS_ENDPOINT = "https://admob.googleapis.com/v1/accounts";

    /** The scopes AdMobile itself requests. */
    private static final String SCOPES =
            "https://www.googleapis.com/auth/admob.readonly"
                    + " https://www.googleapis.com/auth/adsense.readonly";

    /** Connectivity can lag a moment behind the app returning to the foreground. */
    private static final int NETWORK_ATTEMPTS = 4;
    private static final long RETRY_DELAY_MS = 1500L;

    private static final String REDIRECT_RESPONSE =
            "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n"
                    + "<!doctype html><meta charset=\"utf-8\">"
                    + "<body style=\"font-family:system-ui;background:#121212;color:#e0e0e0;padding:3rem\">"
                    + "<h2 style=\"color:#f0c040\">Authorised</h2><p>Back to AdMobile.</p>";

    /** Reported on the main thread once the flow ends, one way or the other. */
    public interface Callback {
        void onFinished(boolean success, String message);
    }

    private OAuthFlow() {
    }

    /** True once the redirect has been captured and only the exchange is left to do. */
    public static boolean hasPendingCode() {
        return !Credentials.get(Credentials.KEY_PENDING_CODE).isEmpty();
    }

    /**
     * Opens the consent screen and captures the redirect. The exchange itself is left to
     * {@link #completePending}, which the form calls when it comes back to the foreground.
     */
    public static void start(Activity activity, String clientId, String clientSecret, Callback callback) {
        new Thread(() -> authorize(activity, clientId, clientSecret, callback)).start();
    }

    private static void authorize(Activity activity, String clientId, String clientSecret, Callback callback) {
        ServerSocket server = null;

        try {
            // Port 0 lets the system pick a free one; Google accepts any loopback port.
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(300_000);

            String redirectUri = "http://127.0.0.1:" + server.getLocalPort();
            String verifier = randomVerifier();

            Credentials.put(Credentials.KEY_CLIENT_ID, clientId);
            Credentials.put(Credentials.KEY_CLIENT_SECRET, clientSecret);
            Credentials.put(Credentials.KEY_PENDING_VERIFIER, verifier);
            Credentials.put(Credentials.KEY_PENDING_REDIRECT, redirectUri);

            activity.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse(authorizationUrl(clientId, redirectUri, verifier))));

            String code = awaitCode(activity, server);

            if (code == null) {
                report(activity, callback, false, "No authorisation code came back.");
                return;
            }

            Credentials.put(Credentials.KEY_PENDING_CODE, code);
            Credentials.put(Credentials.KEY_LAST_STATUS, "Authorised, finishing…");
        } catch (Exception exception) {
            Log.e(TAG, "authorisation failed", exception);
            report(activity, callback, false, "Sign in failed: " + exception.getMessage());
        } finally {
            close(server);
        }
    }

    /**
     * Exchanges the captured code and reads the account back. Called with the app in the
     * foreground, where its network is available.
     */
    public static void completePending(Activity activity, Callback callback) {
        new Thread(() -> exchange(activity, callback)).start();
    }

    private static void exchange(Activity activity, Callback callback) {
        String code = Credentials.get(Credentials.KEY_PENDING_CODE);
        if (code.isEmpty()) return;

        try {
            String tokenResponse = postForm(TOKEN_ENDPOINT,
                    "client_id=" + encode(Credentials.effectiveClientId())
                            + "&client_secret=" + encode(Credentials.effectiveClientSecret())
                            + "&code=" + encode(code)
                            + "&code_verifier=" + encode(Credentials.get(Credentials.KEY_PENDING_VERIFIER))
                            + "&grant_type=authorization_code"
                            + "&redirect_uri=" + encode(Credentials.get(Credentials.KEY_PENDING_REDIRECT)));

            String refreshToken = jsonString(tokenResponse, "refresh_token");
            String accessToken = jsonString(tokenResponse, "access_token");

            if (refreshToken == null || accessToken == null) {
                // The body names what Google objected to, which is the whole diagnosis.
                String detail = jsonString(tokenResponse, "error_description");
                if (detail == null) detail = jsonString(tokenResponse, "error");

                clearPending();
                report(activity, callback, false, detail != null
                        ? "Token exchange refused: " + detail
                        : "Google returned no refresh token. Revoke the app at "
                                + "myaccount.google.com/permissions and try again.");
                return;
            }

            Credentials.put(Credentials.KEY_REFRESH_TOKEN, refreshToken);
            Credentials.put(Credentials.KEY_ACCESS_TOKEN, accessToken);

            // The AdMob API names the account holding the token, so the publisher id, currency and
            // time zone never have to be looked up in the console.
            String accounts = get(ACCOUNTS_ENDPOINT, accessToken);
            if (accounts != null) {
                store(Credentials.KEY_PUBLISHER_ID, jsonString(accounts, "publisherId"));
                store(Credentials.KEY_CURRENCY, jsonString(accounts, "currencyCode"));
                store(Credentials.KEY_TIME_ZONE, jsonString(accounts, "reportingTimeZone"));
            }

            clearPending();

            if (Credentials.get(Credentials.KEY_PUBLISHER_ID).isEmpty()) {
                report(activity, callback, false,
                        "Signed in, but no AdMob account is linked to this Google account.");
                return;
            }

            report(activity, callback, true, "Connected.");
        } catch (Exception exception) {
            Log.e(TAG, "token exchange failed", exception);
            // The code is left in place: it stays usable for a few minutes, so simply returning to
            // this screen with a working connection is enough to finish.
            report(activity, callback, false, "Sign in failed: " + exception.getMessage());
        }
    }

    private static void clearPending() {
        Credentials.put(Credentials.KEY_PENDING_CODE, "");
        Credentials.put(Credentials.KEY_PENDING_VERIFIER, "");
        Credentials.put(Credentials.KEY_PENDING_REDIRECT, "");
    }

    private static String authorizationUrl(String clientId, String redirectUri, String verifier)
            throws Exception {
        return AUTH_ENDPOINT
                + "?client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(redirectUri)
                + "&response_type=code"
                + "&scope=" + encode(SCOPES)
                // Both are needed for a refresh token to be issued at all.
                + "&access_type=offline"
                + "&prompt=consent"
                + "&code_challenge=" + encode(challenge(verifier))
                + "&code_challenge_method=S256";
    }

    /** Reads the single request the browser makes on redirect and pulls the code out of it. */
    private static String awaitCode(Activity activity, ServerSocket server) throws Exception {
        Socket socket = server.accept();

        try {
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String requestLine = reader.readLine();

            OutputStream output = socket.getOutputStream();
            output.write(REDIRECT_RESPONSE.getBytes("UTF-8"));
            output.flush();

            // The browser is in the foreground at this point. Pull the app back up: the exchange
            // needs the network, which vendor builds grant only to foreground apps.
            activity.startActivity(new Intent(activity, CredentialsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP));

            if (requestLine == null) return null;

            Log.i(TAG, "redirect: " + requestLine);
            return queryParameter(requestLine, "code");
        } finally {
            close(socket);
        }
    }

    /**
     * Reads one parameter out of the request line, which looks like {@code GET /?a=1&b=2 HTTP/1.1}.
     *
     * <p>Matched key by key rather than by searching for the name, so that a parameter merely
     * ending in the one being looked for cannot be mistaken for it.
     */
    private static String queryParameter(String requestLine, String name) {
        int query = requestLine.indexOf('?');
        if (query < 0) return null;

        int end = requestLine.indexOf(' ', query);
        String parameters = end < 0
                ? requestLine.substring(query + 1)
                : requestLine.substring(query + 1, end);

        for (String parameter : parameters.split("&")) {
            int separator = parameter.indexOf('=');
            if (separator < 0 || !parameter.substring(0, separator).equals(name)) continue;

            try {
                return java.net.URLDecoder.decode(parameter.substring(separator + 1), "UTF-8");
            } catch (Exception exception) {
                return null;
            }
        }

        return null;
    }

    private static String randomVerifier() {
        byte[] bytes = new byte[64];
        new SecureRandom().nextBytes(bytes);
        return base64Url(bytes);
    }

    private static String challenge(String verifier) throws Exception {
        return base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("UTF-8")));
    }

    private static String base64Url(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static String postForm(String endpoint, String body) throws Exception {
        Exception last = null;

        for (int attempt = 0; attempt < NETWORK_ATTEMPTS; attempt++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();

            try {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                DataOutputStream output = new DataOutputStream(connection.getOutputStream());
                output.write(body.getBytes("UTF-8"));
                output.flush();
                output.close();

                return read(connection);
            } catch (UnknownHostException exception) {
                // Connectivity is often still settling right after the app returns to the front.
                last = exception;
                Log.w(TAG, "no route to " + endpoint + ", retrying");
                Thread.sleep(RETRY_DELAY_MS);
            } finally {
                connection.disconnect();
            }
        }

        throw last;
    }

    private static String get(String endpoint, String accessToken) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();

            try {
                connection.setRequestProperty("Authorization", "Bearer " + accessToken);
                return read(connection);
            } finally {
                connection.disconnect();
            }
        } catch (Exception exception) {
            Log.e(TAG, "could not read " + endpoint, exception);
            return null;
        }
    }

    private static String read(HttpURLConnection connection) throws Exception {
        InputStream stream = connection.getResponseCode() >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();

        if (stream == null) return "";

        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
        StringBuilder body = new StringBuilder();

        String line;
        while ((line = reader.readLine()) != null) body.append(line);
        reader.close();

        return body.toString();
    }

    /**
     * Pulls one string value out of a JSON body.
     *
     * <p>Only flat string fields are needed here, out of responses of a known shape, which does not
     * warrant parsing the whole document.
     */
    private static String jsonString(String json, String name) {
        if (json == null) return null;

        String needle = "\"" + name + "\"";
        int start = json.indexOf(needle);
        if (start < 0) return null;

        start = json.indexOf('"', json.indexOf(':', start + needle.length()) + 1);
        if (start < 0) return null;

        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;

        return json.substring(start + 1, end);
    }

    private static void store(String key, String value) {
        if (value != null && !value.isEmpty()) Credentials.put(key, value);
    }

    private static void report(Activity activity, Callback callback, boolean success, String message) {
        // Recorded before the callback, so the outcome is still readable if the form was torn down
        // while the browser held the foreground.
        Credentials.put(Credentials.KEY_LAST_STATUS, message);
        Log.i(TAG, "finished: " + message);

        activity.runOnUiThread(() -> callback.onFinished(success, message));
    }

    private static void close(ServerSocket server) {
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }
}
