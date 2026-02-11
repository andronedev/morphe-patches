package app.morphe.patches.transit.misc

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val transitUnlockPatch = bytecodePatch(
    name = "Pro Features Unlock",
    description = "Unlock all pro features in Transit",
) {
    compatibleWith("com.thetransitapp.droid")

    execute {
        IsPremiumFingerprint.method.replaceInstruction(6, "const/4 v2, 0x1")
    }
}
