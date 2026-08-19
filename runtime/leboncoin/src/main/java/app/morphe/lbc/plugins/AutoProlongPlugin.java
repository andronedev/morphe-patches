package app.morphe.lbc.plugins;

import android.os.Handler;
import android.os.Looper;

import app.morphe.lbc.Lbc;
import app.morphe.lbc.net.HttpBridge;
import app.morphe.lbc.plugin.Plugin;

import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Prolongation automatique des annonces — la voie recommandée pour garder ses annonces vivantes.
 *
 * <p>L'app expose une action « prolonger » de première partie
 * (`POST /api/pintad/v1/public/manual/prolongation/{list_id}`). L'automatiser atteint le même
 * objectif qu'un supprimer/redéposer, mais sans doublon, sans perdre l'ancienneté de l'annonce,
 * ses statistiques et ses messages, et sans aller contre les CGU. Voir `AutoRepostPlugin` pour
 * l'autre approche, plus risquée.
 *
 * <p>Réglages (portée `plugin.builtin.prolong`) : `adIds` (liste, vide = toutes les annonces vues
 * dans « mes annonces »), `intervalDays` (défaut 7), `dryRun` (défaut activé).
 */
public final class AutoProlongPlugin extends Plugin {

    private static final int MIN_INTERVAL_DAYS = 1;
    private static final long CHECK_INTERVAL_MS = 30 * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ApiClient api = new ApiClient();
    private final Set<String> knownAdIds = new LinkedHashSet<>();

    private volatile boolean enabled;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!enabled) {
                return;
            }
            new Thread(AutoProlongPlugin.this::runDue, "morphe-prolong").start();
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    @Override
    public void onStart() {
        enabled = true;
        api.captureFrom("leboncoin");

        // Repère les annonces de l'utilisateur au passage, pour ne rien lui faire saisir.
        HttpBridge.onResponse("owner_listing", exchange -> {
            collectAdIds(exchange.responseBody);
            return null;
        });

        handler.postDelayed(tick, CHECK_INTERVAL_MS);
        log.i("actif — dryRun=" + prefs.getBoolean("dryRun", true)
                + ", intervalle " + intervalDays() + " j");
    }

    @Override
    public void onStop() {
        enabled = false;
        handler.removeCallbacks(tick);
    }

    private int intervalDays() {
        return Math.max(MIN_INTERVAL_DAYS, prefs.getInt("intervalDays", 7));
    }

    private void collectAdIds(String body) {
        AdsDocument document = AdsDocument.parse(body);
        if (document == null) {
            return;
        }
        for (int i = 0; i < document.ads.length(); i++) {
            JSONObject ad = document.ads.optJSONObject(i);
            if (ad == null) {
                continue;
            }
            String id = AdsDocument.text(ad, "list_id", "ad_id", "id");
            if (!id.isEmpty() && knownAdIds.add(id)) {
                log.d("annonce repérée : " + id);
            }
        }
    }

    /** Annonces à prolonger : celles configurées, sinon celles repérées dans « mes annonces ». */
    private Set<String> targets() {
        List<String> configured = prefs.getList("adIds");
        return configured.isEmpty() ? new LinkedHashSet<>(knownAdIds) : new LinkedHashSet<>(configured);
    }

    private void runDue() {
        try {
            long now = System.currentTimeMillis();
            long interval = intervalDays() * 86_400_000L;
            boolean dryRun = prefs.getBoolean("dryRun", true);

            String template = Lbc.bindings().endpoint("adProlong", "");
            if (template.isEmpty()) {
                log.w("endpoint de prolongation absent de bindings.json");
                return;
            }
            if (!api.hasSession() && !dryRun) {
                log.w("session non capturée : ouvre l'app et consulte « mes annonces », puis réessaie");
                return;
            }

            for (String adId : targets()) {
                if (!enabled) {
                    return;
                }
                long last = prefs.getLong("last." + adId, 0);
                if (now - last < interval) {
                    continue;
                }
                if (dryRun) {
                    log.i("[dryRun] prolongerait l'annonce " + adId);
                    prefs.setLong("last." + adId, now);
                    continue;
                }

                ApiClient.Response response = api.send("POST", template.replace("{id}", adId), "{}");
                if (response.isSuccess()) {
                    prefs.setLong("last." + adId, now);
                    log.i("annonce " + adId + " prolongée");
                } else if (response.looksBlocked()) {
                    // Attendu tant que les appels ne passent pas par le client OkHttp de l'app.
                    log.w("prolongation bloquée par la protection anti-bot (HTTP " + response.status
                            + ") : il faut passer par le client de l'app, cf. ARCHITECTURE.md §3");
                    return;
                } else {
                    log.w("prolongation refusée pour " + adId + " (HTTP " + response.status + ")");
                }
                return; // une annonce par cycle
            }
        } catch (Throwable error) {
            log.e("cycle de prolongation en échec", error);
        }
    }
}
