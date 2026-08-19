package app.morphe.patches.admobile.ads

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.admobile.Constants.AD_UNIT_STRING_RESOURCES
import app.morphe.patches.admobile.Constants.COMPATIBILITY_ADMOBILE
import app.morphe.util.asSequence

@Suppress("unused")
val disableAdRequestsPatch = resourcePatch(
    name = "Disable Ad Requests",
    description = "Clear the AdMob ad unit ids so no ad is ever requested from the network.",
) {
    compatibleWith(COMPATIBILITY_ADMOBILE)

    execute {
        // Each screen reads its ad unit id from a string resource and hands it to
        // AdLoader.Builder(context, adUnitId). The builder only rejects a null context, so an empty
        // id is accepted and the request fails instead of returning an ad. Resource names survive
        // obfuscation, which makes this the version resilient half of the ad removal.
        document("res/values/strings.xml").use { document ->
            val cleared = document.getElementsByTagName("string")
                .asSequence()
                .filter { it.attributes?.getNamedItem("name")?.nodeValue in AD_UNIT_STRING_RESOURCES }
                .onEach { it.textContent = "" }
                .count()

            if (cleared == 0) {
                throw PatchException(
                    "Could not find any of the ad unit string resources " +
                        "${AD_UNIT_STRING_RESOURCES.joinToString()} in res/values/strings.xml.",
                )
            }
        }
    }
}
