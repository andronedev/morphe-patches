package app.morphe.patches.transit.misc

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val COMPATIBILITY_TRANSIT = Compatibility(
        name = "Transit",
        packageName = "com.thetransitapp.droid",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x30B464,
    )
}
