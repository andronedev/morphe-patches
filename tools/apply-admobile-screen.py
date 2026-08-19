#!/usr/bin/env python3
"""Apply the extension-backed AdMobile credential patches to an apktool-decoded APK.

Same edits as Custom AdMob Credentials and AdMob Credentials Screen, for when the Morphe toolchain
is unavailable. Unlike the patches, the extension dex has to be built and injected by hand:

    javac --release 8 -classpath android.jar -d classes \
        extensions/admobile/src/main/java/app/morphe/extension/admobile/*.java
    java -cp r8.jar com.android.tools.r8.D8 --release --min-api 23 --lib android.jar \
        --output dex $(find classes -name '*.class')

    java -jar apktool.jar d -o dec AdMobile.apk
    python3 tools/apply-admobile-screen.py dec
    java -jar apktool.jar b -o AdMobile-patched.apk dec
    zip -j AdMobile-patched.apk dex/classes.dex           # rename to the next free classesN.dex
    java -jar uber-apk-signer.jar --apks AdMobile-patched.apk --allowResign

The anchors come from AdMobile 2.4.8; later versions rename them, which is what the patches
themselves handle.
"""

import os
import re
import sys

EXTENSION = "Lapp/morphe/extension/admobile/Credentials;"
USER = "Lio/stark/admob/model/entity/User;"

APP_STORE = "xf/i.smali"
USER_DAO = "se/j.smali"
USER_ENTITY = "io/stark/admob/model/entity/User.smali"
SIGN_IN_CLIENT = "Landroid/content/Intent;"
APPLICATION = "io/stark/admob/App.smali"

ANCHORS = {
    "datastore_read": ".method public final g(Ll1/d;Lyh/c;)Ljava/lang/Object;\n    .locals 7\n",
    "legacy_read": ".method public final h(Ljava/lang/String;)Ljava/lang/String;\n    .locals 4\n",
    "constructor": ".method public constructor <init>(Ljava/lang/String;Lh1/f;Landroid/content/"
                   "SharedPreferences;Ldh/a;Ljj/c;Lxf/t;)V\n    .locals 0\n",
    "datastore_write": ".method public final n(Ll1/d;Ljava/lang/String;Lyh/c;)Ljava/lang/Object;\n"
                       "    .locals 3\n",
    "legacy_write": ".method public final o(Ljava/lang/String;Ljava/lang/String;)V\n    .locals 1\n",
    "selected_user": ".method public final b(Lyh/c;)Ljava/lang/Object;\n    .locals 4\n",
    "on_create": ".method public final onCreate()V\n    .locals 7\n",
}


def insert_after(path, anchor, body):
    with open(path) as handle:
        source = handle.read()

    if anchor not in source:
        sys.exit(f"anchor not found in {path}:\n{anchor}")

    with open(path, "w") as handle:
        handle.write(source.replace(anchor, anchor + body, 1))


def patch_application(smali_dir):
    """The form writes to private preferences, so the extension needs a context before any read."""
    insert_after(
        os.path.join(smali_dir, APPLICATION),
        ANCHORS["on_create"],
        f"""
    invoke-static {{p0}}, {EXTENSION}->init(Landroid/content/Context;)V
""",
    )


def patch_datastore_read(smali_dir):
    """A null answer falls through, so an unconfigured build behaves exactly as before."""
    insert_after(
        os.path.join(smali_dir, APP_STORE),
        ANCHORS["datastore_read"],
        f"""
    iget-object v0, p1, Ll1/d;->a:Ljava/lang/String;

    invoke-static {{v0}}, {EXTENSION}->forDataStoreKey(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    if-eqz v0, :morphe_original

    return-object v0

    :morphe_original
    nop
""",
    )


def patch_legacy_read(smali_dir):
    insert_after(
        os.path.join(smali_dir, APP_STORE),
        ANCHORS["legacy_read"],
        f"""
    invoke-static {{p1}}, {EXTENSION}->forLegacyKey(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    if-eqz v0, :morphe_original_legacy

    return-object v0

    :morphe_original_legacy
    nop
""",
    )


def patch_writes(smali_dir):
    """Mirror the app's own token writes, so a refreshed token is not shadowed by an expired one."""
    insert_after(
        os.path.join(smali_dir, APP_STORE),
        ANCHORS["datastore_write"],
        f"""
    iget-object v0, p1, Ll1/d;->a:Ljava/lang/String;

    invoke-static {{v0, p2}}, {EXTENSION}->observeWrite(Ljava/lang/String;Ljava/lang/String;)V
""",
    )

    insert_after(
        os.path.join(smali_dir, APP_STORE),
        ANCHORS["legacy_write"],
        f"""
    invoke-static {{p1, p2}}, {EXTENSION}->observeWrite(Ljava/lang/String;Ljava/lang/String;)V
""",
    )


