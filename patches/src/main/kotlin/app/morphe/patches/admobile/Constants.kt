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
     * Custom [android.widget.FrameLayout] that inflates `R.layout.widget_ad_native` and hosts the
     * AdMob `NativeAdView`. It is referenced from layout resources, so R8 keeps the class name, and
     * the default Android ProGuard rules keep its `set*` members, which makes both the class and
     * `setNativeAd` stable obfuscation-proof anchors.
     */
    const val AD_NATIVE_VIEW_CLASS_DESCRIPTOR = "Lio/stark/admob/ui/widget/ads/AdNativeView;"

    const val NATIVE_AD_CLASS_DESCRIPTOR = "Lcom/google/android/gms/ads/nativead/NativeAd;"

    /**
     * String resources holding the AdMob ad unit ids passed to `AdLoader.Builder`.
     * One per native ad placement: home, apps list and app info (also reused by mediation).
     */
    val AD_UNIT_STRING_RESOURCES = setOf(
        "ad_home_native",
        "ad_apps_native",
        "ad_app_info_native",
    )

    /**
     * Prefix of the diagnostic logged when the persisted Play purchase fails to verify. It sits
     * right before the writes to the pro flag, and is distinct from the `"Verify Purchase [playKey:
     * "` prefix logged by the billing client while processing a fresh purchase list.
     */
    const val VERIFY_APP_PURCHASE_LOG_PREFIX = "Verify App Purchase [playKey: "

    const val VERIFY_APP_PURCHASE_EXCEPTION_LOG = "verifyAppPurchase Exception: "

    /**
     * `androidx.datastore.preferences.core.Preferences.Key`. Its single field holds the key name,
     * which is what the app store's reader switches on.
     */
    const val PREFERENCES_KEY_CLASS_DESCRIPTOR = "Ll1/d;"

    const val USER_CLASS_DESCRIPTOR = "Lio/stark/admob/model/entity/User;"

    /**
     * Names of the encrypted values the OAuth code path reads back out of the app store.
     * `token_refresh_` is a prefix: the account id is appended to it.
     */
    const val WEB_CLIENT_SECRET_KEY = "web_client_secret"
    const val USER_PUB_ID_KEY = "user_pub_id"
    const val REFRESH_TOKEN_KEY_PREFIX = "token_refresh_"

    /** Pre-DataStore location of the refresh token, still read by the OkHttp authenticators. */
    const val LEGACY_REFRESH_TOKEN_KEY = "user_token_refresh"

    /** String resource holding the OAuth client id the token requests are signed with. */
    const val WEB_CLIENT_ID_STRING_RESOURCE = "web_client_id"

    const val CHECK_USER_LOG_PREFIX = "checkUser: "
    const val CHECK_USER_REINSERT_LOG_PREFIX = "checkUser REINSERT: "
    const val ACCOUNT_MANAGER_LOG_TAG = "AccountManager"

    /** Name given to the factory this patch adds to the user entity. */
    const val SYNTHETIC_USER_METHOD_NAME = "morpheSyntheticUser"
}
