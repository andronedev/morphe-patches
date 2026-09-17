#!/bin/sh
# Teaches the emulator to trust the sandbox's TLS-intercepting proxy.
#
#   trust-proxy-ca.sh [ca-bundle.pem]
#
# Without this, any app doing HTTPS fails with:
#   SSLHandshakeException: Trust anchor for certification path not found
#
# That is the environment, not the app: egress goes through a proxy that terminates
# TLS with its own authority, which the container trusts and a fresh Android does not.
#
# The certificate has to go in the *system* store. Since API 24 apps ignore user
# certificates unless their network security config opts in, and a third-party APK
# will not have. That means the emulator must have been booted with
# -writable-system, which cannot be added afterwards.
#
# Only for reaching a proxy you already sit behind, on a throwaway emulator you
# control. It is not a way to intercept anyone else's traffic.
set -u

BUNDLE=${1:-/root/.ccr/ca-bundle.crt}
export PATH=$PATH:${ANDROID_HOME:-/opt/android-sdk}/platform-tools

log() { echo "[ca] $*"; }

[ -f "$BUNDLE" ] || { log "no CA bundle at $BUNDLE"; exit 1; }

adb devices 2>/dev/null | grep -q "emulator.*device" \
    || { log "no emulator; boot one with --writable-system first"; exit 1; }

adb root >/dev/null 2>&1
sleep 8
adb wait-for-device

if ! adb remount 2>&1 | grep -qi "succeeded"; then
    log "remount failed. The emulator must be booted with -writable-system;"
    log "it cannot be granted later, so reboot with that flag."
    exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cd "$work" || exit 1

# A bundle holds many certificates; split before hashing.
csplit -sz -f cert- -b '%03d.pem' "$BUNDLE" '/-----BEGIN CERTIFICATE-----/' '{*}' 2>/dev/null \
    || { log "could not split $BUNDLE"; exit 1; }

# Android looks certificates up by subject_hash_old — the pre-1.0 OpenSSL hash, not
# the modern one. Getting this wrong installs a file the system silently ignores.
installed=0
for pem in cert-*.pem; do
    hash=$(openssl x509 -inform PEM -subject_hash_old -in "$pem" -noout 2>/dev/null) || continue
    [ -n "$hash" ] || continue
    cp "$pem" "$hash.0"
    if adb push "$hash.0" /system/etc/security/cacerts/ >/dev/null 2>&1; then
        installed=$((installed + 1))
    fi
done

[ "$installed" -gt 0 ] || { log "nothing installed"; exit 1; }

adb shell "chmod 644 /system/etc/security/cacerts/*.0" >/dev/null 2>&1
log "installed $installed certificate(s); the store now holds $(adb shell 'ls /system/etc/security/cacerts/ | wc -l' 2>/dev/null | tr -d '\r')"
log "restart the app under test so it picks up the new trust store"
