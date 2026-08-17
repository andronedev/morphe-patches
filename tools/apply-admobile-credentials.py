#!/usr/bin/env python3
"""Apply the AdMobile credential patches to an apktool-decoded APK.

Same five edits as the Custom AdMob Credentials and Custom OAuth Client ID patches, for when the
Morphe toolchain is not available. Decode with apktool, run this, build and sign again:

    java -jar apktool.jar d -o dec AdMobile.apk
    python3 tools/apply-admobile-credentials.py dec --client-id ... --client-secret ... \
        --refresh-token ... --publisher-id pub-... --email you@example.com
    java -jar apktool.jar b -o AdMobile-patched.apk dec
    java -jar uber-apk-signer.jar --apks AdMobile-patched.apk --allowResign

The edits are anchored on obfuscated names taken from AdMobile 2.4.8. Later versions rename them,
which is what the patches themselves handle; this script will simply fail to find its anchors.
"""

import argparse
import os
import re
import sys

APP_STORE = "xf/i.smali"
USER_DAO = "se/j.smali"
USER_ENTITY = "io/stark/admob/model/entity/User.smali"

DATASTORE_READ = ".method public final g(Ll1/d;Lyh/c;)Ljava/lang/Object;\n    .locals 7\n"
LEGACY_READ = ".method public final h(Ljava/lang/String;)Ljava/lang/String;\n    .locals 4\n"
SELECTED_USER_QUERY = ".method public final b(Lyh/c;)Ljava/lang/Object;\n    .locals 4\n"


def insert_after(path, anchor, body):
    with open(path) as handle:
        source = handle.read()

    if anchor not in source:
        sys.exit(f"anchor not found in {path}:\n{anchor}")

    with open(path, "w") as handle:
        handle.write(source.replace(anchor, anchor + body, 1))


def patch_datastore_read(smali_dir, args):
    """Answer the three key names the OAuth code path decrypts out of the DataStore.

    This is a suspend function, so returning a value instead of the COROUTINE_SUSPENDED marker is
    what it already does whenever the value is available without suspending.
    """
    insert_after(
        os.path.join(smali_dir, APP_STORE),
        DATASTORE_READ,
        f"""
    iget-object v0, p1, Ll1/d;->a:Ljava/lang/String;

    const-string v1, "web_client_secret"

    invoke-virtual {{v0, v1}}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v2

    if-eqz v2, :morphe_not_secret

    const-string v0, "{args.client_secret}"

    return-object v0

    :morphe_not_secret
    const-string v1, "user_pub_id"

    invoke-virtual {{v0, v1}}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v2

    if-eqz v2, :morphe_not_pubid

    const-string v0, "{args.publisher_id}"

    return-object v0

    :morphe_not_pubid
    const-string v1, "token_refresh_"

    invoke-virtual {{v0, v1}}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z

    move-result v2

    if-eqz v2, :morphe_original

    const-string v0, "{args.refresh_token}"

    return-object v0

    :morphe_original
    nop
""",
    )


def patch_legacy_read(smali_dir, args):
    """The OkHttp authenticators look the refresh token up in the pre-DataStore storage first."""
    insert_after(
        os.path.join(smali_dir, APP_STORE),
        LEGACY_READ,
        f"""
    const-string v0, "user_token_refresh"

    invoke-virtual {{p1, v0}}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v0

    if-eqz v0, :morphe_original_legacy

    const-string v0, "{args.refresh_token}"

    return-object v0

    :morphe_original_legacy
    nop
""",
    )


def add_synthetic_user(smali_dir, args):
    """Add the account factory to the entity.

    Constructor columns in schema order: id, sign_id, fire_id, email, name, avatar, time_zone,
    currency, is_selected. The three id columns share a value because the only thing derived from
    the account id is the refresh token key, and the DataStore hook answers that key for any id.
    It lives on the entity because ten registers are needed, more than the DAO method reserves.
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

    new-instance v0, Lio/stark/admob/model/entity/User;

    const-string v1, "{args.publisher_id}"

    const-string v2, "{args.publisher_id}"

    const-string v3, "{args.publisher_id}"

    const-string v4, "{args.email}"

    const-string v5, "{args.email}"

    const-string v6, ""

    const-string v7, "{args.time_zone}"

    const-string v8, "{args.currency}"

    const/4 v9, 0x1

    invoke-direct/range {{v0 .. v9}}, Lio/stark/admob/model/entity/User;-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V

    return-object v0
.end method
"""
        )


def patch_selected_user_query(smali_dir):
    """checkUser() sends the app to the login screen when this query comes back null."""
    insert_after(
        os.path.join(smali_dir, USER_DAO),
        SELECTED_USER_QUERY,
        """
    invoke-static {}, Lio/stark/admob/model/entity/User;->morpheSyntheticUser()Lio/stark/admob/model/entity/User;

    move-result-object v0

    return-object v0
""",
    )


def patch_client_id(decoded_dir, args):
    """The app store is built with getString(R.string.web_client_id) and sends it as client_id."""
    path = os.path.join(decoded_dir, "res/values/strings.xml")
    with open(path) as handle:
        source = handle.read()

    source, count = re.subn(
        r'(<string name="web_client_id">)[^<]*(</string>)',
        lambda match: match.group(1) + args.client_id + match.group(2),
        source,
    )
    if count != 1:
        sys.exit(f"expected one web_client_id string, found {count}")

    with open(path, "w") as handle:
        handle.write(source)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("decoded_dir", help="apktool output directory")
    parser.add_argument("--client-id", required=True, help="your OAuth client id")
    parser.add_argument("--client-secret", required=True, help="your OAuth client secret")
    parser.add_argument("--refresh-token", required=True, help="refresh token for admob.readonly")
    parser.add_argument("--publisher-id", required=True, help="AdMob publisher id, pub-...")
    parser.add_argument("--email", required=True, help="email shown for the account")
    parser.add_argument("--time-zone", default="UTC", help="report time zone (default: UTC)")
    parser.add_argument("--currency", default="USD", help="report currency (default: USD)")
    args = parser.parse_args()

    for name in ("client_id", "client_secret", "refresh_token", "publisher_id", "email"):
        if '"' in getattr(args, name):
            sys.exit(f"--{name.replace('_', '-')} must not contain a double quote")

    smali_dir = os.path.join(args.decoded_dir, "smali_classes3")
    if not os.path.isdir(smali_dir):
        sys.exit(f"{smali_dir} not found; decode the base APK with apktool first")

    patch_datastore_read(smali_dir, args)
    patch_legacy_read(smali_dir, args)
    add_synthetic_user(smali_dir, args)
    patch_selected_user_query(smali_dir)
    patch_client_id(args.decoded_dir, args)

    print("applied 5 edits: datastore read, legacy read, synthetic account, DAO query, client id")


if __name__ == "__main__":
    main()
