package app.morphe.patches.leboncoin

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.Compatibility

internal object Constants {

    val COMPATIBILITY_LEBONCOIN = Compatibility(
        name = "leboncoin",
        packageName = "fr.leboncoin",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFF6E14,
    )

    /**
     * Classe du runtime appelée par le code injecté.
     * Contrat figé, partagé avec `runtime/leboncoin` — ne pas renommer d'un seul côté.
     */
    const val RUNTIME_ENTRY_CLASS = "app.morphe.lbc.Lbc"

    /** Nom du dex du runtime, cherché dans `getExternalFilesDir("morphe")`. */
    const val RUNTIME_DEX_NAME = "runtime.dex"

    /** Sous-dossier des assets où le patch dépose les noms résolus lus par le runtime. */
    const val BINDINGS_ASSET_PATH = "morphe/bindings.json"
}
