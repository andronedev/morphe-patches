package app.morphe.patches.admobile.auth

import app.morphe.patcher.Fingerprint
import app.morphe.patches.admobile.Constants.ACCOUNT_MANAGER_LOG_TAG
import app.morphe.patches.admobile.Constants.CHECK_USER_LOG_PREFIX
import app.morphe.patches.admobile.Constants.CHECK_USER_REINSERT_LOG_PREFIX
import app.morphe.patches.admobile.Constants.CURRENCY_CODE_SETTING_KEY
import app.morphe.patches.admobile.Constants.LAUNCH_FRAGMENT_CLASS_DESCRIPTOR
import app.morphe.patches.admobile.Constants.SIGN_OUT_LOG_PREFIX
import app.morphe.patches.admobile.Constants.USER_PUB_ID_KEY
import app.morphe.patches.admobile.Constants.WEB_CLIENT_SECRET_KEY
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** Any object, whatever R8 renamed it to. */
private const val OBJECT = "L"

private fun Method.instructionsOrEmpty() = implementation?.instructions ?: emptyList()

/** Every string constant the class holds, including the ones its static initialiser builds keys from. */
private fun ClassDef.strings() = methods.asSequence()
    .flatMap { it.instructionsOrEmpty().asSequence() }
    .mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
    .toSet()

/**
 * The app has two stores of the same shape: this one, holding the secrets, and the settings store
 * below. Both keep their keys in static fields and read them back through a suspending
 * `(Preferences.Key) -> Any?`, so only the key names tell them apart, and key names are string
 * constants R8 leaves alone.
 */
private fun ClassDef.isAppStore() =
    strings().containsAll(listOf(WEB_CLIENT_SECRET_KEY, USER_PUB_ID_KEY))

private fun ClassDef.isSettingsStore() = CURRENCY_CODE_SETTING_KEY in strings()

/** True when the method mentions [LAUNCH_FRAGMENT_CLASS_DESCRIPTOR], as a call or as a field. */
private fun Method.usesLaunchFragment() = instructionsOrEmpty().any { instruction ->
    when (val reference = (instruction as? ReferenceInstruction)?.reference) {
        is MethodReference -> reference.definingClass == LAUNCH_FRAGMENT_CLASS_DESCRIPTOR
        is FieldReference -> reference.definingClass == LAUNCH_FRAGMENT_CLASS_DESCRIPTOR
        else -> false
    }
}

/** True when the method builds an [android.content.Intent] through a no-argument call. */
private fun Method.buildsIntent() = instructionsOrEmpty().any { instruction ->
    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
    reference?.returnType == "Landroid/content/Intent;" && reference.parameterTypes.isEmpty()
}

/**
 * The single decrypting read of the app store, `suspend fun (Preferences.Key) -> String?`. Every
 * secret the OAuth code path needs travels through it: the client secret, the publisher id, and the
 * per-account access and refresh tokens.
 */
internal object AppStoreReadFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf(OBJECT, OBJECT),
    custom = { _, classDef -> classDef.isAppStore() },
)

/**
 * The same read on the settings store, which is where the app keeps the currency symbol it prefixes
 * every amount with. It is written when an account is selected through the app's own sign in, a
 * path the patched app never takes, so it has to be answered as well.
 */
internal object SettingsStoreReadFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf(OBJECT, OBJECT),
    custom = { _, classDef -> classDef.isSettingsStore() },
)

/**
 * The same read for the pre-DataStore storage: a decrypting `SharedPreferences` lookup by name,
 * `fun (String) -> String?`. The OkHttp authenticators still take this path for the tokens.
 */
internal object AppStoreLegacyReadFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/String;"),
    custom = { _, classDef -> classDef.isAppStore() },
)

/**
 * The app store write, the mirror of [AppStoreReadFingerprint]. Hooked so a token the app refreshes
 * for itself replaces the one handed to it, rather than being shadowed by an expired value.
 */
internal object AppStoreWriteFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf(OBJECT, "Ljava/lang/String;", OBJECT),
    custom = { _, classDef -> classDef.isAppStore() },
)

/** The same write for the pre-DataStore storage, where the authenticators put refreshed tokens. */
internal object AppStoreLegacyWriteFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;"),
    custom = { _, classDef -> classDef.isAppStore() },
)

/**
 * `AccountManager.checkUser()`, the startup session check. It asks the user DAO for the selected
 * account, logs it, and either reports "no session" or validates the account against the AdMob API.
 *
 * The patch does not rewrite it; it reads the DAO call out of it to learn which method returns the
 * selected account, since that DAO is fully obfuscated and carries no strings of its own.
 */
internal object CheckUserFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf(OBJECT),
    strings = listOf(
        ACCOUNT_MANAGER_LOG_TAG,
        CHECK_USER_LOG_PREFIX,
        CHECK_USER_REINSERT_LOG_PREFIX,
    ),
)

/**
 * The app store constructor. Its first argument is the OAuth client id, read once from a string
 * resource and sent as the `client_id` form field of both token requests.
 */
internal object AppStoreConstructorFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, classDef ->
        method.name == "<init>" &&
            method.parameterTypes.firstOrNull() == "Ljava/lang/String;" &&
            classDef.isAppStore()
    },
)

/**
 * `AccountManager.signOut()`. The app forgets the account in its database, which is not where the
 * patched app's account lives, so signing out has to reach the extension too or it comes straight
 * back on the next check.
 */
internal object SignOutFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf(OBJECT),
    strings = listOf(SIGN_OUT_LOG_PREFIX),
)

/**
 * The click handler behind the launch screen's sign in button. It reads the sign-in client off the
 * launch fragment and hands the intent it builds to an activity result launcher.
 *
 * Both the client and this lambda are obfuscated, but the launch fragment is named in the
 * navigation graph, so the one method that mentions it and builds an [android.content.Intent]
 * without arguments is the call to patch.
 */
internal object SignInIntentFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    custom = { method, _ -> method.usesLaunchFragment() && method.buildsIntent() },
)
