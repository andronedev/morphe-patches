#!/usr/bin/env python3
"""Apply the AdMobile patches to an apktool-decoded APK.

Same edits as Pro Unlock, Serverless Sign-In and AdMob Credentials Screen, for when the Morphe
toolchain is unavailable. Unlike the patches, the extension dex has to be built and injected by
hand:

    javac --release 8 -classpath android.jar -d classes \
        extensions/admobile/src/main/java/app/morphe/extension/admobile/*.java
    java -cp r8.jar com.android.tools.r8.D8 --release --min-api 23 --lib android.jar \
        --output dex $(find classes -name '*.class')

    java -jar apktool.jar d -o dec AdMobile.apk           # a universal APK, not one split of a set
    python3 tools/apply-admobile.py dec
    java -jar apktool.jar b -o AdMobile-patched.apk dec
    zip -j AdMobile-patched.apk dex/classes.dex           # rename to the next free classesN.dex
    java -jar uber-apk-signer.jar --apks AdMobile-patched.apk --allowResign

This is the same set of edits as the Kotlin patches, spelled out for one build. The patches find
their anchors through fingerprints; the anchors below are the obfuscated names of AdMobile 2.4.8,
so a later version needs this table updated. Change one side and the other needs the same change.
"""

import os
import re
import sys

EXTENSION = "Lapp/morphe/extension/admobile/Credentials;"
USER = "Lio/stark/admob/model/entity/User;"

APP_STORE = "xf/i.smali"
SETTINGS_STORE = "xf/t.smali"
USER_DAO = "se/j.smali"
USER_ENTITY = "io/stark/admob/model/entity/User.smali"
APPLICATION = "io/stark/admob/App.smali"
ACCOUNT_MANAGER = "af/m.smali"
PRO_FLAG = "Lxf/i;->g:Z"
PRO_LIVE_DATA_OWNER = "me/h.smali"

INTENT = "Landroid/content/Intent;"

# name -> (file, anchor, body inserted straight after the anchor).
#
# The anchor is the method header plus its .locals line, which is what pins each edit to one method
# and guarantees the registers the body uses exist.
#
# A body inserted at the top of a suspend function runs on every resumption of it as well as on
# entry, and Kotlin passes null for the value parameters when it resumes. Anything reading one of
# them has to check it first.
EDITS = {
    # The form writes to private preferences, so the extension needs a context before any read.
    "application": (
        APPLICATION,
        ".method public final onCreate()V\n    .locals 7\n",
        f"""
    invoke-static {{p0}}, {EXTENSION}->init(Landroid/content/Context;)V
""",
    ),
    # A null answer falls through, so an unconfigured build behaves exactly as before.
    "datastore_read": (
        APP_STORE,
        ".method public final g(Ll1/d;Lyh/c;)Ljava/lang/Object;\n    .locals 7\n",
        f"""
    if-eqz p1, :morphe_original

    iget-object v0, p1, Ll1/d;->a:Ljava/lang/String;

    invoke-static {{v0}}, {EXTENSION}->forDataStoreKey(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    if-eqz v0, :morphe_original

    return-object v0

    :morphe_original
    nop
""",
    ),
    # The same read on the settings store, which is where the currency symbol lives.
    "settings_read": (
        SETTINGS_STORE,
        ".method public final f(Ll1/d;Lyh/c;)Ljava/lang/Object;\n    .locals 4\n",
        f"""
    if-eqz p1, :morphe_original_setting

    iget-object v0, p1, Ll1/d;->a:Ljava/lang/String;

    invoke-static {{v0}}, {EXTENSION}->forDataStoreKey(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    if-eqz v0, :morphe_original_setting

    return-object v0

    :morphe_original_setting
    nop
""",
    ),
    "legacy_read": (
        APP_STORE,
        ".method public final h(Ljava/lang/String;)Ljava/lang/String;\n    .locals 4\n",
        f"""
    invoke-static {{p1}}, {EXTENSION}->forLegacyKey(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    if-eqz v0, :morphe_original_legacy

    return-object v0

    :morphe_original_legacy
    nop
""",
    ),
    # Mirror the app's own token writes, so a refreshed token is not shadowed by an expired one.
    "datastore_write": (
        APP_STORE,
        ".method public final n(Ll1/d;Ljava/lang/String;Lyh/c;)Ljava/lang/Object;\n    .locals 3\n",
        f"""
    if-eqz p1, :morphe_original_write

    iget-object v0, p1, Ll1/d;->a:Ljava/lang/String;

    invoke-static {{v0, p2}}, {EXTENSION}->observeWrite(Ljava/lang/String;Ljava/lang/String;)V

    :morphe_original_write
    nop
""",
    ),
    "legacy_write": (
        APP_STORE,
        ".method public final o(Ljava/lang/String;Ljava/lang/String;)V\n    .locals 1\n",
        f"""
    invoke-static {{p1, p2}}, {EXTENSION}->observeWrite(Ljava/lang/String;Ljava/lang/String;)V
""",
    ),
    # The app forgets the account in its database, which is not where the patched one lives.
    "sign_out": (
        ACCOUNT_MANAGER,
        ".method public final j(Lyh/c;)Ljava/lang/Object;\n    .locals 12\n",
        f"""
    invoke-static {{}}, {EXTENSION}->signOut()V
""",
    ),
    # The client id is read once here and travels to both token requests as a field. The
    # constructor reserves no locals, so the parameter register is reused in place.
    "constructor": (
        APP_STORE,
        ".method public constructor <init>(Ljava/lang/String;Lh1/f;Landroid/content/"
        "SharedPreferences;Ldh/a;Ljj/c;Lxf/t;)V\n    .locals 0\n",
        f"""
    invoke-static {{p1}}, {EXTENSION}->clientIdOrOriginal(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p1
""",
    ),
    # checkUser() sends the app to the login screen when this comes back null. Only stand in once
    # credentials exist, so an unconfigured build still reaches the login screen.
    "selected_user": (
        USER_DAO,
        ".method public final b(Lyh/c;)Ljava/lang/Object;\n    .locals 4\n",
        f"""
    invoke-static {{}}, {EXTENSION}->isConfigured()Z

    move-result v0

    if-eqz v0, :morphe_original_user

    invoke-static {{}}, {USER}->morpheSyntheticUser()Lio/stark/admob/model/entity/User;

    move-result-object v0

    return-object v0

    :morphe_original_user
    nop
""",
    ),
}

