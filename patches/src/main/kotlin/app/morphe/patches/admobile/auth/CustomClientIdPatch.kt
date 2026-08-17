package app.morphe.patches.admobile.auth

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patches.admobile.Constants.COMPATIBILITY_ADMOBILE
import app.morphe.patches.admobile.Constants.WEB_CLIENT_ID_STRING_RESOURCE

@Suppress("unused")
val customClientIdPatch = resourcePatch(
    name = "Custom OAuth Client ID",
    description = "Send your own OAuth client id with the token requests. Use together with " +
        "Custom AdMob Credentials, with the client the refresh token was issued to.",
) {
    compatibleWith(COMPATIBILITY_ADMOBILE)

    val clientIdOption = stringOption(
        key = "clientId",
        default = "",
        title = "OAuth client id",
        description = "Client id of your own Google OAuth client.",
        required = false,
    )

    execute {
        val clientId = clientIdOption.value?.trim().orEmpty()
        if (clientId.isBlank()) {
            throw PatchException("Option 'clientId' is required.")
        }

        // The app store is constructed with getString(R.string.web_client_id) and passes that value
        // as the client_id form field of both the authorization_code and refresh_token requests.
        // Rewriting the resource is enough; nothing else reads it.
        document("res/values/strings.xml").use { document ->
            val stringNodes = document.getElementsByTagName("string")

            var patched = false
            for (index in 0 until stringNodes.length) {
                val node = stringNodes.item(index)
                val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue

                if (name != WEB_CLIENT_ID_STRING_RESOURCE) continue

                node.textContent = clientId
                patched = true
                break
            }

            if (!patched) {
                throw PatchException(
                    "Could not find the $WEB_CLIENT_ID_STRING_RESOURCE string resource.",
                )
            }
        }
    }
}
