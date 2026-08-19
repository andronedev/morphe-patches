package app.morphe.lbc.plugin;

import org.json.JSONObject;

/** Métadonnées d'un plugin, lues depuis `plugin.json`. */
public final class PluginInfo {

    public final String id;
    public final String name;
    public final String version;
    public final String author;
    public final String description;
    public final String entry;
    public final int minRuntime;
    public final boolean builtin;

    public PluginInfo(String id, String name, String version, String author, String description,
                      String entry, int minRuntime, boolean builtin) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.author = author;
        this.description = description;
        this.entry = entry;
        this.minRuntime = minRuntime;
        this.builtin = builtin;
    }

    public static PluginInfo builtin(String id, String name, String description) {
        return new PluginInfo(id, name, "runtime", "Morphe", description, "", 1, true);
    }

    public static PluginInfo fromJson(String json) throws Exception {
        JSONObject object = new JSONObject(json);
        String id = object.getString("id");
        String entry = object.getString("entry");
        if (id.trim().isEmpty() || entry.trim().isEmpty()) {
            throw new IllegalArgumentException("plugin.json: 'id' et 'entry' sont obligatoires");
        }
        return new PluginInfo(
                id,
                object.optString("name", id),
                object.optString("version", "0"),
                object.optString("author", "inconnu"),
                object.optString("description", ""),
                entry,
                object.optInt("minRuntime", 1),
                false);
    }

    @Override
    public String toString() {
        return name + " " + version + " (" + id + ")";
    }
}
