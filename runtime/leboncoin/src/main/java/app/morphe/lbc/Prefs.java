package app.morphe.lbc;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Réglages du runtime et des plugins.
 *
 * <p>Stockés dans un fichier séparé de ceux de l'app pour ne rien casser côté leboncoin,
 * et pour survivre à un `clear data` partiel.
 */
public final class Prefs {

    private static final String FILE = "morphe_lbc";

    private final SharedPreferences prefs;
    private final String namespace;

    public Prefs(Context context, String namespace) {
        this.prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        this.namespace = namespace.isEmpty() ? "" : namespace + ".";
    }

    /** Vue préfixée, pour qu'un plugin ne puisse pas marcher sur les clés d'un autre. */
    public Prefs scoped(String childNamespace) {
        return new Prefs(prefs, namespace + childNamespace);
    }

    private Prefs(SharedPreferences prefs, String namespace) {
        this.prefs = prefs;
        this.namespace = namespace.endsWith(".") ? namespace : namespace + ".";
    }

    private String key(String key) {
        return namespace + key;
    }

    public boolean getBoolean(String key, boolean fallback) {
        return prefs.getBoolean(key(key), fallback);
    }

    public void setBoolean(String key, boolean value) {
        prefs.edit().putBoolean(key(key), value).apply();
    }

    public int getInt(String key, int fallback) {
        return prefs.getInt(key(key), fallback);
    }

    public void setInt(String key, int value) {
        prefs.edit().putInt(key(key), value).apply();
    }

    public long getLong(String key, long fallback) {
        return prefs.getLong(key(key), fallback);
    }

    public void setLong(String key, long value) {
        prefs.edit().putLong(key(key), value).apply();
    }

    public String getString(String key, String fallback) {
        return prefs.getString(key(key), fallback);
    }

    public void setString(String key, String value) {
        prefs.edit().putString(key(key), value).apply();
    }

    public Set<String> getStringSet(String key) {
        Set<String> stored = prefs.getStringSet(key(key), null);
        return stored == null ? Collections.emptySet() : new LinkedHashSet<>(stored);
    }

    public void setStringSet(String key, Set<String> value) {
        prefs.edit().putStringSet(key(key), new LinkedHashSet<>(value)).apply();
    }

    /** Liste séparée par des virgules, pratique pour une saisie utilisateur libre. */
    public java.util.List<String> getList(String key) {
        String raw = getString(key, "");
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
