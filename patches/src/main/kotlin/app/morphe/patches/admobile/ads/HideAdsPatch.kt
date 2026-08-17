package app.morphe.patches.admobile.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.admobile.ads.Constants.COMPATIBILITY_ADMOBILE
import app.morphe.patches.admobile.ads.Constants.NATIVE_AD_CLASS_DESCRIPTOR

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide Ads",
    description = "Hide the native ads shown on the home, apps, app info and mediation screens.",
) {
    compatibleWith(COMPATIBILITY_ADMOBILE)

    execute {
        // AdNativeView hides itself in its constructor and stays GONE until setNativeAd() binds an
        // ad and flips it to VISIBLE. Releasing the ad and returning before any of that runs keeps
        // every placeholder collapsed, so no ad is rendered and the surrounding layout closes up.
        //
        // invoke-virtual/range is used because the method reserves 19 locals, which pushes the
        // parameter registers above v15 and out of reach of the non-range invoke.
        SetNativeAdFingerprint.method.addInstructions(
            0,
            """
                invoke-virtual/range { p1 .. p1 }, $NATIVE_AD_CLASS_DESCRIPTOR->destroy()V
                return-void
            """,
        )
    }
}
