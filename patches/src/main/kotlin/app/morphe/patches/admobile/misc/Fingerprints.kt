package app.morphe.patches.admobile.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patches.admobile.Constants.VERIFY_APP_PURCHASE_EXCEPTION_LOG
import app.morphe.patches.admobile.Constants.VERIFY_APP_PURCHASE_LOG_PREFIX

/**
 * `verifyAppPurchase`, the suspend body that restores the pro state on startup: it reads the
 * purchase json and the signature persisted after the last Play purchase, checks them against the
 * stored Play public key, and writes the outcome to the app wide pro flag.
 *
 * It is the only writer of that flag, and it runs before the billing client connects, so the value
 * it stores is what every pro gate reads. The billing client re-runs it whenever it processes a
 * purchase list, including an empty one.
 */
internal object VerifyAppPurchaseFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    strings = listOf(VERIFY_APP_PURCHASE_LOG_PREFIX, VERIFY_APP_PURCHASE_EXCEPTION_LOG),
)
