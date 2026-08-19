package app.morphe.patches.admobile

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val COMPATIBILITY_ADMOBILE = Compatibility(
        name = "AdMobile",
        packageName = "io.stark.admob",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0xF0C040,
    )

    /**
     * Prefix of the diagnostic logged when the persisted Play purchase fails to verify. It sits
     * right before the writes to the pro flag, and is distinct from the `"Verify Purchase [playKey:
     * "` prefix logged by the billing client while processing a fresh purchase list.
     */
    const val VERIFY_APP_PURCHASE_LOG_PREFIX = "Verify App Purchase [playKey: "

    const val VERIFY_APP_PURCHASE_EXCEPTION_LOG = "verifyAppPurchase Exception: "

    const val USER_CLASS_DESCRIPTOR = "Lio/stark/admob/model/entity/User;"

    /**
     * Two of the names the app store keeps its encrypted values under. They identify that store
     * through obfuscation: the settings store below has the same shape, and only the key names,
     * which are string constants, tell the two apart.
     */
    const val WEB_CLIENT_SECRET_KEY = "web_client_secret"
    const val USER_PUB_ID_KEY = "user_pub_id"

    /** The currency symbol every amount is prefixed with, and the settings store's marker. */
    const val CURRENCY_CODE_SETTING_KEY = "config_currency_code"

    const val APPLICATION_CLASS_DESCRIPTOR = "Lio/stark/admob/App;"

    /** Named in the navigation graph, so R8 keeps it. */
    const val LAUNCH_FRAGMENT_CLASS_DESCRIPTOR = "Lio/stark/admob/ui/launch/LaunchFragment;"

    const val CREDENTIALS_CLASS_DESCRIPTOR = "Lapp/morphe/extension/admobile/Credentials;"
    const val CREDENTIALS_ACTIVITY_CLASS_NAME = "app.morphe.extension.admobile.CredentialsActivity"
    const val CREDENTIALS_ACTIVITY_LABEL = "AdMobile credentials"

    /** The app's own Material 3 theme, so the form matches the rest of the app. */
    const val CREDENTIALS_ACTIVITY_THEME = "@style/AppTheme"

    const val CHECK_USER_LOG_PREFIX = "checkUser: "
    const val CHECK_USER_REINSERT_LOG_PREFIX = "checkUser REINSERT: "
    const val ACCOUNT_MANAGER_LOG_TAG = "AccountManager"
    const val SIGN_OUT_LOG_PREFIX = "signOut: userId: "

    /** Name given to the factory this patch adds to the user entity. */
    const val SYNTHETIC_USER_METHOD_NAME = "morpheSyntheticUser"
}
