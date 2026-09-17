package app.morphe.patches.admobile.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.admobile.Constants.COMPATIBILITY_ADMOBILE
import app.morphe.patches.admobile.Constants.VERIFY_APP_PURCHASE_LOG_PREFIX
import app.morphe.util.findFreeRegister
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstStringInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val proUnlockPatch = bytecodePatch(
    name = "Pro Unlock",
    description = "Unlock every pro feature in AdMobile, in every process that checks for one.",
) {
    compatibleWith(COMPATIBILITY_ADMOBILE)

    execute {
        // Pro is one boolean field, written in exactly one place: the verifyAppPurchase body, which
        // checks the purchase and signature persisted after the last Play purchase against the
        // stored public key. The check is entirely local, and the method is found by the log prefix
        // it emits, a string R8 leaves alone.
        val verify = VerifyAppPurchaseFingerprint.method
        val logIndex = verify.indexOfFirstStringInstructionOrThrow(VERIFY_APP_PURCHASE_LOG_PREFIX)

        val writeIndex = verify.instructions
            .drop(logIndex)
            .indexOfFirst { it.opcode == Opcode.IPUT_BOOLEAN }
            .let { if (it < 0) -1 else it + logIndex }

        if (writeIndex < 0) {
            throw PatchException("Could not find the pro flag written by ${verify.name}.")
        }

        val proFlag = verify.getInstruction(writeIndex).getReference<FieldReference>()
            ?: throw PatchException("Could not resolve the pro flag field.")

        // Forcing that write was not enough. It only runs when the billing client processes a
        // purchase list, which happens once the app is properly open — so a home screen widget,
        // whose worker can start the process on its own, read the flag before anything had set it
        // and rendered as if the user had never paid.
        //
        // Every read is answered instead. That leaves no order of events in which a gate sees
        // false, whichever entry point woke the process, and it needs no knowledge of what each
        // gate goes on to do.
        var forced = 0
        val liveDataFields = mutableSetOf<FieldReference>()

        classDefForEach { classDef ->
            classDef.methods.forEach { method ->
                val instructions = method.implementation?.instructions ?: return@forEach

                val readIndices = instructions
                    .withIndex()
                    .filter { (_, instruction) ->
                        instruction.opcode == Opcode.IGET_BOOLEAN &&
                            instruction.getReference<FieldReference>() == proFlag
                    }
                    .map { it.index }

                if (readIndices.isEmpty()) return@forEach

                val mutableMethod = mutableClassDefBy(classDef).findMutableMethodOf(method)

                readIndices.forEach { index ->
                    // The instruction is replaced rather than preceded, so the object it would have
                    // dereferenced is left untouched: one fewer thing that can be null.
                    val register = mutableMethod
                        .getInstruction<TwoRegisterInstruction>(index)
                        .registerA

                    mutableMethod.replaceInstruction(index, "const/4 v$register, 0x1")
                    forced++
                }

                // Two of these readers hand the flag to a LiveData the screens observe. Noting it
                // here means the seeding below does not have to know the class by name.
                liveDataFields += mutableMethod.liveDataFedFrom(readIndices.first())
            }
        }

        if (forced == 0) {
            throw PatchException("Could not find any read of the pro flag.")
        }

        // The screens do not read the field; they observe that LiveData, and it is created empty.
        // Until the billing client fills it, "is the user pro" answers no — which is the window the
        // ad loader lives in, and the reason the profile shows nothing premium on a cold start.
        // Starting it as true closes the window, and makes the app look and behave the way it does
        // for someone who actually paid, from the first frame.
        liveDataFields.forEach { field -> seedTrue(field) }
    }
}

/**
 * Field references of the `MutableLiveData` this method pushes the pro flag into, if it does.
 *
 * Found by walking forward from the read to the `setValue`/`postValue` call and taking the field
 * whose value is the receiver, so neither the class nor the field has to be named.
 */
private fun app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.liveDataFedFrom(
    readIndex: Int,
): Set<FieldReference> {
    val publish = instructions
        .withIndex()
        .firstOrNull { (index, instruction) ->
            index > readIndex &&
                instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                instruction.getReference<MethodReference>()?.name in setOf("setValue", "postValue")
        }
        ?.index
        ?: return emptySet()

    val receiver = getInstruction<com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction>(
        publish,
    ).registerC

    // The receiver was loaded from a field shortly before the call.
    val load = (publish - 1 downTo 0).firstOrNull { index ->
        val instruction = getInstruction(index)
        instruction.opcode == Opcode.IGET_OBJECT &&
            (instruction as TwoRegisterInstruction).registerA == receiver
    } ?: return emptySet()

    return setOfNotNull(getInstruction(load).getReference<FieldReference>())
}

/** Gives a `MutableLiveData` field the value true as soon as its owner constructs it. */
context(app.morphe.patcher.patch.BytecodePatchContext)
private fun seedTrue(field: FieldReference) {
    val owner = mutableClassDefByOrNull(field.definingClass) ?: return

    owner.methods.filter { it.name == "<init>" }.forEach { constructor ->
        val assignment = constructor.instructions
            .withIndex()
            .lastOrNull { (_, instruction) ->
                instruction.opcode == Opcode.IPUT_OBJECT &&
                    instruction.getReference<FieldReference>() == field
            }
            ?: return@forEach

        val liveData = constructor.getInstruction<TwoRegisterInstruction>(assignment.index).registerA
        val free = constructor.findFreeRegister(assignment.index + 1, liveData)

        constructor.addInstructions(
            assignment.index + 1,
            """
                sget-object v$free, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                invoke-virtual { v$liveData, v$free }, Landroidx/lifecycle/MutableLiveData;->setValue(Ljava/lang/Object;)V
            """,
        )
    }
}
