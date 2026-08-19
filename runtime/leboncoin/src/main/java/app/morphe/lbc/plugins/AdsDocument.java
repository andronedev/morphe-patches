package app.morphe.lbc.plugins;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Accès tolérant au JSON de résultats de recherche.
 *
 * <p>Le nom exact du tableau d'annonces n'est pas figé selon les endpoints (`ads`, `items`,
 * `results`, ...) et il change parfois entre versions de l'API. Plutôt que de coder un schéma en
 * dur, on cherche le premier tableau d'objets plausible : un plugin qui ne trouve rien ne fait
 * rien, et l'affichage reste intact.
 */
final class AdsDocument {

    private static final String[] CANDIDATE_KEYS = {"ads", "items", "results", "listings", "data"};

    final JSONObject root;
    final JSONArray ads;
    final String key;

    private AdsDocument(JSONObject root, JSONArray ads, String key) {
        this.root = root;
        this.ads = ads;
        this.key = key;
    }

    /** @return null si le corps n'est pas un JSON contenant une liste d'annonces. */
    static AdsDocument parse(String body) {
        if (body == null || body.isEmpty() || body.charAt(0) != '{') {
            return null;
        }
        try {
            JSONObject root = new JSONObject(body);
            for (String candidate : CANDIDATE_KEYS) {
                JSONArray array = root.optJSONArray(candidate);
                if (isAdArray(array)) {
                    return new AdsDocument(root, array, candidate);
                }
            }
            // Certaines réponses imbriquent la liste sous un objet intermédiaire.
            for (java.util.Iterator<String> it = root.keys(); it.hasNext(); ) {
                JSONObject nested = root.optJSONObject(it.next());
                if (nested == null) {
                    continue;
                }
                for (String candidate : CANDIDATE_KEYS) {
                    JSONArray array = nested.optJSONArray(candidate);
                    if (isAdArray(array)) {
                        return new AdsDocument(nested, array, candidate);
                    }
                }
            }
        } catch (Throwable ignored) {
            // Corps non JSON : rien à filtrer.
        }
        return null;
    }

    private static boolean isAdArray(JSONArray array) {
        return array != null && array.length() > 0 && array.optJSONObject(0) != null;
    }

    /** Remplace la liste d'annonces et met à jour le total si présent. */
    String replaceAds(JSONArray filtered, String originalBody) {
        try {
            root.put(key, filtered);
            if (root.has("total")) {
                root.put("total", filtered.length());
            }
            if (root.has("total_all")) {
                root.put("total_all", filtered.length());
            }
            return root.toString();
        } catch (Throwable ignored) {
            return originalBody;
        }
    }

    /** Lecture d'un champ texte, à plusieurs endroits possibles du même objet. */
    static String text(JSONObject ad, String... keys) {
        for (String key : keys) {
            String value = ad.optString(key, null);
            if (value != null && !value.isEmpty() && !"null".equals(value)) {
                return value;
            }
        }
        return "";
    }

    /** Prix en euros : le champ est tantôt un nombre, tantôt un tableau (prix mini/maxi). */
    static double price(JSONObject ad) {
        Object raw = ad.opt("price");
        if (raw instanceof Number) {
            return ((Number) raw).doubleValue();
        }
        if (raw instanceof JSONArray && ((JSONArray) raw).length() > 0) {
            return ((JSONArray) raw).optDouble(0, -1);
        }
        double cents = ad.optDouble("price_cents", -1);
        return cents >= 0 ? cents / 100d : -1;
    }
}
