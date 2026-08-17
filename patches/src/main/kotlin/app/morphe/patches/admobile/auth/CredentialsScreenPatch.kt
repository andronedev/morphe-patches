package app.morphe.patches.admobile.auth

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.admobile.Constants.COMPATIBILITY_ADMOBILE
import app.morphe.patches.admobile.Constants.CREDENTIALS_ACTIVITY_CLASS_NAME
import app.morphe.patches.admobile.Constants.CREDENTIALS_ACTIVITY_LABEL

/**
 * Declares the credentials form and gives it its own launcher entry, which is the least invasive
 * way to reach it: the app's own navigation graph is left alone.
 */
internal val credentialsScreenPatch = resourcePatch(
    name = "AdMob Credentials Screen",
    description = "Add a launcher entry for the screen where the AdMob credentials are entered.",
) {
    compatibleWith(COMPATIBILITY_ADMOBILE)

    execute {
        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0)
                ?: throw PatchException("Could not find the application element.")

            val alreadyDeclared = document.getElementsByTagName("activity").let { activities ->
                (0 until activities.length).any { index ->
                    activities.item(index).attributes?.getNamedItem("android:name")?.nodeValue ==
                        CREDENTIALS_ACTIVITY_CLASS_NAME
                }
            }
            if (alreadyDeclared) return@use

            val activity = document.createElement("activity")
            activity.setAttribute("android:name", CREDENTIALS_ACTIVITY_CLASS_NAME)
            activity.setAttribute("android:label", CREDENTIALS_ACTIVITY_LABEL)
            activity.setAttribute("android:exported", "true")
            // Its own task, so leaving the form does not drop the user into the app mid-session.
            activity.setAttribute("android:launchMode", "singleTask")

            val intentFilter = document.createElement("intent-filter")

            val action = document.createElement("action")
            action.setAttribute("android:name", "android.intent.action.MAIN")
            intentFilter.appendChild(action)

            val category = document.createElement("category")
            category.setAttribute("android:name", "android.intent.category.LAUNCHER")
            intentFilter.appendChild(category)

            activity.appendChild(intentFilter)
            application.appendChild(activity)
        }
    }
}
