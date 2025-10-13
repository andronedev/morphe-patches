package app.revanced.patches.notewise.misc.fingerprints

import app.revanced.patcher.fingerprint.MethodFingerprint

object IsUnlimitedFingerprint : MethodFingerprint(
    strings = listOf("notewise_unlimited"),
    customFingerprint = { methodDef, _ ->
        methodDef.definingClass == "Lcom/yygg/note/app/purchase/PurchaseUtils;" &&
        methodDef.name == "d" &&
        methodDef.parameters == listOf("Lcom/revenuecat/purchases/CustomerInfo;") &&
        methodDef.returnType == "Ljava/util/Optional;"
    }
)