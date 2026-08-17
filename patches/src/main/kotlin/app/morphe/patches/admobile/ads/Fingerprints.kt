package app.morphe.patches.admobile.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patches.admobile.Constants.AD_NATIVE_VIEW_CLASS_DESCRIPTOR
import app.morphe.patches.admobile.Constants.NATIVE_AD_CLASS_DESCRIPTOR
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * `AdNativeView.setNativeAd(NativeAd)` binds a loaded native ad to the view hierarchy and, as its
 * last instruction, calls `setVisibility(VISIBLE)`. It is the single place where any ad becomes
 * visible: every placement (home, apps, app info, mediation) routes its loaded ad through it.
 */
internal object SetNativeAdFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(NATIVE_AD_CLASS_DESCRIPTOR),
    custom = { method, classDef ->
        classDef.type == AD_NATIVE_VIEW_CLASS_DESCRIPTOR && method.name == "setNativeAd"
    },
)
