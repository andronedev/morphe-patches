#!/usr/bin/env python3
"""Check a patched, apktool-decoded AdMobile tree.

    python3 tools/verify-admobile.py dec

Every edit the patches make is asserted here, so a build is checked before it reaches a device
rather than after. The checks that matter most are not "is the hook present" but the rules the
hooks have to obey, which is where the bugs were:

- A body injected at the top of a suspend function runs on every resumption of it, and Kotlin
  passes null for the value parameters when it resumes. Reading one without checking it first is a
  crash on the first read that has to wait for the disk.
- A hook that answers a read must let an unrecognised key fall through, or the app loses its own
  storage.
"""

import os
import re
import sys

EXTENSION = "Lapp/morphe/extension/admobile/Credentials;"

SMALI = "smali_classes3"
STRINGS = "res/values/strings.xml"
PROFILE_LAYOUT = "res/layout/fragment_profile.xml"

# file -> methods that must call into the extension.
HOOKS = {
    "xf/i.smali": ["forDataStoreKey", "forLegacyKey", "observeWrite", "clientIdOrOriginal"],
    "xf/t.smali": ["forDataStoreKey"],
    "af/m.smali": ["signOut"],
    "se/j.smali": ["isConfigured"],
    "io/stark/admob/App.smali": ["init"],
}

AD_UNITS = ("ad_home_native", "ad_apps_native", "ad_app_info_native")

failures = []


def fail(message):
    failures.append(message)


def read(root, path):
    with open(os.path.join(root, path), encoding="utf-8") as handle:
        return handle.read()


def methods(source):
    """Every method body in a smali file, as (signature, body)."""
    for match in re.finditer(r"^\.method (.+?)$(.*?)^\.end method", source, re.M | re.S):
        yield match.group(1), match.group(2)


def check_hooks(root):
    for path, expected in HOOKS.items():
        source = read(root, os.path.join(SMALI, path))
        for name in expected:
            if f"{EXTENSION}->{name}" not in source:
                fail(f"{path}: no call to {name}")


def check_suspend_guards(root):
    """A parameter read at the top of a suspend method must be guarded against null."""
    for path in HOOKS:
        source = read(root, os.path.join(SMALI, path))

        for signature, body in methods(source):
            if EXTENSION not in body:
                continue

            # Kotlin compiles a suspend function with a Continuation as its last parameter, and
            # calls it again with null arguments to resume it.
            if not re.search(r"\(.*L[^;]+;\)Ljava/lang/Object;$", signature):
                continue

            injected = body.split(f"{EXTENSION}->")[0]
            reads = re.findall(r"iget-object v\d+, (p\d+),", injected)
            if not reads:
                continue

            for parameter in set(reads):
                if not re.search(rf"if-eqz {parameter},", injected):
                    fail(f"{path}: {signature.split('(')[0]} reads {parameter} before the state "
                         f"machine without a null check; it will crash when the coroutine resumes")


def check_fallthrough(root):
    """Answering a read must leave a path back to the app's own storage."""
    for path in ("xf/i.smali", "xf/t.smali"):
        source = read(root, os.path.join(SMALI, path))

        for signature, body in methods(source):
            if f"{EXTENSION}->forDataStoreKey" not in body:
                continue

            answer = body.split(f"{EXTENSION}->forDataStoreKey")[1]
            if "if-eqz" not in answer.split("return-object")[0]:
                fail(f"{path}: {signature.split('(')[0]} returns the extension's answer without "
                     f"checking it, so an unrecognised key never reaches the app's storage")


def check_pro_unlock(root):
    source = read(root, os.path.join(SMALI, "ui/n.smali"))

    writes = re.findall(r"(const/16 (v\d+), 0x1\n\n    )?iput-boolean (v\d+),", source)
    forced = [write for write in writes if write[0]]

    if len(forced) < 2:
        fail(f"pro flag: {len(forced)} of {len(writes)} writes forced, expected both")


def check_hide_ads(root):
    body = read(root, os.path.join(SMALI, "io/stark/admob/ui/widget/ads/AdNativeView.smali"))
    match = re.search(r"^\.method public final setNativeAd.*?^\.end method", body, re.M | re.S)

    if not match:
        fail("hide ads: setNativeAd not found")
        return

    head = match.group(0).split("return-void")[0]
    if "destroy()V" not in head:
        fail("hide ads: setNativeAd does not release the ad before returning")


def check_resources(root):
    strings = read(root, STRINGS)

    for name in AD_UNITS:
        if not re.search(rf'<string name="{name}"\s*/>', strings):
            fail(f"ad unit {name} is not blank")

    if "morphe_premium_active" not in strings:
        fail("the premium label string is missing")

    layout = read(root, PROFILE_LAYOUT)
    if 'android:text="@string/morphe_premium_active"' not in layout:
        fail("the premium button does not carry the label")


def check_manifest(root):
    manifest = read(root, "AndroidManifest.xml")

    if "CredentialsActivity" not in manifest:
        fail("the credentials screen is not declared")
    elif 'android:exported="true"' in re.search(
        r"<activity[^>]*CredentialsActivity[^>]*>", manifest
    ).group(0):
        fail("the credentials screen is exported")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)

    root = sys.argv[1]
    if not os.path.isdir(os.path.join(root, SMALI)):
        sys.exit(f"{root} does not look like an apktool-decoded APK")

    check_hooks(root)
    check_suspend_guards(root)
    check_fallthrough(root)
    check_pro_unlock(root)
    check_hide_ads(root)
    check_resources(root)
    check_manifest(root)

    for failure in failures:
        print("FAIL  " + failure)

    if failures:
        sys.exit(f"\n{len(failures)} check(s) failed")

    print("every check passed")


if __name__ == "__main__":
    main()
