package app.morphe.patches.leboncoin

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.leboncoin.Constants.BINDINGS_ASSET_PATH
import app.morphe.patches.leboncoin.Constants.COMPATIBILITY_LEBONCOIN
import java.io.File

/**
 * Prépare l'APK pour le runtime Morphe LBC :
 *
 * - relève le nom de la classe `Application` (cible de l'injection, cf. [leboncoinLoaderPatch]) ;
 * - dépose `assets/morphe/bindings.json`, la table des noms obfusqués et endpoints résolus au
 *   moment du patch et lue à l'exécution par le runtime.
 *
 * Le `bindings.json` est le pivot de l'architecture : c'est lui qui permet au runtime et aux
 * plugins de ne jamais coder un nom obfusqué en dur, et donc de survivre à une mise à jour de
 * l'app tant que les fingerprints du patch, eux, sont à jour.
 *
 * Voir `docs/leboncoin/ARCHITECTURE.md`.
 */
@Suppress("unused")
val leboncoinManifestPatch = resourcePatch(
    name = "Leboncoin runtime bootstrap",
    description = "Prépare l'APK leboncoin pour le runtime Morphe LBC (permissions, bindings, classe Application).",
    use = false,
) {
    compatibleWith(COMPATIBILITY_LEBONCOIN)

    execute {
        document("AndroidManifest.xml").use { dom ->
            val applicationNodes = dom.getElementsByTagName("application")
            if (applicationNodes.length == 0) {
                throw PatchException("Aucune balise <application> dans AndroidManifest.xml.")
            }
            val application = applicationNodes.item(0)

            applicationClassName = application.attributes
                .getNamedItem("android:name")
                ?.nodeValue
                ?.takeIf { it.isNotBlank() }
                ?: throw PatchException(
                    "L'application ne déclare pas de classe Application : le point d'injection " +
                        "doit être choisi autrement (cf. docs/leboncoin/ARCHITECTURE.md).",
                )

            // Le runtime lit `runtime.dex` dans le stockage externe de l'app et déclenche des
            // requêtes réseau pour l'auto-repost.
            val manifest = dom.getElementsByTagName("manifest").item(0)
                ?: throw PatchException("Balise <manifest> introuvable.")
            val existing = dom.getElementsByTagName("uses-permission")
                .let { nodes ->
                    (0 until nodes.length).mapNotNull {
                        nodes.item(it).attributes.getNamedItem("android:name")?.nodeValue
                    }
                }
                .toSet()

            REQUIRED_PERMISSIONS.filterNot(existing::contains).forEach { permission ->
                val node = dom.createElement("uses-permission")
                node.setAttribute("android:name", permission)
                manifest.insertBefore(node, manifest.firstChild)
            }
        }

        // `bindings.json` : les valeurs non résolues restent vides, le runtime démarre alors en
        // mode dégradé plutôt que de planter (cf. Bindings.load).
        val assets = get("assets", false)
        val target = File(assets, BINDINGS_ASSET_PATH)
        target.parentFile?.mkdirs()
        target.writeText(buildBindingsJson())
    }
}

private val REQUIRED_PERMISSIONS = listOf(
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.POST_NOTIFICATIONS",
)

/**
 * Nom de la classe `Application`, relevé par [leboncoinManifestPatch] et consommé par
 * [leboncoinLoaderPatch] (qui déclare ce patch en dépendance, donc s'exécute après).
 */
internal var applicationClassName: String? = null

/**
 * Endpoints relevés sur `fr.leboncoin` 100.120.1 (cf. `docs/leboncoin/RECON.md`).
 *
 * `classes` et `methods` restent vides : ils ne servent qu'aux bibliothèques tierces obfusquées
 * (OkHttp, Retrofit), qui demandent des fingerprints dédiés — le code `fr.leboncoin.*`, lui, n'est
 * pas renommé et est donc adressable directement par le runtime.
 *
 * TODO(okhttp) : résoudre `okhttp.interceptor` / `okhttp.clientBuilder` par fingerprint (ancre
 * exploitable : la chaîne `"network interceptor "` de RealInterceptorChain), puis générer le shim
 * qui alimente `HttpBridge`.
 */
private fun buildBindingsJson(): String = """
    {
      "apkVersion": "$KNOWN_APK_VERSION",
      "classes": {
        "app.application": "$KNOWN_APPLICATION_CLASS"
      },
      "methods": {},
      "endpoints": {
        "apiBase": "https://api.leboncoin.fr",
        "adProlong": "https://api.leboncoin.fr/api/pintad/v1/public/manual/prolongation/{id}",
        "adDelete": "https://api.leboncoin.fr/api/pintad/v1/public/manual/delete/ads",
        "adPause": "https://api.leboncoin.fr/api/pintad/v1/public/manual/pause/ads",
        "adUnpause": "https://api.leboncoin.fr/api/pintad/v1/public/manual/unpause/ads",
        "adDetail": "https://api.leboncoin.fr/api/pintad/v1/public/manual/classified/{id}",
        "ownerListing": "https://api.leboncoin.fr/api/adfinder/v1/owner_listing",
        "adCreate": ""
      }
    }
""".trimIndent()

/** Version sur laquelle la recon a été faite : sert au diagnostic, pas au filtrage. */
private const val KNOWN_APK_VERSION = "100.120.1"

/** Relevé par la recon ; le patch lit quand même le manifeste plutôt que de s'y fier. */
private const val KNOWN_APPLICATION_CLASS = "fr.leboncoin.app.PolarisApplicationRelease"
