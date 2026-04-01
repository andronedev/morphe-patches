package app.morphe.patches.transit.misc

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patches.transit.misc.Constants.COMPATIBILITY_TRANSIT

@Suppress("unused")
val transitMapsApiKeyPatch = resourcePatch(
    name = "Custom Maps API Key",
    description = "Replace Transit Google Maps key with your own Android Maps SDK key for re-signed APKs.",
) {
    compatibleWith(COMPATIBILITY_TRANSIT)

    val mapsApiKeyOption = stringOption(
        key = "mapsApiKey",
        default = "",
        title = "Google Maps API key",
        description = "Android Maps SDK key",
        required = false,
    )

    execute {
        val apiKey = mapsApiKeyOption.value?.trim().orEmpty()
        if (apiKey.isBlank()) {
            throw PatchException("Option 'mapsApiKey' is required.")
        }

        document("AndroidManifest.xml").use { dom ->
            val metaDataNodes = dom.getElementsByTagName("meta-data")
            var found = false
            for (i in 0 until metaDataNodes.length) {
                val node = metaDataNodes.item(i)
                val name = node.attributes.getNamedItem("android:name")?.nodeValue
                if (name == "com.google.android.maps.v2.API_KEY") {
                    node.attributes.getNamedItem("android:value")?.nodeValue = apiKey
                    found = true
                    break
                }
            }
            if (!found) {
                throw PatchException("Could not find com.google.android.maps.v2.API_KEY in AndroidManifest.xml.")
            }
        }
    }
}