def patch_constructor(smali_dir):
    """The client id is read once here and travels to both token requests as a field.

    The constructor reserves no locals, so the parameter register is reused in place.
    """
    insert_after(
        os.path.join(smali_dir, APP_STORE),
        ANCHORS["constructor"],
        f"""
    invoke-static {{p1}}, {EXTENSION}->clientIdOrOriginal(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p1
""",
    )


def add_synthetic_user(smali_dir):
    """Columns in schema order: id, sign_id, fire_id, email, name, avatar, time_zone, currency,
    is_selected. The three id columns share the publisher id, because the only value derived from
    the account id is the refresh token key, which the DataStore hook answers for any id.
    """
    path = os.path.join(smali_dir, USER_ENTITY)
    with open(path) as handle:
        source = handle.read().rstrip()

    with open(path, "w") as handle:
        handle.write(
            source
            + f"""

.method public static morpheSyntheticUser()Lio/stark/admob/model/entity/User;
    .locals 10

    invoke-static {{}}, {EXTENSION}->publisherId()Ljava/lang/String;

    move-result-object v1

    invoke-static {{}}, {EXTENSION}->email()Ljava/lang/String;

    move-result-object v4

    invoke-static {{}}, {EXTENSION}->timeZone()Ljava/lang/String;

    move-result-object v7

    invoke-static {{}}, {EXTENSION}->currency()Ljava/lang/String;

    move-result-object v8

    new-instance v0, {USER}

    move-object v2, v1

    move-object v3, v1

    move-object v5, v4

    const-string v6, ""

    const/4 v9, 0x1

    invoke-direct/range {{v0 .. v9}}, {USER}-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V

    return-object v0
.end method
"""
        )


def patch_selected_user_query(smali_dir):
    """checkUser() sends the app to the login screen when this comes back null.

    Only stand in once credentials exist, so an unconfigured build still reaches the login screen
    rather than looping on an account that cannot be authenticated.
    """
    insert_after(
        os.path.join(smali_dir, USER_DAO),
        ANCHORS["selected_user"],
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
    )


def patch_sign_in_intent(smali_dir):
    """The launch screen's sign in button opens the form while nothing is configured.

    Both the sign-in client and the click handler are obfuscated, but the launch fragment is named
    in the navigation graph, so the single no-argument call returning an Intent inside a method that
    mentions it is the one to redirect.
    """
    # apktool interleaves .line directives and blank lines between the call and its result.
    pattern = re.compile(
        r"(invoke-virtual \{v\d+\}, L[^;]+;->\w+\(\)Landroid/content/Intent;\n"
        r"(?:[ \t]*\n|[ \t]*\.line \d+\n)*"
        r"[ \t]*move-result-object (v\d+)\n)"
    )

    # Every call site is redirected, not just the launch screen's: the add account action builds the
    # same intent, and once signed in it is the only way back to the form.
    redirected = 0

    for directory, _, names in os.walk(smali_dir):
        for name in names:
            if not name.endswith(".smali"):
                continue

            path = os.path.join(directory, name)
            with open(path) as handle:
                source = handle.read()

            if SIGN_IN_CLIENT not in source:
                continue

            patched, count = pattern.subn(
                lambda found: found.group(1)
                + f"""
    invoke-static {{{found.group(2)}}}, {EXTENSION}->signInIntentOrOriginal(Landroid/content/Intent;)Landroid/content/Intent;

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

    # Check every anchor before writing anything. A half-applied run leaves the tree with duplicate
    # labels, and the only way back is to decode again.
    with open(os.path.join(smali_dir, APP_STORE)) as handle:
        app_store = handle.read()

    if EXTENSION in app_store:
        sys.exit("already patched; decode the APK again to start from a clean tree")

    for name, anchor in ANCHORS.items():
        path = APPLICATION if name == "on_create" else (
            USER_DAO if name == "selected_user" else APP_STORE
        )
        with open(os.path.join(smali_dir, path)) as handle:
            if anchor not in handle.read():
                sys.exit(f"anchor '{name}' not found in {path}")

    patch_application(smali_dir)
    patch_datastore_read(smali_dir)
    patch_legacy_read(smali_dir)
    patch_writes(smali_dir)
    patch_constructor(smali_dir)
    add_synthetic_user(smali_dir)
    patch_sign_in_intent(smali_dir)
    patch_selected_user_query(smali_dir)
    patch_manifest(decoded_dir)

    print("applied 10 edits; now build, inject the extension dex, and sign")


if __name__ == "__main__":
    main()
