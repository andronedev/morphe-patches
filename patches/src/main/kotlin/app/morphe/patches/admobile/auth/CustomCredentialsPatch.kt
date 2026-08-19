package app.morphe.patches.admobile.auth

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.admobile.Constants.APPLICATION_CLASS_DESCRIPTOR
import app.morphe.patches.admobile.Constants.CHECK_USER_LOG_PREFIX
import app.morphe.patches.admobile.Constants.COMPATIBILITY_ADMOBILE
import app.morphe.patches.admobile.Constants.CREDENTIALS_CLASS_DESCRIPTOR
import app.morphe.patches.admobile.Constants.PREFERENCES_KEY_CLASS_DESCRIPTOR
import app.morphe.patches.admobile.Constants.SYNTHETIC_USER_METHOD_NAME
import app.morphe.patches.admobile.Constants.USER_CLASS_DESCRIPTOR
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstStringInstructionOrThrow
import app.morphe.util.returnEarly
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

/**
 * Resolves one of the extension's bundled-client getters, which the patch rewrites to return the
 * value the build was given.
 */
context(BytecodePatchContext)
private fun bundledClientMethod(name: String) =
    mutableClassDefByOrNull(CREDENTIALS_CLASS_DESCRIPTOR)
        ?.methods
        ?.firstOrNull { it.name == name && it.parameters.isEmpty() }
        ?: throw PatchException("Could not find $CREDENTIALS_CLASS_DESCRIPTOR->$name.")

