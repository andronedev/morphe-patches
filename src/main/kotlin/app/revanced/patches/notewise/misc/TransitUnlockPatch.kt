package app.revanced.patches.notewise.misc

import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.patch.annotation.CompatiblePackage
import app.revanced.patcher.patch.annotation.Patch
import app.revanced.patches.notewise.misc.fingerprints.IsUnlimitedFingerprint

@Patch(
    name = "Pro Features Unlock",
    description = "Unlock all pro features in Notewise by forcing unlimited entitlement to always be active.",
    compatiblePackages = [
        CompatiblePackage("com.yygg.note.app"),
    ],
)
@Suppress("unused")
object NotewiseUnlockPatch : BytecodePatch(setOf(IsUnlimitedFingerprint)) {
    override fun execute(context: BytecodeContext) = IsUnlimitedFingerprint.result?.let { result ->
        // Remplacer l'instruction qui récupère le résultat de isActive() (move-result p0) par const/4 p0, 0x1 (forcer à true)
        // Index 16 correspond à "move-result p0" dans la méthode SMALI (ajustez si nécessaire en fonction du fingerprint)
        result.mutableMethod.replaceInstruction(16, "const/4 p0, 0x1")
    } ?: throw IllegalStateException("IsUnlimitedFingerprint not found")
}