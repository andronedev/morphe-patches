#!/bin/sh
# Installs an APK on a slow emulator without killing the system.
#
#   install-apk.sh path/to/app.apk
#
# Takes about two minutes. Run it in the background.
#
# The streaming install adb prefers by default holds system_server long enough for
# the watchdog to kill it, which then fails every later command with "Can't find
# service: package" and looks like a problem with the APK. --no-streaming pushes the
# file first and installs from disk, which the device survives.
set -u

APK=${1:?usage: install-apk.sh path/to/app.apk}
export PATH=$PATH:${ANDROID_HOME:-/opt/android-sdk}/platform-tools

log() { echo "[install] $*"; }

[ -f "$APK" ] || { log "no such file: $APK"; exit 1; }

adb devices 2>/dev/null | grep -q "emulator.*device" \
    || { log "no emulator; boot one first"; exit 1; }

# Installing before the package service is up is what corrupts system_server, so
# confirm it answers rather than assuming the boot left it ready.
log "waiting for the package service"
i=0
until adb shell pm list packages >/dev/null 2>&1; do
    i=$((i + 1))
    [ $i -gt 40 ] && { log "package service never came up; the system probably died, reboot it"; exit 1; }
    sleep 15
done

log "installing $(basename "$APK")"
output=$(adb install --no-streaming -r -t "$APK" 2>&1)
echo "$output" | tail -3

# aapt is not on PATH by default; it lives in the newest build-tools.
aapt2=$(ls -d "${ANDROID_HOME:-/opt/android-sdk}"/build-tools/*/aapt2 2>/dev/null | sort | tail -1)
package=""
[ -n "$aapt2" ] && package=$("$aapt2" dump packagename "$APK" 2>/dev/null | tr -d '\r')

if [ -z "$package" ]; then
    log "installed, but could not read the package name to verify it (no aapt2 found)"
    exit 0
fi

path=$(adb shell pm path "$package" 2>/dev/null | tr -d '\r')
if [ -n "$path" ]; then
    log "verified: $path"
else
    log "install did not take. Common causes:"
    log "  'Can't find service: package'     the watchdog killed system_server, reboot"
    log "  NPE in allocateBytes              same, caught mid-recovery"
    log "  INSTALL_FAILED_NO_MATCHING_ABIS   the APK ships libs for another architecture"
    echo "$output" | head -8
    exit 1
fi
