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
PRO_FLAG = "Lxf/i;->g:Z"

SMALI = "smali_classes3"

# file -> methods that must call into the extension.
HOOKS = {
    "xf/i.smali": ["forDataStoreKey", "forLegacyKey", "observeWrite", "clientIdOrOriginal"],
    "xf/t.smali": ["forDataStoreKey"],
    "af/m.smali": ["signOut"],
    "se/j.smali": ["isConfigured"],
    "io/stark/admob/App.smali": ["init"],
}

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
    """No read of the pro flag may survive, in any class.

    A single one left behind is a gate that answers no, and the process that hits it decides the
    user never paid — which is how the home screen widgets came back empty while the app itself
    looked fine.
    """
    remaining = []

    for directory, _, names in os.walk(os.path.join(root, SMALI)):
        for name in names:
            if not name.endswith(".smali"):
                continue

            path = os.path.join(directory, name)
            with open(path, encoding="utf-8", errors="replace") as handle:
                source = handle.read()

            for match in re.finditer(rf"iget-boolean v\d+, v\d+, {re.escape(PRO_FLAG)}", source):
                remaining.append(os.path.relpath(path, root))

    for path in sorted(set(remaining)):
        fail(f"{path}: still reads the pro flag instead of being answered true")

    # The screens observe a LiveData, not the field, and it starts empty.
    owner = read(root, os.path.join(SMALI, "me/h.smali"))
    seeded = re.search(
        r"iput-object p\d+, p\d+, Lme/h;->G:Landroidx/lifecycle/MutableLiveData;\s*"
        r"sget-object \S+ Ljava/lang/Boolean;->TRUE",
        owner,
    )
    if not seeded:
        fail("the pro LiveData is not seeded true, so a cold start still looks unpaid")


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
    check_manifest(root)

    for failure in failures:
        print("FAIL  " + failure)

    if failures:
        sys.exit(f"\n{len(failures)} check(s) failed")

    print("every check passed")


if __name__ == "__main__":
    main()
