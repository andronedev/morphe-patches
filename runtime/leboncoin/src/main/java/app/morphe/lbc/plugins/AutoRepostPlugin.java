package app.morphe.lbc.plugins;

import android.os.Handler;
import android.os.Looper;

import app.morphe.lbc.Lbc;
import app.morphe.lbc.net.HttpBridge;
import app.morphe.lbc.plugin.Plugin;

import org.json.JSONObject;

import java.util.List;

/**
 * Republication automatique d'annonces : suppression puis redépôt.
 *
 * <p><b>Préférer {@link AutoProlongPlugin}.</b> La prolongation native atteint le même but sans
 * supprimer l'annonce, sans doublon et sans perdre son ancienneté, ses statistiques ni ses
 * messages. Ce plugin existe parce que le supprimer/redéposer a été explicitement demandé ; il est
 * désactivé par défaut.
 *
 * <h3>Comment ça marche</h3>
 * <ol>
 *   <li>Quand tu déposes une annonce normalement, {@link HttpBridge} capture la requête de dépôt et
 *       l'identifiant renvoyé : c'est le modèle qui sera rejoué. On ne reconstruit pas le schéma de
 *       l'API de dépôt, qui est volumineux et versionné.</li>
 *   <li>Quand une annonce est due : suppression, puis rejeu du modèle capturé, puis suivi du
 *       nouvel identifiant.</li>
 * </ol>
 *
 * <h3>Garde-fous (volontaires)</h3>
 * Republier pour remonter une annonce est contraire aux CGU leboncoin (c'est leur option payante
 * « remontée »), les doublons sont détectés côté serveur et DataDome voit passer le trafic : un
 * rythme agressif se paie par une suspension de compte. Par défaut :
 * <ul>
 *   <li>{@code dryRun} activé — le plugin journalise ce qu'il ferait, sans rien envoyer ;</li>
 *   <li>intervalle minimum de {@value #MIN_INTERVAL_HOURS} h par annonce, plancher non contournable ;</li>
 *   <li>{@value #MAX_REPOSTS_PER_DAY} republications par jour maximum, toutes annonces confondues ;</li>
 *   <li>une seule annonce traitée par cycle ;</li>
 *   <li>exécution uniquement quand l'app tourne (pas de service en arrière-plan en v1).</li>
 * </ul>
 */
public final class AutoRepostPlugin extends Plugin {

    static final int MIN_INTERVAL_HOURS = 12;
    static final int MAX_REPOSTS_PER_DAY = 4;

    private static final long CHECK_INTERVAL_MS = 15 * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ApiClient api = new ApiClient();

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
        api.captureFrom("leboncoin");

        String depositEndpoint = Lbc.bindings().endpoint("adCreate", "");
        if (depositEndpoint.isEmpty()) {
            log.w("endpoint de dépôt inconnu (bindings.json incomplet) : capture désactivée. "
                    + "Il faut une capture réseau d'un dépôt réel pour le renseigner.");
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
            if (!api.hasSession() && !dryRun) {
                log.w("aucune session capturée : ouvre l'app une fois, puis réessaie");
                return;
            }

            List<RepostStore.Entry> entries = store.all();
            for (RepostStore.Entry entry : entries) {
                if (!enabled || !entry.isDue(now, MIN_INTERVAL_HOURS)) {
                    continue;
                }
                if (dryRun) {
                    log.i("[dryRun] republierait l'annonce " + entry.adId);
                    entry.lastRepostAt = now;
                    store.update(entry);
                    continue;
                }
                repost(entry, now);
                return; // une seule republication par cycle, volontairement
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
            // TODO(capture) : la charge utile exacte de /manual/delete/ads reste à confirmer par
            // capture réseau — l'endpoint prend une liste d'annonces, pas un identifiant en URL.
            String deletePayload = new JSONObject()
                    .put("ads", new org.json.JSONArray().put(new JSONObject().put("ad_id", entry.adId)))
                    .toString();

            ApiClient.Response deleted = api.send("POST", deleteEndpoint.replace("{id}", entry.adId), deletePayload);
            if (deleted.looksBlocked()) {
                log.w("suppression bloquée par la protection anti-bot (HTTP " + deleted.status
                        + ") : il faut passer par le client de l'app, cf. ARCHITECTURE.md §3");
                return;
            }
            if (!deleted.isSuccess()) {
                log.w("suppression refusée (HTTP " + deleted.status + ") pour " + entry.adId);
                return;
            }

            ApiClient.Response created = api.send("POST", createEndpoint, entry.payload);
            if (!created.isSuccess()) {
                // L'annonce a été supprimée mais pas recréée : il faut le dire fort.
                log.w("ATTENTION : annonce " + entry.adId + " supprimée mais redépôt refusé (HTTP "
                        + created.status + "). Redépose-la manuellement.");
                return;
            }
            String newId = AdsDocument.text(new JSONObject(created.body), "ad_id", "list_id", "id");

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

    private static String pathOf(String endpoint) {
        int scheme = endpoint.indexOf("://");
        int start = scheme < 0 ? 0 : endpoint.indexOf('/', scheme + 3);
        return start < 0 ? endpoint : endpoint.substring(start);
    }
}
