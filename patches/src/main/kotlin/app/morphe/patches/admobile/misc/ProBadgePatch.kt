package app.morphe.patches.admobile.misc

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.admobile.Constants.COMPATIBILITY_ADMOBILE
import app.morphe.patches.admobile.Constants.PREMIUM_ACTIVE_STRING_NAME
import app.morphe.patches.admobile.Constants.PREMIUM_ACTIVE_STRING_VALUE
import app.morphe.patches.admobile.Constants.PREMIUM_BUTTON_ID
import app.morphe.patches.admobile.Constants.PROFILE_LAYOUT_RESOURCE
import org.w3c.dom.Element

/**
 * Says so on the profile screen once pro is unlocked.
 *
 * The app's own indicator is a premium coloured ring around the account avatar, which is easy to
 * miss and easy to read as decoration. The button that used to sell the subscription is a better
 * place: it sits on the same screen, already carries the premium icon, and its label is the one
 * thing there that states a state rather than an action.
 *
 * The label is set in the layout rather than at runtime because the patched app is always pro:
 * there is no second state for it to switch to.
 */
internal val proBadgePatch = resourcePatch(
    name = "Pro Badge",
    description = "Show the pro state on the AdMobile profile screen.",
) {
    compatibleWith(COMPATIBILITY_ADMOBILE)

    execute {
        document("res/values/strings.xml").use { document ->
            val resources = document.getElementsByTagName("resources").item(0)
                ?: throw PatchException("Could not find the resources element.")

            val string = document.createElement("string")
            string.setAttribute("name", PREMIUM_ACTIVE_STRING_NAME)
            string.textContent = PREMIUM_ACTIVE_STRING_VALUE

            resources.appendChild(string)
        }

        document(PROFILE_LAYOUT_RESOURCE).use { document ->
            val buttons = document.getElementsByTagName("com.google.android.material.button.MaterialButton")

            val premiumButton = (0 until buttons.length)
                .map { buttons.item(it) as Element }
                .firstOrNull { it.getAttribute("android:id") == PREMIUM_BUTTON_ID }
                ?: throw PatchException("Could not find the premium button in $PROFILE_LAYOUT_RESOURCE.")

            premiumButton.setAttribute("android:text", "@string/$PREMIUM_ACTIVE_STRING_NAME")
        }
    }
}
