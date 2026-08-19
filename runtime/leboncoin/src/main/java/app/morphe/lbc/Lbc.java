package app.morphe.lbc;

import android.content.Context;

import app.morphe.lbc.hook.Hooks;
import app.morphe.lbc.net.HttpBridge;
import app.morphe.lbc.plugin.PluginManager;

/**
 * Point d'entrée du runtime.
 *
 * <p>Appelé par le code injecté dans `Application.attachBaseContext()` par le patch Morphe.
 * Contrat figé (l'injecteur l'appelle par réflexion, ne pas renommer) :
 *
 * <pre>app.morphe.lbc.Lbc.init(android.content.Context)</pre>
 *
 * <p>Règle d'or : <b>aucune exception ne doit remonter</b>. Si le runtime échoue, l'app doit
 * démarrer normalement, sans mod. Un mod qui empêche de lire ses annonces est pire que pas de mod.
 */
public final class Lbc {

    /** Incrémenté à chaque changement incompatible de l'API plugin (cf. `minRuntime`). */
    public static final int RUNTIME_API = 1;

    private static final Logger LOG = new Logger("Lbc");

    private static volatile boolean initialised;
    private static volatile boolean debug;
    private static Context context;
    private static Bindings bindings;
    private static Prefs prefs;
    private static PluginManager pluginManager;

    private Lbc() {
    }

    public static void init(Context appContext) {
        if (initialised) {
            return;
        }
        initialised = true;
        try {
            context = appContext.getApplicationContext() != null
                    ? appContext.getApplicationContext()
                    : appContext;
            prefs = new Prefs(context, "");
            debug = prefs.getBoolean("debug", false);

            if (!prefs.getBoolean("enabled", true)) {
                LOG.i("runtime désactivé par l'utilisateur");
                return;
            }

            bindings = Bindings.load(context);
            LOG.i("démarrage — API " + RUNTIME_API + ", APK " + bindings.apkVersion()
                    + (bindings.isEmpty() ? " (bindings vides)" : ""));

            if (!Hooks.init()) {
                LOG.w("backend de hook indisponible : seules les fonctions réseau resteront actives");
            }

            HttpBridge.init(context);

            pluginManager = new PluginManager(context, prefs);
            pluginManager.loadAll();

            LOG.i("prêt — " + pluginManager.startedCount() + " plugin(s) actif(s)");
        } catch (Throwable error) {
            LOG.e("échec d'initialisation, l'app continue sans mod", error);
        }
    }

    public static boolean isDebug() {
        return debug;
    }

    public static Context context() {
        return context;
    }

    public static Bindings bindings() {
        return bindings == null ? Bindings.empty() : bindings;
    }

    public static Prefs prefs() {
        return prefs;
    }

    public static PluginManager plugins() {
        return pluginManager;
    }

    /** ClassLoader de l'app leboncoin (pas celui du runtime) : sert à atteindre ses classes. */
    public static ClassLoader appClassLoader() {
        return context == null ? Lbc.class.getClassLoader() : context.getClassLoader();
    }
}
