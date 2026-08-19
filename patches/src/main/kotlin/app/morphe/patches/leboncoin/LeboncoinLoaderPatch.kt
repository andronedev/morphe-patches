package app.morphe.patches.leboncoin

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.leboncoin.Constants.COMPATIBILITY_LEBONCOIN
import app.morphe.patches.leboncoin.Constants.RUNTIME_DEX_NAME
import app.morphe.patches.leboncoin.Constants.RUNTIME_ENTRY_CLASS
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getFreeRegisterProvider

/**
 * Injecte le chargeur du runtime Morphe LBC dans `Application.onCreate()`.
 *
 * Modèle Aliucord : le patch reste minimal et stable, toute la logique (no-ads, filtres,
 * auto-repost, plugins tiers) vit dans `runtime.dex`, chargé à l'exécution et remplaçable sans
 * repatcher l'APK. Le code injecté équivaut à :
 *
 * ```java
 * File dir = getExternalFilesDir("morphe");
 * if (dir != null) {
 *     File dex = new File(dir, "runtime.dex");
 *     if (dex.exists()) {
 *         new DexClassLoader(dex.getAbsolutePath(), getCodeCacheDir().getAbsolutePath(), null, getClassLoader())
 *             .loadClass("app.morphe.lbc.Lbc")
 *             .getMethod("init", Context.class)
 *             .invoke(null, this);
 *     }
 * }
 * ```
 *
 * Limites connues, à lever avec un vrai APK sous la main :
 * - **pas de try/catch autour de l'injection** : le smali ajouté ne pose pas de bloc de garde, la
 *   protection se limite aux tests `dir != null` / `dex.exists()`. Une erreur de réflexion (dex
 *   incompatible) ferait donc planter le démarrage. À reprendre via un bloc catch dès que le
 *   comportement est validé sur appareil.
 * - **allocation des registres** : les six registres libres sont demandés à
 *   [getFreeRegisterProvider] ; `invoke-direct` du DexClassLoader en utilise cinq d'un coup et
 *   exige donc des numéros < 16. À vérifier sur la méthode réelle.
 * - **`onCreate` doit être déclarée** dans la classe `Application` de l'app. Si l'app hérite
 *   simplement de `Application` sans redéfinir `onCreate`, il faudra créer la méthode.
 */
@Suppress("unused")
val leboncoinLoaderPatch = bytecodePatch(
    name = "Leboncoin runtime loader",
    description = "Charge le runtime Morphe LBC (plugins, no-ads, filtres, auto-repost) au démarrage de l'app.",
    use = false,
) {
    compatibleWith(COMPATIBILITY_LEBONCOIN)
    dependsOn(leboncoinManifestPatch)

    execute {
        val className = applicationClassName
            ?: throw PatchException("Classe Application inconnue : le patch manifest doit s'exécuter avant.")

        val descriptor = "L${className.replace('.', '/')};"
        val applicationClass = mutableClassDefBy(descriptor)

        val onCreate = applicationClass.methods.firstOrNull {
            it.name == "onCreate" && it.parameters.isEmpty()
        } ?: throw PatchException(
            "$className ne déclare pas onCreate() : choisir un autre point d'injection " +
                "(cf. docs/leboncoin/ARCHITECTURE.md).",
        )

        val registers = onCreate.getFreeRegisterProvider(0, 6)
        val dir = registers.getFreeRegister()
        val file = registers.getFreeRegister()
        val tmp = registers.getFreeRegister()
        val loader = registers.getFreeRegister()
        val cl = registers.getFreeRegister()
        val args = registers.getFreeRegister()

        onCreate.addInstructionsAtControlFlowLabel(
            0,
            """
                const-string v$dir, "morphe"
                invoke-virtual { p0, v$dir }, Landroid/content/Context;->getExternalFilesDir(Ljava/lang/String;)Ljava/io/File;
                move-result-object v$dir
                if-eqz v$dir, :morphe_lbc_skip

                new-instance v$file, Ljava/io/File;
                const-string v$tmp, "$RUNTIME_DEX_NAME"
                invoke-direct { v$file, v$dir, v$tmp }, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V
                invoke-virtual { v$file }, Ljava/io/File;->exists()Z
                move-result v$tmp
                if-eqz v$tmp, :morphe_lbc_skip

                invoke-virtual { v$file }, Ljava/io/File;->getAbsolutePath()Ljava/lang/String;
                move-result-object v$file

                invoke-virtual { p0 }, Landroid/content/Context;->getCodeCacheDir()Ljava/io/File;
                move-result-object v$dir
                invoke-virtual { v$dir }, Ljava/io/File;->getAbsolutePath()Ljava/lang/String;
                move-result-object v$dir

                invoke-virtual { p0 }, Landroid/content/Context;->getClassLoader()Ljava/lang/ClassLoader;
                move-result-object v$loader

                new-instance v$cl, Ldalvik/system/DexClassLoader;
                const/4 v$tmp, 0x0
                invoke-direct { v$cl, v$file, v$dir, v$tmp, v$loader }, Ldalvik/system/DexClassLoader;-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V

                const-string v$file, "$RUNTIME_ENTRY_CLASS"
                invoke-virtual { v$cl, v$file }, Ljava/lang/ClassLoader;->loadClass(Ljava/lang/String;)Ljava/lang/Class;
                move-result-object v$cl

                const/4 v$tmp, 0x1
                new-array v$args, v$tmp, [Ljava/lang/Class;
                const-class v$loader, Landroid/content/Context;
                const/4 v$tmp, 0x0
                aput-object v$loader, v$args, v$tmp
                const-string v$file, "init"
                invoke-virtual { v$cl, v$file, v$args }, Ljava/lang/Class;->getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;
                move-result-object v$cl

                const/4 v$tmp, 0x1
                new-array v$args, v$tmp, [Ljava/lang/Object;
                const/4 v$tmp, 0x0
                aput-object p0, v$args, v$tmp
                const/4 v$loader, 0x0
                invoke-virtual { v$cl, v$loader, v$args }, Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;

                :morphe_lbc_skip
                nop
            """,
        )
    }
}
