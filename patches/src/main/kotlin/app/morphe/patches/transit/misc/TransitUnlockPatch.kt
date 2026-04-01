package app.morphe.patches.transit.misc

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.transit.misc.Constants.COMPATIBILITY_TRANSIT
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstStringInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

@Suppress("unused")
val transitUnlockPatch = bytecodePatch(
    name = "Pro Features Unlock",
    description = "Unlock all pro features in Transit",
) {
    compatibleWith(COMPATIBILITY_TRANSIT)

    execute {
        val method = IsPremiumFingerprint.method
        val premiumKeyIndex = method.indexOfFirstStringInstructionOrThrow("activate_royale_subscription")
        val moveResultIndex = method.indexOfFirstInstructionOrThrow(premiumKeyIndex, Opcode.MOVE_RESULT)
        val resultRegister = method.getInstruction<OneRegisterInstruction>(moveResultIndex).registerA

        // Force the result of isFeatureActivated(...) to true without corrupting preceding instructions.
        method.replaceInstruction(moveResultIndex, "const/4 v$resultRegister, 0x1")
    }
}
