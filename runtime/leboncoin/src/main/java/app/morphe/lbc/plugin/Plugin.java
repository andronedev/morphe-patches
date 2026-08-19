package app.morphe.lbc.plugin;

import android.content.Context;

import app.morphe.lbc.Logger;
import app.morphe.lbc.Prefs;

/**
 * Classe de base d'un plugin.
 *
 * <p>Un plugin externe est un zip contenant `plugin.json` + `classes.dex`, déposé dans
 * `Android/data/fr.leboncoin/files/morphe/plugins/`. Exemple minimal :
 *
 * <pre>
 * public final class MonPlugin extends Plugin {
 *     &#64;Override public void onStart() {
 *         HttpBridge.onResponse("/finder/search", exchange -&gt; null);
 *     }
 * }
 * </pre>
 *
 * <p>Contrat : le constructeur doit être public et sans argument. {@link #onStart()} est appelé
 * sur le thread principal, au démarrage de l'app — pas d'I/O bloquante dedans.
 */
public abstract class Plugin {

    protected PluginInfo info;
    protected Context context;
    protected Prefs prefs;
    protected Logger log;

    private boolean started;

    final void attach(PluginInfo info, Context context, Prefs prefs) {
        this.info = info;
        this.context = context;
        this.prefs = prefs;
        this.log = new Logger(info.id);
    }

    /** Appelé une fois, à l'activation. */
    public void onStart() {
    }

    /** Appelé à la désactivation. Doit être idempotent. */
    public void onStop() {
    }

    /** Réglages par défaut proposés dans l'écran de configuration (phase 2). */
    public void onConfigure() {
    }

    public final PluginInfo info() {
        return info;
    }

    public final boolean isStarted() {
        return started;
    }

    final void markStarted(boolean value) {
        started = value;
    }
}
