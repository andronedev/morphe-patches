package app.morphe.lbc.plugins;

import app.morphe.lbc.net.HttpBridge;
import app.morphe.lbc.plugin.Plugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/**
 * Suppression de la publicité.
 *
 * <p>Deux niveaux :
 * <ol>
 *   <li><b>réseau</b> — les appels aux régies (Criteo, AppLovin, Smart AdServer, Teads, ...) sont
 *       court-circuités par {@link HttpBridge}, ce qui économise aussi de la batterie et des données ;</li>
 *   <li><b>contenu</b> — les entrées sponsorisées sont retirées du JSON de résultats avant que
 *       l'app ne les affiche.</li>
 * </ol>
 *
 * <p>Les marqueurs de sponsorisation ne sont pas documentés et bougent : ils sont donc
 * configurables (`markers`), avec des valeurs par défaut à confirmer sur des réponses réelles
 * (cf. `tools/lbc-recon.py` et le mode debug qui journalise ce qui est retiré).
 */
public final class NoAdsPlugin extends Plugin {

    /** Clés booléennes qui, à vrai, désignent une insertion publicitaire. */
    private static final List<String> DEFAULT_FLAG_MARKERS = Arrays.asList(
            "is_sponsored", "sponsored", "is_boosted", "has_option_urgent_boost");

    /** Valeurs de `ad_type` / `type` correspondant à de la pub plutôt qu'à une annonce. */
    private static final List<String> DEFAULT_TYPE_MARKERS = Arrays.asList(
            "sponsored", "advertising", "ad_sense", "native_ad", "banner");

    private volatile boolean enabled;

    @Override
    public void onStart() {
        enabled = true;

        for (String host : prefs.getList("extraBlockedHosts")) {
            HttpBridge.blockHost(host);
        }

        HttpBridge.onResponse("", exchange -> {
            if (!enabled || !prefs.getBoolean("filterContent", true)) {
                return null;
            }
            return filter(exchange.responseBody);
        });

        log.i("actif — " + HttpBridge.blockedHosts().size() + " hôtes publicitaires bloqués");
    }

    @Override
    public void onStop() {
        enabled = false;
    }

    private String filter(String body) {
        AdsDocument document = AdsDocument.parse(body);
        if (document == null) {
            return null;
        }

        List<String> flagMarkers = prefs.getList("flagMarkers");
        if (flagMarkers.isEmpty()) {
            flagMarkers = DEFAULT_FLAG_MARKERS;
        }
        List<String> typeMarkers = prefs.getList("typeMarkers");
        if (typeMarkers.isEmpty()) {
            typeMarkers = DEFAULT_TYPE_MARKERS;
        }

        JSONArray kept = new JSONArray();
        int removed = 0;
        for (int i = 0; i < document.ads.length(); i++) {
            JSONObject ad = document.ads.optJSONObject(i);
            if (ad == null) {
                continue;
            }
            if (isSponsored(ad, flagMarkers, typeMarkers)) {
                removed++;
                log.d("retiré: " + AdsDocument.text(ad, "subject", "title", "list_id"));
            } else {
                kept.put(ad);
            }
        }
        if (removed == 0) {
            return null;
        }
        log.d(removed + " insertion(s) publicitaire(s) retirée(s)");
        return document.replaceAds(kept, body);
    }

    private boolean isSponsored(JSONObject ad, List<String> flagMarkers, List<String> typeMarkers) {
        for (String marker : flagMarkers) {
            if (ad.optBoolean(marker, false)) {
                return true;
            }
        }
        String type = AdsDocument.text(ad, "ad_type", "type", "kind").toLowerCase(java.util.Locale.ROOT);
        for (String marker : typeMarkers) {
            if (!type.isEmpty() && type.contains(marker)) {
                return true;
            }
        }
        // Une entrée sans identifiant d'annonce mais avec une URL de tracking est un emplacement pub.
        boolean hasId = !AdsDocument.text(ad, "list_id", "ad_id", "id").isEmpty();
        boolean hasTracking = ad.has("impression_url") || ad.has("click_url") || ad.has("adserver");
        return !hasId && hasTracking;
    }
}
