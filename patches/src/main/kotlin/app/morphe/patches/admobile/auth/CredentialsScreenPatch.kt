package app.morphe.patches.admobile.auth

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.admobile.Constants.COMPATIBILITY_ADMOBILE
import app.morphe.patches.admobile.Constants.CREDENTIALS_ACTIVITY_CLASS_NAME
import app.morphe.patches.admobile.Constants.CREDENTIALS_ACTIVITY_LABEL
import app.morphe.patches.admobile.Constants.CREDENTIALS_ACTIVITY_THEME
import app.morphe.util.adoptChild
import app.morphe.util.findElementByAttributeValue
import app.morphe.util.getNode

/**
 * Declares the credentials form. It has no launcher entry and is not exported: the app's own sign
 * in button opens it, so setup stays one tap from the screen the user already lands on.
 */
internal val credentialsScreenPatch = resourcePatch(
    name = "AdMob Credentials Screen",
    description = "Declare the screen where the AdMob credentials are entered.",
) {
    compatibleWith(COMPATIBILITY_ADMOBILE)

    execute {
        document("AndroidManifest.xml").use { document ->
            val alreadyDeclared = document.getElementsByTagName("activity")
                .findElementByAttributeValue("android:name", CREDENTIALS_ACTIVITY_CLASS_NAME) != null
            if (alreadyDeclared) return@use

            val application = document.getNode("application")
                ?: throw PatchException("Could not find the application element.")

            application.adoptChild("activity") {
                setAttribute("android:name", CREDENTIALS_ACTIVITY_CLASS_NAME)
                setAttribute("android:label", CREDENTIALS_ACTIVITY_LABEL)
                // Reached only from inside the app, so it needs neither a launcher entry nor export.
                setAttribute("android:exported", "false")
                // The app's own Material 3 theme, so the form follows its colours, its day and night
                // variants, and the dynamic palette it already picks up on Android 12 and above.
                setAttribute("android:theme", CREDENTIALS_ACTIVITY_THEME)
            }
        }
    }
}
