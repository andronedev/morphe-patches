package app.morphe.lbc.plugins;

import android.os.Handler;
import android.os.Looper;

import app.morphe.lbc.Lbc;
import app.morphe.lbc.net.HttpBridge;
import app.morphe.lbc.plugin.Plugin;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Republication automatique d'annonces (suppression puis redépôt).
 *
 * <h3>Comment ça marche</h3>
 * <ol>
 *   <li>Quand tu déposes une annonce normalement, {@link HttpBridge} capture la requête de dépôt
 *       et l'identifiant renvoyé : c'est le modèle qui sera rejoué (aucun schéma d'API reconstruit
 *       à la main).</li>
 *   <li>Les en-têtes d'authentification sont mémorisés au passage, pour rejouer les appels avec la
 *       même session que l'app.</li>
 *   <li>Quand une annonce est due, le plugin envoie un DELETE puis un POST du modèle capturé, et
 *       met à jour l'identifiant suivi.</li>
 * </ol>
 *
 * <h3>Garde-fous (volontaires)</h3>
 * Republier pour remonter une annonce est contraire aux CGU leboncoin (c'est leur option payante
 * « remontée ») et les doublons sont détectés côté serveur : un rythme agressif se paie par une
 * suspension de compte. Par défaut, donc :
 * <ul>
 *   <li>{@code dryRun} activé — le plugin journalise ce qu'il ferait, sans rien envoyer ;</li>
 *   <li>intervalle minimum de {@value #MIN_INTERVAL_HOURS} h par annonce, plancher non contournable ;</li>
 *   <li>{@value #MAX_REPOSTS_PER_DAY} republications par jour maximum, toutes annonces confondues ;</li>
 *   <li>un décalage aléatoire, pour ne pas republier à heure fixe ;</li>
 *   <li>exécution uniquement quand l'app tourne (pas de service en arrière-plan en v1).</li>
 * </ul>
 */
public final class AutoRepostPlugin extends Plugin {

    static final int MIN_INTERVAL_HOURS = 12;
    static final int MAX_REPOSTS_PER_DAY = 4;

    private static final long CHECK_INTERVAL_MS = 15 * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, String> authHeaders = new HashMap<>();

    private RepostStore store;
    private volatile boolean enabled;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!enabled) {
                return;
            }
            new Thread(AutoRepostPlugin.this::runDueReposts, "morphe-repost").start();
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    @Override
    public void onStart() {
        enabled = true;
        store = new RepostStore(prefs);

        // Mémorise la session courante pour pouvoir rejouer des appels authentifiés.
        HttpBridge.onRequest("leboncoin", exchange -> {
            for (Map.Entry<String, String> header : exchange.requestHeaders().entrySet()) {
                String name = header.getKey().toLowerCase(java.util.Locale.ROOT);
                if (name.equals("authorization") || name.startsWith("api-key")
                        || name.equals("user-agent") || name.startsWith("x-")) {
                    authHeaders.put(header.getKey(), header.getValue());
                }
            }
        });

        String depositEndpoint = Lbc.bindings().endpoint("adCreate", "");
        if (depositEndpoint.isEmpty()) {
            log.w("endpoint de dépôt inconnu (bindings.json incomplet) : capture désactivée");
        } else {
            HttpBridge.onResponse(pathOf(depositEndpoint), exchange -> {
                captureDeposit(exchange.requestBody, exchange.responseBody);
                return null;
            });
        }

        handler.postDelayed(tick, CHECK_INTERVAL_MS);
        log.i("actif — dryRun=" + prefs.getBoolean("dryRun", true)
                + ", " + store.all().size() + " annonce(s) suivie(s)");
    }

    @Override
    public void onStop() {
        enabled = false;
        handler.removeCallbacks(tick);
    }

    // ------------------------------------------------------------------------ capture

    private void captureDeposit(String requestBody, String responseBody) {
        if (requestBody == null || requestBody.isEmpty() || responseBody == null) {
            return;
        }
        try {
            JSONObject response = new JSONObject(responseBody);
            String adId = AdsDocument.text(response, "ad_id", "list_id", "id");
            if (adId.isEmpty()) {
                JSONObject ad = response.optJSONObject("ad");
                if (ad != null) {
                    adId = AdsDocument.text(ad, "ad_id", "list_id", "id");
                }
            }
            if (adId.isEmpty()) {
                log.d("dépôt capturé mais identifiant introuvable dans la réponse");
                return;
            }
            store.capture(adId, requestBody);
            log.i("annonce " + adId + " capturée — active-la dans les réglages pour l'auto-repost");
        } catch (Throwable error) {
            log.e("capture du dépôt impossible", error);
        }
    }

    // ---------------------------------------------------------------------- exécution

    private void runDueReposts() {
        try {
            boolean dryRun = prefs.getBoolean("dryRun", true);
            long now = System.currentTimeMillis();

            if (repostsToday(now) >= MAX_REPOSTS_PER_DAY) {
                log.d("quota quotidien atteint (" + MAX_REPOSTS_PER_DAY + ")");
                return;
            }
            if (authHeaders.isEmpty() && !dryRun) {
                log.w("aucune session capturée pour l'instant : ouvre l'app une fois, puis réessaie");
                return;
            }

            List<RepostStore.Entry> entries = store.all();
            for (RepostStore.Entry entry : entries) {
                if (!enabled || !entry.isDue(now, MIN_INTERVAL_HOURS)) {
                    continue;
                }
                if (dryRun) {
                    log.i("[dryRun] republierait l'annonce " + entry.adId);
                    continue;
                }
                repost(entry, now);
                break; // une seule republication par cycle, volontairement
            }
        } catch (Throwable error) {
            log.e("cycle d'auto-repost en échec", error);
        }
    }

    private void repost(RepostStore.Entry entry, long now) {
        String deleteEndpoint = Lbc.bindings().endpoint("adDelete", "");
        String createEndpoint = Lbc.bindings().endpoint("adCreate", "");
        if (deleteEndpoint.isEmpty() || createEndpoint.isEmpty()) {
            log.w("endpoints de suppression/dépôt inconnus : republication impossible");
            return;
        }
        try {
            int deleteStatus = send("DELETE", deleteEndpoint.replace("{id}", entry.adId), null);
            if (deleteStatus < 200 || deleteStatus >= 300) {
                log.w("suppression refusée (HTTP " + deleteStatus + ") pour " + entry.adId);
                return;
            }
            String created = sendForBody("POST", createEndpoint, entry.payload);
            String newId = created == null ? "" : AdsDocument.text(new JSONObject(created),
                    "ad_id", "list_id", "id");

            entry.lastRepostAt = now;
            entry.repostCount++;
            if (!newId.isEmpty()) {
                entry.adId = newId;
            }
            store.update(entry);
            prefs.setInt("repostsToday", repostsToday(now) + 1);
            prefs.setLong("repostDay", dayOf(now));
            log.i("annonce republiée" + (newId.isEmpty() ? "" : " sous l'identifiant " + newId));
        } catch (Throwable error) {
            log.e("republication en échec pour " + entry.adId, error);
        }
    }

    private int repostsToday(long now) {
        return prefs.getLong("repostDay", 0) == dayOf(now) ? prefs.getInt("repostsToday", 0) : 0;
    }

    private static long dayOf(long millis) {
        return millis / 86_400_000L;
    }

    // ---------------------------------------------------------------------------- HTTP

    private int send(String method, String url, String body) throws Exception {
        HttpURLConnection connection = open(method, url);
        try {
            writeBody(connection, body);
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private String sendForBody(String method, String url, String body) throws Exception {
        HttpURLConnection connection = open(method, url);
        try {
            writeBody(connection, body);
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String method, String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        for (Map.Entry<String, String> header : authHeaders.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        connection.setRequestProperty("Content-Type", "application/json");
        return connection;
    }

    private static void writeBody(HttpURLConnection connection, String body) throws Exception {
        if (body == null || body.isEmpty()) {
            return;
        }
        connection.setDoOutput(true);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes("UTF-8"));
        }
    }

    private static String pathOf(String endpoint) {
        int scheme = endpoint.indexOf("://");
        int start = scheme < 0 ? 0 : endpoint.indexOf('/', scheme + 3);
        return start < 0 ? endpoint : endpoint.substring(start);
    }
}
