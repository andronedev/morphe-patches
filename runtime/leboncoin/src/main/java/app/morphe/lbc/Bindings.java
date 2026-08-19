package app.morphe.lbc;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Noms obfusqués et endpoints résolus au moment du patch.
 *
 * <p>C'est ce qui rend le runtime indépendant de la version de l'app : le patch Morphe a accès
 * au bytecode, il résout les noms via ses fingerprints et écrit
 * `assets/morphe/bindings.json` dans l'APK. Le runtime ne code jamais un nom obfusqué en dur.
 *
 * <p>Format attendu :
 * <pre>
 * {
 *   "apkVersion": "8.x.y",
 *   "classes":   { "okhttp.clientBuilder": "okhttp3.OkHttpClient$Builder", ... },
 *   "methods":   { "okhttp.clientBuilder.build": "build", ... },
 *   "endpoints": { "search": "https://api.leboncoin.fr/finder/search", ... }
 * }
 * </pre>
 */
public final class Bindings {

    public static final String ASSET_PATH = "morphe/bindings.json";

    private static final Logger LOG = new Logger("Bindings");

    private final String apkVersion;
    private final Map<String, String> classes;
    private final Map<String, String> methods;
    private final Map<String, String> endpoints;

    private Bindings(String apkVersion, Map<String, String> classes, Map<String, String> methods,
                     Map<String, String> endpoints) {
        this.apkVersion = apkVersion;
        this.classes = classes;
        this.methods = methods;
        this.endpoints = endpoints;
    }

    /** Bindings vides : le runtime démarre quand même, en mode dégradé. */
    public static Bindings empty() {
        return new Bindings("", Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
    }

    public static Bindings load(Context context) {
        try (InputStream in = context.getAssets().open(ASSET_PATH)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                out.write(chunk, 0, read);
            }
            JSONObject root = new JSONObject(out.toString("UTF-8"));
            return new Bindings(
                    root.optString("apkVersion", ""),
                    toMap(root.optJSONObject("classes")),
                    toMap(root.optJSONObject("methods")),
                    toMap(root.optJSONObject("endpoints")));
        } catch (Throwable error) {
            LOG.w("bindings.json absent ou illisible, mode dégradé (" + error + ")");
            return empty();
        }
    }

    private static Map<String, String> toMap(JSONObject object) {
        Map<String, String> out = new HashMap<>();
        if (object == null) {
            return out;
        }
        for (java.util.Iterator<String> it = object.keys(); it.hasNext(); ) {
            String key = it.next();
            out.put(key, object.optString(key, ""));
        }
        return out;
    }

    public String apkVersion() {
        return apkVersion;
    }

    public boolean isEmpty() {
        return classes.isEmpty() && methods.isEmpty() && endpoints.isEmpty();
    }

    /** Nom de classe résolu, ou {@code null} si le patch ne l'a pas fourni. */
    public String className(String key) {
        String value = classes.get(key);
        return value == null || value.isEmpty() ? null : value;
    }

    public String methodName(String key, String fallback) {
        String value = methods.get(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    public String endpoint(String key, String fallback) {
        String value = endpoints.get(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** Charge une classe de l'app à partir d'une clé de binding. */
    public Class<?> loadClass(ClassLoader loader, String key) {
        String name = className(key);
        if (name == null) {
            return null;
        }
        try {
            return loader.loadClass(name);
        } catch (Throwable error) {
            LOG.w("classe introuvable pour '" + key + "' (" + name + ")");
            return null;
        }
    }
}
