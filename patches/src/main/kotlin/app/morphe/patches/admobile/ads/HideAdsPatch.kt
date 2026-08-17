package app.morphe.patches.admobile.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.admobile.misc.Constants.COMPATIBILITY_ADMOBILE
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * An entry point of the Google Mobile Ads SDK that the host app calls to request an ad.
 *
 * These class names are part of the SDK public API. `play-services-ads` ships consumer
 * ProGuard rules that keep them, so they survive R8 in release builds and can be matched
 * by name instead of by app specific fingerprints. Everything below the public API
 * (the `zz*` classes) is pre-obfuscated and is deliberately left alone.
 *
 * @param classDescriptor Smali descriptor of the SDK class.
 * @param methodNames Names of the ad requesting methods on that class. Every void overload
 *                    is neutralized, because the parameter lists differ between SDK versions.
 * @param collapseView Whether the class is a [android.view.View] container holding the ad.
 *                     When true the neutralized method also hides the container, so the
 *                     layout does not keep the reserved banner space as blank padding.
 */
private data class AdEntryPoint(
    val classDescriptor: String,
    val methodNames: Set<String>,
    val collapseView: Boolean = false,
)

private val AD_ENTRY_POINTS = listOf(
    // Banners. AdView and AdManagerAdView both inherit loadAd from BaseAdView,
    // but older SDK versions declare it on the leaf classes instead.
    AdEntryPoint("Lcom/google/android/gms/ads/BaseAdView;", setOf("loadAd"), collapseView = true),
    AdEntryPoint("Lcom/google/android/gms/ads/AdView;", setOf("loadAd"), collapseView = true),
    AdEntryPoint("Lcom/google/android/gms/ads/admanager/AdManagerAdView;", setOf("loadAd"), collapseView = true),

    // Native ads.
    AdEntryPoint("Lcom/google/android/gms/ads/AdLoader;", setOf("loadAd", "loadAds")),

    // Full screen formats. These are static factory loaders, so the load callback
    // simply never fires and the app never gets an ad to show.
    AdEntryPoint("Lcom/google/android/gms/ads/interstitial/InterstitialAd;", setOf("load")),
    AdEntryPoint("Lcom/google/android/gms/ads/admanager/AdManagerInterstitialAd;", setOf("load")),
    AdEntryPoint("Lcom/google/android/gms/ads/appopen/AppOpenAd;", setOf("load")),
    AdEntryPoint("Lcom/google/android/gms/ads/rewarded/RewardedAd;", setOf("load")),
    AdEntryPoint("Lcom/google/android/gms/ads/rewardedinterstitial/RewardedInterstitialAd;", setOf("load")),
)

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Stops the app from requesting Google Mobile Ads and collapses the banner containers.",
) {
    compatibleWith(COMPATIBILITY_ADMOBILE)

    execute {
        var neutralized = 0

        AD_ENTRY_POINTS.forEach { entryPoint ->
            // The SDK ships a different set of formats depending on which artifacts the app
            // pulled in, so a missing class is expected and not an error.
            val adClass = classBy { it.type == entryPoint.classDescriptor }?.mutableClass
                ?: return@forEach

            adClass.methods
                .filter { it.name in entryPoint.methodNames && it.returnType == "V" }
                .forEach { method ->
                    if (entryPoint.collapseView && method.canCollapseView()) {
                        method.collapseAndReturn()
                    } else {
                        method.returnEarly()
                    }
                    neutralized++
                }
        }

        if (neutralized == 0) {
            throw PatchException(
                "Found no Google Mobile Ads entry point. Either the SDK was renamed by R8 " +
                    "or the app no longer bundles it. Run tools/apk-recon.sh against the APK " +
                    "to list the ad classes that are actually present.",
            )
        }
    }
}

/**
 * The collapse code writes an int into the first parameter register and calls a virtual
 * method on `p0`, so it needs a non static method that takes at least one parameter.
 */
private fun MutableMethod.canCollapseView() =
    !AccessFlags.STATIC.isSet(accessFlags) && parameterTypes.isNotEmpty()

/**
 * Replaces the body with code that hides the ad container and returns.
 *
 * `p1` holds the AdRequest, which is dead the moment the injected `return-void` runs,
 * so it can be reused as a scratch register without having to search for a free one.
 */
private fun MutableMethod.collapseAndReturn() = addInstructions(
    0,
    """
        const/16 p1, 0x8
        invoke-virtual { p0, p1 }, Landroid/view/View;->setVisibility(I)V
        return-void
    """,
)
