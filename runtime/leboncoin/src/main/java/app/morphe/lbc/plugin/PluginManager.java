package app.morphe.lbc.plugin;

import android.content.Context;

import app.morphe.lbc.Lbc;
import app.morphe.lbc.Logger;
import app.morphe.lbc.Prefs;
import app.morphe.lbc.plugins.AutoProlongPlugin;
import app.morphe.lbc.plugins.AutoRepostPlugin;
import app.morphe.lbc.plugins.BetterFiltersPlugin;
import app.morphe.lbc.plugins.NoAdsPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexClassLoader;

/**
 * Chargement et cycle de vie des plugins.
 *
 * <p>Deux origines :
 * <ul>
 *   <li><b>intégrés</b> — livrés avec le runtime ({@link NoAdsPlugin}, {@link BetterFiltersPlugin},
 *       {@link AutoRepostPlugin}) ; ils servent aussi d'exemples de référence ;</li>
 *   <li><b>externes</b> — zips (`plugin.json` + `classes.dex`) déposés dans
 *       `Android/data/fr.leboncoin/files/morphe/plugins/`, chargés via {@link DexClassLoader}.</li>
 * </ul>
 *
 * <p>Un plugin qui échoue est désactivé et journalisé : il ne doit jamais empêcher les autres
 * de démarrer, ni l'app de fonctionner.
 */
public final class PluginManager {

    private static final Logger LOG = new Logger("Plugins");

    private final Context context;
    private final Prefs prefs;
    private final Map<String, Plugin> plugins = new LinkedHashMap<>();

    public PluginManager(Context context, Prefs prefs) {
        this.context = context;
        this.prefs = prefs;
    }

    public File pluginsDir() {
        File base = context.getExternalFilesDir("morphe");
        if (base == null) {
            base = new File(context.getFilesDir(), "morphe");
        }
        File dir = new File(base, "plugins");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private File codeCacheDir() {
        File dir = new File(context.getCodeCacheDir(), "morphe-plugins");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    public void loadAll() {
        registerBuiltin(new NoAdsPlugin(), PluginInfo.builtin(
                "builtin.noads", "No Ads",
                "Retire les annonces sponsorisées et coupe les appels aux régies publicitaires."));
        registerBuiltin(new BetterFiltersPlugin(), PluginInfo.builtin(
                "builtin.filters", "Meilleurs filtres",
                "Filtres côté client : mots-clés exclus, prix réel, exclusion des pros, dédoublonnage."));
        registerBuiltin(new AutoProlongPlugin(), PluginInfo.builtin(
                "builtin.prolong", "Auto-prolongation",
                "Prolonge automatiquement les annonces via l'action native de l'app (recommandé)."));
        registerBuiltin(new AutoRepostPlugin(), PluginInfo.builtin(
                "builtin.repost", "Auto-repost",
                "Republie les annonces suivies (suppression puis redépôt). Contraire aux CGU : "
                        + "préférer l'auto-prolongation."));

        loadExternal();

        for (Plugin plugin : new ArrayList<>(plugins.values())) {
            if (isEnabled(plugin.info())) {
                start(plugin);
            }
        }
    }

    private void registerBuiltin(Plugin plugin, PluginInfo info) {
        plugin.attach(info, context, prefs.scoped("plugin." + info.id));
        plugins.put(info.id, plugin);
    }

    private void loadExternal() {
        File dir = pluginsDir();
        File[] files = dir.listFiles((unused, name) -> name.endsWith(".zip"));
        if (files == null || files.length == 0) {
            LOG.i("aucun plugin externe dans " + dir);
            return;
        }
        for (File file : files) {
            try {
                loadExternal(file);
            } catch (Throwable error) {
                LOG.e("plugin ignoré : " + file.getName(), error);
            }
        }
    }

    private void loadExternal(File zip) throws Exception {
        PluginInfo info;
        File dex = new File(codeCacheDir(), zip.getName() + ".dex");

        try (ZipFile archive = new ZipFile(zip)) {
            ZipEntry manifest = archive.getEntry("plugin.json");
            ZipEntry code = archive.getEntry("classes.dex");
            if (manifest == null || code == null) {
                throw new IllegalArgumentException("zip incomplet : plugin.json et classes.dex requis");
            }
            try (InputStream in = archive.getInputStream(manifest)) {
                info = PluginInfo.fromJson(readUtf8(in));
            }
            if (info.minRuntime > Lbc.RUNTIME_API) {
                throw new IllegalStateException(
                        "requiert l'API runtime " + info.minRuntime + ", disponible : " + Lbc.RUNTIME_API);
            }
            if (plugins.containsKey(info.id)) {
                throw new IllegalStateException("identifiant déjà pris : " + info.id);
            }
            try (InputStream in = archive.getInputStream(code);
                 OutputStream out = new FileOutputStream(dex)) {
                copy(in, out);
            }
        }

        // Parent = ClassLoader de l'app : le plugin voit le runtime ET les classes de leboncoin.
        DexClassLoader loader = new DexClassLoader(
                dex.getAbsolutePath(), codeCacheDir().getAbsolutePath(), null, getClass().getClassLoader());
        Class<?> entry = loader.loadClass(info.entry);
        if (!Plugin.class.isAssignableFrom(entry)) {
            throw new IllegalArgumentException(info.entry + " n'étend pas Plugin");
        }
        Plugin plugin = (Plugin) entry.getDeclaredConstructor().newInstance();
        plugin.attach(info, context, prefs.scoped("plugin." + info.id));
        plugins.put(info.id, plugin);
        LOG.i("chargé : " + info);
    }

    // ------------------------------------------------------------------ cycle de vie

    public boolean isEnabled(PluginInfo info) {
        // Les plugins qui écrivent côté serveur ne démarrent que sur activation explicite.
        boolean writesToServer = "builtin.repost".equals(info.id) || "builtin.prolong".equals(info.id);
        return prefs.getBoolean("enabled." + info.id, !writesToServer);
    }

    public void setEnabled(String id, boolean enabled) {
        prefs.setBoolean("enabled." + id, enabled);
        Plugin plugin = plugins.get(id);
        if (plugin == null) {
            return;
        }
        if (enabled) {
            start(plugin);
        } else {
            stop(plugin);
        }
    }

    private void start(Plugin plugin) {
        if (plugin.isStarted()) {
            return;
        }
        try {
            plugin.onStart();
            plugin.markStarted(true);
            LOG.i("démarré : " + plugin.info());
        } catch (Throwable error) {
            LOG.e("démarrage impossible : " + plugin.info() + " — plugin désactivé", error);
            prefs.setBoolean("enabled." + plugin.info().id, false);
        }
    }

    private void stop(Plugin plugin) {
        if (!plugin.isStarted()) {
            return;
        }
        try {
            plugin.onStop();
        } catch (Throwable error) {
            LOG.e("arrêt en erreur : " + plugin.info(), error);
        } finally {
            plugin.markStarted(false);
        }
    }

    public Collection<Plugin> all() {
        return new ArrayList<>(plugins.values());
    }

    public List<Plugin> started() {
        List<Plugin> out = new ArrayList<>();
        for (Plugin plugin : plugins.values()) {
            if (plugin.isStarted()) {
                out.add(plugin);
            }
        }
        return out;
    }

    public int startedCount() {
        return started().size();
    }

    // ---------------------------------------------------------------------- utilitaires

    private static String readUtf8(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toString("UTF-8");
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }
}