# The account factory, appended to the entity rather than inserted. Constructor columns in schema
# order: id, sign_id, fire_id, email, name, avatar, time_zone, currency, is_selected. The three id
# columns share the publisher id, because the only value derived from the account id is the refresh
# token key, which the DataStore hook answers for any id.
SYNTHETIC_USER = f"""

.method public static morpheSyntheticUser()Lio/stark/admob/model/entity/User;
    .locals 10

    invoke-static {{}}, {EXTENSION}->publisherId()Ljava/lang/String;

    move-result-object v1

    invoke-static {{}}, {EXTENSION}->timeZone()Ljava/lang/String;

    move-result-object v7

    invoke-static {{}}, {EXTENSION}->currency()Ljava/lang/String;

    move-result-object v8

    new-instance v0, {USER}

    move-object v2, v1

    move-object v3, v1

    const-string v4, ""

    const-string v5, ""

    const-string v6, ""

    const/4 v9, 0x1

    invoke-direct/range {{v0 .. v9}}, {USER}-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V

    return-object v0
.end method
"""

# apktool interleaves .line directives and blank lines between the call and its result.
SIGN_IN_INTENT = re.compile(
    r"(invoke-virtual \{v\d+\}, L[^;]+;->\w+\(\)Landroid/content/Intent;\n"
    r"(?:[ \t]*\n|[ \t]*\.line \d+\n)*"
    r"[ \t]*move-result-object (v\d+)\n)"
)



