package app.morphe.patches.admobile.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.admobile.Constants.COMPATIBILITY_ADMOBILE
import app.morphe.patches.admobile.Constants.VERIFY_APP_PURCHASE_LOG_PREFIX
import app.morphe.util.indexOfFirstStringInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

@Suppress("unused")
val proUnlockPatch = bytecodePatch(
    name = "Pro Unlock",
    description = "Unlock all pro features in AdMobile.",
) {
    compatibleWith(COMPATIBILITY_ADMOBILE)

    execute {
        val method = VerifyAppPurchaseFingerprint.method

        // The signature check writes its outcome to a single boolean field, and that field is what
        // every pro gate reads: the real report charts instead of the demo data, the full ad unit
        // and ad source breakdowns instead of a single teaser row, more than one AdMob account, the
        // premium accents in the profile, and the branch that skips loading an ad.
        //
        // Both writes are patched: the one after a failed verification and the one taken when no
        // purchase is persisted at all. Forcing them to true covers the never purchased case, which
        // overriding the verification result alone would not.
        val verificationLogIndex = method.indexOfFirstStringInstructionOrThrow(
            VERIFY_APP_PURCHASE_LOG_PREFIX,
        )

        // The pro flag is the only boolean field this method writes.
        val proFlagWriteIndices = method.instructions
            .withIndex()
            .filter { (index, instruction) ->
                index > verificationLogIndex && instruction.opcode == Opcode.IPUT_BOOLEAN
            }
            .map { it.index }

        if (proFlagWriteIndices.isEmpty()) {
            throw PatchException("Could not find the pro flag write in ${method.name}.")
        }

        // Insert back to front so the remaining indices stay valid as instructions are added.
        proFlagWriteIndices.asReversed().forEach { index ->
            val valueRegister = method.getInstruction<TwoRegisterInstruction>(index).registerA

            method.addInstruction(index, "const/16 v$valueRegister, 0x1")
        }
    }
}