@Suppress("unused")
val customCredentialsPatch = bytecodePatch(
    name = "Custom AdMob Credentials",
    description = "Sign in with your own Google OAuth client and refresh token, entered in the " +
        "app, instead of the Google Sign-In flow that a re-signed APK cannot use.",
) {
    compatibleWith(COMPATIBILITY_ADMOBILE)

    dependsOn(credentialsScreenPatch)

    extendWith("extensions/admobile.mpe")

    val clientIdOption = stringOption(
        key = "clientId",
        default = "",
        title = "OAuth client id",
        description = "Desktop OAuth client to sign in with. Leave empty to be asked in the app.",
        required = false,
    )

    val clientSecretOption = stringOption(
        key = "clientSecret",
        default = "",
        title = "OAuth client secret",
        description = "Secret of that client. Leave empty to be asked in the app.",
        required = false,
    )

    execute {
        // Built into the extension so signing in is a single tap with nothing to paste. Both are
        // left empty by default, in which case the form asks for a client instead.
        val clientId = clientIdOption.value?.trim().orEmpty()
        val clientSecret = clientSecretOption.value?.trim().orEmpty()

        if (clientId.isNotEmpty() != clientSecret.isNotEmpty()) {
            throw PatchException("Set both 'clientId' and 'clientSecret', or neither.")
        }

        if (clientId.isNotEmpty()) {
            bundledClientMethod("bundledClientId").returnEarly(clientId)
            bundledClientMethod("bundledClientSecret").returnEarly(clientSecret)
        }

        // The form writes to the app's private preferences, so the extension needs a context before
        // anything reads a credential. Application.onCreate runs first and its class is named in the
        // manifest, so R8 keeps it.
        val applicationClass = mutableClassDefByOrNull(APPLICATION_CLASS_DESCRIPTOR)
            ?: throw PatchException("Could not find $APPLICATION_CLASS_DESCRIPTOR.")

        val onCreate = applicationClass.methods.firstOrNull {
            it.name == "onCreate" && it.parameters.isEmpty() && it.returnType == "V"
        } ?: throw PatchException("Could not find the application's onCreate.")

        onCreate.addInstructions(
            0,
            "invoke-static { p0 }, $CREDENTIALS_CLASS_DESCRIPTOR->init(Landroid/content/Context;)V",
        )

        // 1. The DataStore read. Every secret the token requests need is decrypted here, keyed by
        //    name, so answering three names stands in for a whole signed-in session. It is a suspend
        //    function: returning a value rather than the COROUTINE_SUSPENDED marker is what it
        //    already does whenever nothing has to suspend. A null answer falls through to the app's
        //    own storage, which keeps the unconfigured app working as before.
        AppStoreReadFingerprint.method.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p1, $PREFERENCES_KEY_CLASS_DESCRIPTOR->a:Ljava/lang/String;
                invoke-static { v0 }, $CREDENTIALS_CLASS_DESCRIPTOR->forDataStoreKey(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                if-eqz v0, :original
                return-object v0

                :original
                nop
            """,
        )

        // 2. The pre-DataStore read, which the OkHttp authenticators try first for the refresh token.
        AppStoreLegacyReadFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { p1 }, $CREDENTIALS_CLASS_DESCRIPTOR->forLegacyKey(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                if-eqz v0, :original
                return-object v0

                :original
                nop
            """,
        )

        // 2b. Both writes are mirrored. An access token lasts an hour, after which the app refreshes
        //     it and stores the new one; without this the reads above would keep answering with the
        //     expired value and every request would fail.
        AppStoreWriteFingerprint.method.addInstructions(
            0,
            """
                iget-object v0, p1, $PREFERENCES_KEY_CLASS_DESCRIPTOR->a:Ljava/lang/String;
                invoke-static { v0, p2 }, $CREDENTIALS_CLASS_DESCRIPTOR->observeWrite(Ljava/lang/String;Ljava/lang/String;)V
            """,
        )

        AppStoreLegacyWriteFingerprint.method.addInstructions(
            0,
            "invoke-static { p1, p2 }, $CREDENTIALS_CLASS_DESCRIPTOR->observeWrite(Ljava/lang/String;Ljava/lang/String;)V",
        )

        // 3. The client id is read once, when the app store is constructed, and travels to both
        //    token requests as a field. Substituting the constructor argument covers both. The
        //    constructor reserves no locals, so the parameter register is reused in place.
        AppStoreConstructorFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $CREDENTIALS_CLASS_DESCRIPTOR->clientIdOrOriginal(Ljava/lang/String;)Ljava/lang/String;
                move-result-object p1
            """,
        )

        // 4. The launch screen's sign in button opens the form while nothing is configured, so the
        //    setup is one tap from the screen a fresh install already lands on. Once credentials
        //    exist the account is served locally and the button is never reached again.
        val signInIntentMethod = SignInIntentFingerprint.method
        val signInIntentIndex = signInIntentMethod.instructions.indexOfFirst { instruction ->
            val reference = instruction.getReference<MethodReference>()

            reference?.returnType == "Landroid/content/Intent;" && reference.parameterTypes.isEmpty()
        }
        if (signInIntentIndex < 0) {
            throw PatchException("Could not find the sign-in intent call.")
        }

        val signInIntentReference = signInIntentMethod
            .getInstruction<com.android.tools.smali.dexlib2.iface.instruction.Instruction>(signInIntentIndex)
            .getReference<MethodReference>()
            ?: throw PatchException("Could not resolve the sign-in intent call.")

        // Every call site is redirected, not just the launch screen's: the add account action
        // builds the same intent, and once signed in it is the only way back to the form — which is
        // where disconnecting lives, since the app's own sign out cannot forget an account that is
        // served from the extension rather than from its database.
        var redirected = 0
        classDefForEach { classDef ->
            classDef.methods.forEach { method ->
                val callIndices = method.implementation
                    ?.instructions
                    ?.withIndex()
                    ?.filter { (_, instruction) ->
                        instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                            instruction.getReference<MethodReference>() == signInIntentReference
                    }
                    ?.map { it.index }
                    .orEmpty()

                if (callIndices.isEmpty()) return@forEach

                val mutableMethod = mutableClassDefBy(classDef).findMutableMethodOf(method)

                callIndices.asReversed().forEach { index ->
                    val register = mutableMethod
                        .getInstruction<OneRegisterInstruction>(index + 1)
                        .registerA

                    mutableMethod.addInstructions(
                        index + 2,
                        """
                            invoke-static { v$register }, $CREDENTIALS_CLASS_DESCRIPTOR->signInIntentOrOriginal(Landroid/content/Intent;)Landroid/content/Intent;
                            move-result-object v$register
                        """,
                    )
                    redirected++
                }
            }
        }

        if (redirected == 0) {
            throw PatchException("Could not redirect any sign-in intent call.")
        }

        // 4b. The app's own sign out forgets the account in its database. The patched app's account
        //     is served from the extension instead, so without this it reappears on the next check
        //     and signing out looks like it does nothing.
        SignOutFingerprint.method.addInstructions(
            0,
            "invoke-static { }, $CREDENTIALS_CLASS_DESCRIPTOR->signOut()V",
        )

        // 5. checkUser() sends the app to the login screen when the user DAO reports no selected
        //    account, so that query hands back a fabricated one. The DAO carries no strings of its
        //    own and is identified by the call checkUser() makes just before logging the result.
        val checkUserMethod = CheckUserFingerprint.method
        val checkUserLogIndex = checkUserMethod.indexOfFirstStringInstructionOrThrow(
            CHECK_USER_LOG_PREFIX,
        )

        val selectedUserQuery = checkUserMethod.instructions
            .withIndex()
            .take(checkUserLogIndex)
            .lastOrNull { (_, instruction) ->
                if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return@lastOrNull false

                val reference = instruction.getReference<MethodReference>() ?: return@lastOrNull false

                reference.returnType == "Ljava/lang/Object;" &&
                    reference.parameterTypes.map(CharSequence::toString) == listOf("Lyh/c;") &&
                    // The account manager calls its own suspend helpers here too; the DAO is the
                    // only call that leaves the class.
                    reference.definingClass != checkUserMethod.definingClass
            }
            ?.value
            ?.getReference<MethodReference>()
            ?: throw PatchException("Could not find the selected account query in checkUser().")

        // 6. The account itself. Its constructor takes the columns in schema order: id, sign_id,
        //    fire_id, email, name, avatar, time_zone, currency and the selected flag. The three id
        //    columns share the publisher id because the only thing derived from the account id is
        //    the refresh token key, which step 1 answers for any id. The factory lives on the entity
        //    because it needs ten registers, more than the DAO method reserves.
        val userClass = mutableClassDefByOrNull(USER_CLASS_DESCRIPTOR)
            ?: throw PatchException("Could not find $USER_CLASS_DESCRIPTOR.")

        val syntheticUserFactory = ImmutableMethod(
            USER_CLASS_DESCRIPTOR,
            SYNTHETIC_USER_METHOD_NAME,
            emptyList(),
            USER_CLASS_DESCRIPTOR,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            ImmutableMethodImplementation(10, emptyList(), null, null),
        ).toMutable()

        syntheticUserFactory.addInstructions(
            0,
            """
                invoke-static { }, $CREDENTIALS_CLASS_DESCRIPTOR->publisherId()Ljava/lang/String;
                move-result-object v1
                invoke-static { }, $CREDENTIALS_CLASS_DESCRIPTOR->email()Ljava/lang/String;
                move-result-object v4
                invoke-static { }, $CREDENTIALS_CLASS_DESCRIPTOR->timeZone()Ljava/lang/String;
                move-result-object v7
                invoke-static { }, $CREDENTIALS_CLASS_DESCRIPTOR->currency()Ljava/lang/String;
                move-result-object v8

                new-instance v0, $USER_CLASS_DESCRIPTOR
                move-object v2, v1
                move-object v3, v1
                move-object v5, v4
                const-string v6, ""
                const/4 v9, 0x1
                invoke-direct/range { v0 .. v9 }, $USER_CLASS_DESCRIPTOR-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V
                return-object v0
            """,
        )

        userClass.methods.add(syntheticUserFactory)

        val selectedUserQueryClass = mutableClassDefByOrNull(selectedUserQuery.definingClass)
            ?: throw PatchException("Could not find ${selectedUserQuery.definingClass}.")

        val selectedUserQueryMethod = selectedUserQueryClass.methods.firstOrNull {
            it.name == selectedUserQuery.name &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Lyh/c;")
        } ?: throw PatchException("Could not find ${selectedUserQuery.name} in the user DAO.")

        // Only stand in once credentials exist, so an unconfigured build still reaches the login
        // screen rather than looping on an account that cannot be authenticated.
        selectedUserQueryMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $CREDENTIALS_CLASS_DESCRIPTOR->isConfigured()Z
                move-result v0
                if-eqz v0, :original
                invoke-static { }, $USER_CLASS_DESCRIPTOR->$SYNTHETIC_USER_METHOD_NAME()$USER_CLASS_DESCRIPTOR
                move-result-object v0
                return-object v0

                :original
                nop
            """,
        )

        setExtensionIsPatchIncluded(CREDENTIALS_CLASS_DESCRIPTOR)
    }
}