def patch_pro_unlock(smali_dir):
    """Answer every read of the pro flag with true, wherever it is read.

    Forcing the two writes in verifyAppPurchase was not enough: that body only runs once the
    billing client processes a purchase list, so a home screen widget, whose worker can start the
    process on its own, read the flag before anything had set it. Replacing the reads leaves no
    order of events in which a gate sees false.

    The instruction is replaced rather than preceded, so the object it would have dereferenced is
    left alone.
    """
    forced = 0

    for directory, _, names in os.walk(smali_dir):
        for name in names:
            if not name.endswith(".smali"):
                continue

            path = os.path.join(directory, name)
            with open(path) as handle:
                source = handle.read()

            if PRO_FLAG not in source:
                continue

            patched, count = re.subn(
                rf"    iget-boolean (v\d+), v\d+, {re.escape(PRO_FLAG)}\n",
                r"    const/4 \1, 0x1\n",
                source,
            )
            if not count:
                continue

            with open(path, "w") as handle:
                handle.write(patched)

            forced += count

    if not forced:
        sys.exit("no read of the pro flag found")

    # The screens observe a LiveData rather than the field, and it is created empty, so "is the
    # user pro" answers no until the billing client fills it. Starting it as true closes that
    # window and makes the app look pro from the first frame.
    path = os.path.join(smali_dir, PRO_LIVE_DATA_OWNER)
    with open(path) as handle:
        source = handle.read()

    anchor = "    iput-object p1, p0, Lme/h;->G:Landroidx/lifecycle/MutableLiveData;\n"
    if anchor not in source:
        sys.exit("pro LiveData assignment not found")

    with open(path, "w") as handle:
        handle.write(source.replace(anchor, anchor + """
    sget-object p2, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;

    invoke-virtual {p1, p2}, Landroidx/lifecycle/MutableLiveData;->setValue(Ljava/lang/Object;)V
""", 1))


def patch_sign_in_intent(smali_dir):
    """The sign in button opens the form rather than a Google flow a re-signed APK cannot complete.

    Both the sign-in client and the click handler are obfuscated, but every call site is redirected,
    not just the launch screen's: the add account action builds the same intent, and once signed in
    it is the only way back to the form.
    """
    redirected = 0

    for directory, _, names in os.walk(smali_dir):
        for name in names:
            if not name.endswith(".smali"):
                continue

            path = os.path.join(directory, name)
            with open(path) as handle:
                source = handle.read()

            if INTENT not in source:
                continue

            patched, count = SIGN_IN_INTENT.subn(
                lambda found: found.group(1)
                + f"""
    invoke-static {{}}, {EXTENSION}->signInIntent()Landroid/content/Intent;

    move-result-object {found.group(2)}
""",
                source,
            )

            if not count:
                continue

            with open(path, "w") as handle:
                handle.write(patched)

            redirected += count

    if not redirected:
        sys.exit("sign-in intent call not found")


def patch_manifest(decoded_dir):
    """Declared but not exported: it is reached from inside the app, never from the launcher."""
    path = os.path.join(decoded_dir, "AndroidManifest.xml")
    with open(path) as handle:
        source = handle.read()

    if "CredentialsActivity" in source:
        return

    activity = (
        '<activity android:name="app.morphe.extension.admobile.CredentialsActivity" '
        'android:label="AdMobile credentials" android:exported="false" '
        'android:theme="@style/AppTheme"/>'
    )

    source, count = re.subn(r"</application>", activity + "</application>", source, count=1)
    if count != 1:
        sys.exit("could not find the application element")

    with open(path, "w") as handle:
        handle.write(source)


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)

    decoded_dir = sys.argv[1]
    smali_dir = os.path.join(decoded_dir, "smali_classes3")
    if not os.path.isdir(smali_dir):
        sys.exit(f"{smali_dir} not found; decode the base APK with apktool first")

    # Every file is read once, edited in memory and written once, so a missing anchor leaves the
    # tree untouched. A half-applied run leaves duplicate labels behind and the only way back is to
    # decode again.
    sources = {}
    for name, (path, anchor, body) in EDITS.items():
        source = sources.get(path)
        if source is None:
            with open(os.path.join(smali_dir, path)) as handle:
                source = handle.read()

            if EXTENSION in source:
                sys.exit("already patched; decode the APK again to start from a clean tree")

        if anchor not in source:
            sys.exit(f"anchor '{name}' not found in {path}")

        sources[path] = source.replace(anchor, anchor + body, 1)

    sources[USER_ENTITY] = open(os.path.join(smali_dir, USER_ENTITY)).read().rstrip() + SYNTHETIC_USER

    for path, source in sources.items():
        with open(os.path.join(smali_dir, path), "w") as handle:
            handle.write(source)

    patch_sign_in_intent(smali_dir)
    patch_manifest(decoded_dir)
    patch_pro_unlock(smali_dir)

    print("applied every edit; now build, inject the extension dex, and sign")


if __name__ == "__main__":
    main()
