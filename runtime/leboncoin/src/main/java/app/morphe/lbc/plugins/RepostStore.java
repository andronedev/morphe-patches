package app.morphe.lbc.plugins;

import app.morphe.lbc.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Annonces suivies par l'auto-repost, persistées dans les préférences.
 *
 * <p>Le `payload` est la charge utile <b>capturée</b> lors d'un vrai dépôt fait par l'utilisateur :
 * on ne reconstruit pas le schéma de l'API (il est complexe et versionné), on rejoue ce que l'app
 * a réellement envoyé. C'est ce qui rend la fonction robuste aux évolutions du formulaire de dépôt.
 */
final class RepostStore {

    private static final String KEY = "entries";

    private final Prefs prefs;

    RepostStore(Prefs prefs) {
        this.prefs = prefs;
    }

    static final class Entry {

        String adId;
        String payload;
        int intervalHours;
        long lastRepostAt;
        int repostCount;
        boolean enabled;

        JSONObject toJson() throws Exception {
            JSONObject object = new JSONObject();
            object.put("adId", adId);
            object.put("payload", payload);
            object.put("intervalHours", intervalHours);
            object.put("lastRepostAt", lastRepostAt);
            object.put("repostCount", repostCount);
            object.put("enabled", enabled);
            return object;
        }

        static Entry fromJson(JSONObject object) {
            Entry entry = new Entry();
            entry.adId = object.optString("adId", "");
            entry.payload = object.optString("payload", "");
            entry.intervalHours = object.optInt("intervalHours", 24);
            entry.lastRepostAt = object.optLong("lastRepostAt", 0);
            entry.repostCount = object.optInt("repostCount", 0);
            entry.enabled = object.optBoolean("enabled", false);
            return entry;
        }

        boolean isDue(long now, int minIntervalHours) {
            if (!enabled || adId.isEmpty() || payload.isEmpty()) {
                return false;
            }
            long interval = Math.max(intervalHours, minIntervalHours) * 3600_000L;
            return now - lastRepostAt >= interval;
        }
    }

    List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object != null) {
                    out.add(Entry.fromJson(object));
                }
            }
        } catch (Throwable ignored) {
            // Stockage corrompu : on repart d'une liste vide plutôt que de planter au démarrage.
        }
        return out;
    }

    void save(List<Entry> entries) {
        try {
            JSONArray array = new JSONArray();
            for (Entry entry : entries) {
                array.put(entry.toJson());
            }
            prefs.setString(KEY, array.toString());
        } catch (Throwable ignored) {
        }
    }

    /** Enregistre (ou met à jour) la charge utile capturée pour une annonce. */
    void capture(String adId, String payload) {
        List<Entry> entries = all();
        for (Entry entry : entries) {
            if (entry.adId.equals(adId)) {
                entry.payload = payload;
                save(entries);
                return;
            }
        }
        Entry entry = new Entry();
        entry.adId = adId;
        entry.payload = payload;
        entry.intervalHours = 24;
        entry.enabled = false; // l'utilisateur doit activer explicitement
        entries.add(entry);
        save(entries);
    }

    void update(Entry updated) {
        List<Entry> entries = all();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).adId.equals(updated.adId)) {
                entries.set(i, updated);
                save(entries);
                return;
            }
        }
    }
}
