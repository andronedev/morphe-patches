package app.morphe.patches.admobile.auth

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.admobile.Constants.COMPATIBILITY_ADMOBILE
import app.morphe.patches.admobile.Constants.CHECK_USER_LOG_PREFIX
import app.morphe.patches.admobile.Constants.LEGACY_REFRESH_TOKEN_KEY
import app.morphe.patches.admobile.Constants.PREFERENCES_KEY_CLASS_DESCRIPTOR
import app.morphe.patches.admobile.Constants.REFRESH_TOKEN_KEY_PREFIX
import app.morphe.patches.admobile.Constants.SYNTHETIC_USER_METHOD_NAME
import app.morphe.patches.admobile.Constants.USER_CLASS_DESCRIPTOR
import app.morphe.patches.admobile.Constants.USER_PUB_ID_KEY
import app.morphe.patches.admobile.Constants.WEB_CLIENT_SECRET_KEY
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstStringInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

@Suppress("unused")
val customCredentialsPatch = bytecodePatch(
    name = "Custom AdMob Credentials",
    description = "Sign in with your own Google OAuth client and refresh token instead of the " +
        "Google Sign-In flow, which a re-signed APK cannot use.",
) {
    compatibleWith(COMPATIBILITY_ADMOBILE)

    val clientSecretOption = stringOption(
        key = "clientSecret",
        default = "",
        title = "OAuth client secret",
        description = "Client secret of your own Google OAuth client.",
        required = false,
    )

    val refreshTokenOption = stringOption(
        key = "refreshToken",
        default = "",
        title = "OAuth refresh token",
        description = "Refresh token issued to that client for the admob.readonly scope.",
        required = false,
    )

    val publisherIdOption = stringOption(
        key = "publisherId",
        default = "",
        title = "AdMob publisher id",
        description = "Your AdMob publisher id, for example pub-0000000000000000.",
        required = false,
    )

    val accountEmailOption = stringOption(
        key = "accountEmail",
        default = "",
        title = "Account email",
        description = "Email shown for the account. Only a label.",
        required = false,
    )

    val timeZoneOption = stringOption(
        key = "timeZone",
        default = "UTC",
        title = "Report time zone",
        description = "Time zone the AdMob reports are requested in.",
        required = false,
    )

    val currencyOption = stringOption(
        key = "currency",
        default = "USD",
        title = "Report currency",
        description = "Currency the AdMob earnings are labelled with.",
        required = false,
    )

    execute {
        val clientSecret = clientSecretOption.value?.trim().orEmpty()
        val refreshToken = refreshTokenOption.value?.trim().orEmpty()
        val publisherId = publisherIdOption.value?.trim().orEmpty()
        val accountEmail = accountEmailOption.value?.trim().orEmpty()
        val timeZone = timeZoneOption.value?.trim().orEmpty().ifEmpty { "UTC" }
        val currency = currencyOption.value?.trim().orEmpty().ifEmpty { "USD" }

        mapOf(
            "clientSecret" to clientSecret,
            "refreshToken" to refreshToken,
            "publisherId" to publisherId,
            "accountEmail" to accountEmail,
        ).forEach { (key, value) ->
            if (value.isBlank()) throw PatchException("Option '$key' is required.")
        }

        // 1. The DataStore read. Every secret the token requests need is decrypted here, keyed by
        //    name, so answering three names is enough to stand in for a whole signed-in session.
        //    The method is a suspend function: returning a value directly, rather than the
        //    COROUTINE_SUSPENDED marker, is exactly what it does when the data is already in memory.
        AppStoreReadFingerprint.method.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p1, $PREFERENCES_KEY_CLASS_DESCRIPTOR->a:Ljava/lang/String;

                const-string v1, "$WEB_CLIENT_SECRET_KEY"
                invoke-virtual { v0, v1 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v2
                if-eqz v2, :not_client_secret
                const-string v0, "$clientSecret"
                return-object v0

                :not_client_secret
                const-string v1, "$USER_PUB_ID_KEY"
                invoke-virtual { v0, v1 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v2
                if-eqz v2, :not_publisher_id
                const-string v0, "$publisherId"
                return-object v0

                :not_publisher_id
                const-string v1, "$REFRESH_TOKEN_KEY_PREFIX"
                invoke-virtual { v0, v1 }, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
                move-result v2
                if-eqz v2, :original
                const-string v0, "$refreshToken"
                return-object v0

                :original
                nop
            """,
        )

        // 2. The pre-DataStore read. The OkHttp authenticators that refresh an expired access token
        //    still look the refresh token up here first.
        AppStoreLegacyReadFingerprint.method.addInstructionsWithLabels(
            0,
            """
                const-string v0, "$LEGACY_REFRESH_TOKEN_KEY"
                invoke-virtual { p1, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :original
                const-string v0, "$refreshToken"
                return-object v0

                :original
                nop
            """,
        )

        // 3. A session also needs an account row. checkUser() asks the user DAO for the selected
        //    account and reports "no session" when it comes back null, which is what sends a fresh
        //    install to the login screen. The DAO is fully obfuscated and holds no strings, so it is
        //    identified by the call checkUser() makes right before logging what it got back.
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

        // 4. Build the account the DAO will hand back. The entity constructor takes the columns in
        //    schema order: id, sign_id, fire_id, email, name, avatar, time_zone, currency and the
        //    selected flag. The three id columns share a value; only the refresh token key derived
        //    from the account id matters, and step 1 answers that key whatever the id turns out to
        //    be. The factory lives on the entity because it needs ten registers, more than the DAO
        //    method reserves.
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
                new-instance v0, $USER_CLASS_DESCRIPTOR
                const-string v1, "$publisherId"
                const-string v2, "$publisherId"
                const-string v3, "$publisherId"
                const-string v4, "$accountEmail"
                const-string v5, "$accountEmail"
                const-string v6, ""
                const-string v7, "$timeZone"
                const-string v8, "$currency"
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

        selectedUserQueryMethod.addInstructions(
            0,
            """
                invoke-static { }, $USER_CLASS_DESCRIPTOR->$SYNTHETIC_USER_METHOD_NAME()$USER_CLASS_DESCRIPTOR
                move-result-object v0
                return-object v0
            """,
        )
    }
}
