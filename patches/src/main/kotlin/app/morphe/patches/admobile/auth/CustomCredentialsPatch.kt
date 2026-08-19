package app.morphe.patches.admobile.auth

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.admobile.Constants.APPLICATION_CLASS_DESCRIPTOR
import app.morphe.patches.admobile.Constants.CHECK_USER_LOG_PREFIX
import app.morphe.patches.admobile.Constants.COMPATIBILITY_ADMOBILE
import app.morphe.patches.admobile.Constants.CREDENTIALS_CLASS_DESCRIPTOR
import app.morphe.patches.admobile.Constants.SYNTHETIC_USER_METHOD_NAME
import app.morphe.patches.admobile.Constants.USER_CLASS_DESCRIPTOR
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversed
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
    mutableClassDefBy(CREDENTIALS_CLASS_DESCRIPTOR)
        .methods
        .firstOrNull { it.name == name && it.parameters.isEmpty() }
        ?: throw PatchException("Could not find $CREDENTIALS_CLASS_DESCRIPTOR->$name.")

/**
 * Answers a suspending `(Preferences.Key) -> Any?` read from the extension, falling through to the
 * app's own storage when the extension has nothing to say.
 *
 * @param keyNameField the field of `Preferences.Key` holding the key name, resolved rather than
 * spelled out: both the class and the field are named by R8.
 */
