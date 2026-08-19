package app.morphe.lbc.plugins;

import app.morphe.lbc.net.HttpBridge;
import app.morphe.lbc.plugin.Plugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Filtres côté client, appliqués après l'API et avant l'affichage.
 *
 * <p>Ce que l'app ne sait pas faire et qui est traité ici :
 * <ul>
 *   <li>exclusion par mots-clés (titre + description), y compris les « lot », « pièces », « HS » ;</li>
 *   <li>bornes de prix strictes, avec exclusion des annonces sans prix ;</li>
 *   <li>exclusion des vendeurs professionnels / boutiques ;</li>
 *   <li>dédoublonnage des annonces republiées (même titre + même prix) ;</li>
 *   <li>liste noire de vendeurs.</li>
 * </ul>
 *
 * <p>Réglages (`Prefs`, portée `plugin.builtin.filters`) :
 * `excludeKeywords`, `requireKeywords`, `minPrice`, `maxPrice`, `hidePriceless`,
 * `excludePro`, `dedupe`, `blockedSellers`.
 */
public final class BetterFiltersPlugin extends Plugin {

    private volatile boolean enabled;

    @Override
    public void onStart() {
        enabled = true;
        HttpBridge.onResponse("", exchange -> enabled ? filter(exchange.responseBody) : null);
        log.i("actif");
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

        List<String> excluded = lower(prefs.getList("excludeKeywords"));
        List<String> required = lower(prefs.getList("requireKeywords"));
        Set<String> blockedSellers = new HashSet<>(lower(prefs.getList("blockedSellers")));
        int minPrice = prefs.getInt("minPrice", 0);
        int maxPrice = prefs.getInt("maxPrice", 0); // 0 = pas de plafond
        boolean hidePriceless = prefs.getBoolean("hidePriceless", false);
        boolean excludePro = prefs.getBoolean("excludePro", false);
        boolean dedupe = prefs.getBoolean("dedupe", true);

        if (excluded.isEmpty() && required.isEmpty() && blockedSellers.isEmpty()
                && minPrice == 0 && maxPrice == 0 && !hidePriceless && !excludePro && !dedupe) {
            return null; // rien de configuré
        }

        JSONArray kept = new JSONArray();
        Set<String> seen = new HashSet<>();
        int removed = 0;

        for (int i = 0; i < document.ads.length(); i++) {
            JSONObject ad = document.ads.optJSONObject(i);
            if (ad == null) {
                continue;
            }
            if (rejects(ad, excluded, required, blockedSellers, minPrice, maxPrice, hidePriceless, excludePro)) {
                removed++;
                continue;
            }
            if (dedupe && !seen.add(signature(ad))) {
                removed++;
                continue;
            }
            kept.put(ad);
        }

        if (removed == 0) {
            return null;
        }
        log.d(removed + " annonce(s) filtrée(s) sur " + document.ads.length());
        return document.replaceAds(kept, body);
    }

    private boolean rejects(JSONObject ad, List<String> excluded, List<String> required,
                            Set<String> blockedSellers, int minPrice, int maxPrice,
                            boolean hidePriceless, boolean excludePro) {
        String haystack = (AdsDocument.text(ad, "subject", "title") + " "
                + AdsDocument.text(ad, "body", "description")).toLowerCase(Locale.ROOT);

        for (String keyword : excluded) {
            if (haystack.contains(keyword)) {
                return true;
            }
        }
        if (!required.isEmpty()) {
            boolean found = false;
            for (String keyword : required) {
                if (haystack.contains(keyword)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return true;
            }
        }

        double price = AdsDocument.price(ad);
        if (price < 0) {
            if (hidePriceless) {
                return true;
            }
        } else {
            if (minPrice > 0 && price < minPrice) {
                return true;
            }
            if (maxPrice > 0 && price > maxPrice) {
                return true;
            }
        }

        JSONObject owner = ad.optJSONObject("owner");
        if (owner != null) {
            if (excludePro) {
                String type = AdsDocument.text(owner, "type", "user_type").toLowerCase(Locale.ROOT);
                if (type.contains("pro") || type.contains("store") || owner.has("store_id")) {
                    return true;
                }
            }
            if (!blockedSellers.isEmpty()) {
                String name = AdsDocument.text(owner, "name", "user_id", "store_id").toLowerCase(Locale.ROOT);
                if (!name.isEmpty() && blockedSellers.contains(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Une annonce republiée garde son titre et son prix mais change d'identifiant. */
    private static String signature(JSONObject ad) {
        return AdsDocument.text(ad, "subject", "title").trim().toLowerCase(Locale.ROOT)
                + "|" + AdsDocument.price(ad)
                + "|" + AdsDocument.text(ad, "owner_id", "user_id");
    }

    private static List<String> lower(List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            values.set(i, values.get(i).toLowerCase(Locale.ROOT));
        }
        return values;
    }
}
