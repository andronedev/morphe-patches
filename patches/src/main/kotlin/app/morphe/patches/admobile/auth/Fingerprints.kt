package app.morphe.patches.admobile.auth

import app.morphe.patcher.Fingerprint
import app.morphe.patches.admobile.Constants.ACCOUNT_MANAGER_LOG_TAG
import app.morphe.patches.admobile.Constants.CHECK_USER_LOG_PREFIX
import app.morphe.patches.admobile.Constants.CHECK_USER_REINSERT_LOG_PREFIX
import app.morphe.patches.admobile.Constants.PREFERENCES_KEY_CLASS_DESCRIPTOR
import com.android.tools.smali.dexlib2.iface.ClassDef

/**
 * The app store keeps every secret in a DataStore whose values are encrypted at rest, and declares
 * one static [PREFERENCES_KEY_CLASS_DESCRIPTOR] field per key it owns. No other class does, which
 * makes the field types a reliable way to recognise it through obfuscation.
 */
private fun ClassDef.isAppStore() =
    fields.count { it.type == PREFERENCES_KEY_CLASS_DESCRIPTOR } >= 5

/**
 * The single decrypting read of the DataStore, `suspend fun (Preferences.Key) -> String?`. Every
 * secret the OAuth code path needs travels through it: the client secret, the publisher id, and the
 * per-account refresh token.
 */
internal object AppStoreReadFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf(PREFERENCES_KEY_CLASS_DESCRIPTOR, "Lyh/c;"),
    custom = { _, classDef -> classDef.isAppStore() },
)

/**
 * The same read for the pre-DataStore storage: a decrypting `SharedPreferences` lookup by name,
 * `fun (String) -> String?`. The OkHttp authenticators still take this path for the refresh token.
 */
internal object AppStoreLegacyReadFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/String;"),
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
    parameters = listOf("Lyh/c;"),
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
    custom = { method, classDef -> method.name == "<init>" && classDef.isAppStore() },
)
