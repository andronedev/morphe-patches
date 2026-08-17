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
}
