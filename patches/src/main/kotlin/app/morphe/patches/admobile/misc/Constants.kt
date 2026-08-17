package app.morphe.patches.admobile.misc

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    /**
     * AdMobile - AdMob Metrics (Wixel Store).
     *
     * Play Store delivers this app as a split bundle (per-ABI config split),
     * so the patchable input is a bundle file rather than a single APK.
     */
    val COMPATIBILITY_ADMOBILE = Compatibility(
        name = "AdMobile",
        packageName = "io.stark.admob",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x1A73E8,
    )
}