private fun MutableMethod.hookStoreRead(keyNameField: String) = addInstructionsWithLabels(
    0,
    """
        if-eqz p1, :original
        iget-object v0, p1, $keyNameField
        invoke-static { v0 }, $CREDENTIALS_CLASS_DESCRIPTOR->forDataStoreKey(Ljava/lang/String;)Ljava/lang/String;
        move-result-object v0
        if-eqz v0, :original
        return-object v0

        :original
        nop
    """,
)

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
        val onCreate = mutableClassDefBy(APPLICATION_CLASS_DESCRIPTOR).methods.firstOrNull {
            it.name == "onCreate" && it.parameters.isEmpty() && it.returnType == "V"
        } ?: throw PatchException("Could not find the application's onCreate.")

        onCreate.addInstructions(
            0,
            "invoke-static { p0 }, $CREDENTIALS_CLASS_DESCRIPTOR->init(Landroid/content/Context;)V",
        )

        // 1. The two DataStore reads, one per store. Everything the token requests need is decrypted
        //    in the first, keyed by name, so answering four names stands in for a whole signed-in
        //    session; the second holds the currency symbol, which is written only by the sign in the
        //    patched app never runs. Both are suspend functions: returning a value rather than the
        //    COROUTINE_SUSPENDED marker is what they already do whenever nothing has to suspend. A
        //    null answer falls through to the app's own storage, which keeps the unconfigured app
        //    working as before.
        //
        //    The key class and the field holding the key name are both obfuscated, so they are read
        //    off the method's own signature rather than written down.
        //
        //    Both hooks sit before the state machine, so they also run on every resumption of the
        //    suspend function, and Kotlin passes null for the value parameters when it resumes. A
        //    null key is therefore normal and means "not an entry, a return", so it falls through.
        val preferencesKeyType = AppStoreReadFingerprint.method.parameterTypes.first().toString()
        val keyNameField = mutableClassDefBy(preferencesKeyType)
            .fields
            .singleOrNull { it.type == "Ljava/lang/String;" }
            ?.let { "$preferencesKeyType->${it.name}:Ljava/lang/String;" }
            ?: throw PatchException("Could not find the key name field of $preferencesKeyType.")

        AppStoreReadFingerprint.method.hookStoreRead(keyNameField)
        SettingsStoreReadFingerprint.method.hookStoreRead(keyNameField)

        // 2. The pre-DataStore read, which the OkHttp authenticators try first for the tokens.
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
        AppStoreWriteFingerprint.method.addInstructionsWithLabels(
            0,
            """
                if-eqz p1, :original
                iget-object v0, p1, $keyNameField
                invoke-static { v0, p2 }, $CREDENTIALS_CLASS_DESCRIPTOR->observeWrite(Ljava/lang/String;Ljava/lang/String;)V

                :original
                nop
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

        // 4. The sign in button opens the form instead of the Google flow, so the setup is one tap
        //    from the screen a fresh install already lands on. Every call site is redirected, not
        //    just the launch screen's: the add account action builds the same intent, and once
        //    signed in it is the only way back to the form, which is where disconnecting lives.
        val signInIntentMethod = SignInIntentFingerprint.method
        val signInIntentReference = signInIntentMethod
            .getInstruction(
                signInIntentMethod.indexOfFirstInstructionOrThrow {
                    val reference = getReference<MethodReference>()

                    reference?.returnType == "Landroid/content/Intent;" &&
                        reference.parameterTypes.isEmpty()
                },
            )
            .getReference<MethodReference>()
            ?: throw PatchException("Could not resolve the sign-in intent call.")

        var redirected = 0
        classDefForEach { classDef ->
            classDef.methods.forEach { method ->
                val instructions = method.implementation?.instructions ?: return@forEach

                // The result register is what gets replaced, so a call site that discards the intent
                // has nothing to redirect and is skipped.
                val callIndices = instructions
                    .withIndex()
                    .filter { (_, instruction) ->
                        instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                            instruction.getReference<MethodReference>() == signInIntentReference
                    }
                    .map { it.index }
                    .filter { instructions.elementAt(it + 1).opcode == Opcode.MOVE_RESULT_OBJECT }

                if (callIndices.isEmpty()) return@forEach

                val mutableMethod = mutableClassDefBy(classDef).findMutableMethodOf(method)

                // Back to front, so the remaining indices stay valid as instructions are added.
                callIndices.asReversed().forEach { index ->
                    val register = mutableMethod
                        .getInstruction<OneRegisterInstruction>(index + 1)
                        .registerA

                    mutableMethod.addInstructions(
                        index + 2,
                        """
                            invoke-static { }, $CREDENTIALS_CLASS_DESCRIPTOR->signInIntent()Landroid/content/Intent;
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
        //    account. The extension writes that account into the app's own database, but only once
        //    the file exists, and Room creates it lazily, so on a fresh install the first check
        //    happens before there is anything to find. That one query hands back a fabricated
        //    account to cover the gap. The DAO carries no strings of its own and is identified by
        //    the call checkUser() makes just before logging the result.
        val checkUserMethod = CheckUserFingerprint.method
        val checkUserLogIndex = checkUserMethod.indexOfFirstStringInstructionOrThrow(
            CHECK_USER_LOG_PREFIX,
        )

        val selectedUserQueryIndex = checkUserMethod.indexOfFirstInstructionReversed(
            checkUserLogIndex,
        ) {
            if (opcode != Opcode.INVOKE_VIRTUAL) return@indexOfFirstInstructionReversed false

            val reference = getReference<MethodReference>()
                ?: return@indexOfFirstInstructionReversed false

            reference.returnType == "Ljava/lang/Object;" &&
                reference.parameterTypes.size == 1 &&
                // The account manager calls its own suspend helpers here too; the DAO is the only
                // call that leaves the class.
                reference.definingClass != checkUserMethod.definingClass
        }

        if (selectedUserQueryIndex < 0) {
            throw PatchException("Could not find the selected account query in checkUser().")
        }

        val selectedUserQuery = checkUserMethod
            .getInstruction(selectedUserQueryIndex)
            .getReference<MethodReference>()
            ?: throw PatchException("Could not resolve the selected account query.")

        // 6. The account itself. Its constructor takes the columns in schema order: id, sign_id,
        //    fire_id, email, name, avatar, time_zone, currency and the selected flag. The three id
        //    columns share the publisher id because the only thing derived from the account id is
        //    the refresh token key, which step 1 answers for any id. The factory lives on the entity
        //    because it needs ten registers, more than the DAO method reserves.
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
                invoke-static { }, $CREDENTIALS_CLASS_DESCRIPTOR->timeZone()Ljava/lang/String;
                move-result-object v7
                invoke-static { }, $CREDENTIALS_CLASS_DESCRIPTOR->currency()Ljava/lang/String;
                move-result-object v8

                new-instance v0, $USER_CLASS_DESCRIPTOR
                move-object v2, v1
                move-object v3, v1
                const-string v4, ""
                const-string v5, ""
                const-string v6, ""
                const/4 v9, 0x1
                invoke-direct/range { v0 .. v9 }, $USER_CLASS_DESCRIPTOR-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V
                return-object v0
            """,
        )

        mutableClassDefBy(USER_CLASS_DESCRIPTOR).methods.add(syntheticUserFactory)

        // Only stand in once credentials exist, so an unconfigured build still reaches the login
        // screen rather than looping on an account that cannot be authenticated.
        navigate(selectedUserQuery).stop().addInstructionsWithLabels(
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
