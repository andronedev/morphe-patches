#!/bin/sh
# Boots an Android emulator that survives a container with no KVM.
#
#   boot-emulator.sh [avd-name] [--writable-system]
#
# Takes three to five minutes. Run it in the background.
#
# The flags are not arbitrary:
#   -no-accel      there is no /dev/kvm here, so QEMU interprets rather than virtualises
#   -gpu off       software rendering costs CPU this machine needs for the guest
#   -cores 2       TCG contends on its own locks; more cores is slower, not faster
#   -no-snapshot   a restored snapshot inherits whatever broke last time
#   -writable-system  needed to modify /system (installing a CA), and it cannot be
#                     added to a running emulator, so decide now
set -u

AVD=${1:-test}
WRITABLE=""
[ "${2:-}" = "--writable-system" ] && WRITABLE="-writable-system"

export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
export ANDROID_SDK_ROOT=$ANDROID_HOME
export ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-$HOME/.android/avd}
export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator

# No Google Play services. Their background load keeps system_server past Android's
# 60 second watchdog under TCG, and the watchdog then kills it, taking the package
# and activity services with it.
IMAGE=${IMAGE:-"system-images;android-28;default;x86_64"}

log() { echo "[boot] $*"; }

if [ ! -d "$ANDROID_HOME/emulator" ] || [ ! -d "$ANDROID_HOME/system-images" ]; then
    log "installing emulator and $IMAGE (several minutes)"
    yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "emulator" "$IMAGE" >/dev/null 2>&1 \
        || { log "sdkmanager failed"; exit 1; }
fi

if [ ! -d "$ANDROID_AVD_HOME/$AVD.avd" ]; then
    log "creating AVD $AVD"
    echo no | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" \
        create avd -n "$AVD" -k "$IMAGE" --force >/dev/null 2>&1 \
        || { log "could not create the AVD"; exit 1; }
fi

# setsid detaches from this shell, which otherwise takes the emulator with it when
# the tool call returns.
log "starting $AVD ${WRITABLE:-without a writable system}"
cd "$ANDROID_HOME/emulator" || exit 1
setsid nohup ./emulator -avd "$AVD" \
    -no-window -no-audio -no-boot-anim -no-snapshot -gpu off -no-accel \
    -memory 3072 -cores 2 $WRITABLE \
    > /tmp/emulator-$AVD.log 2>&1 < /dev/null &

# adb is the only reliable liveness check: pgrep -f matches this script's own
# command line, so it reports an emulator that is not there.
log "waiting for adb"
i=0
until adb devices 2>/dev/null | grep -q "emulator.*device"; do
    i=$((i + 1))
    [ $i -gt 60 ] && { log "no device after 10 minutes, see /tmp/emulator-$AVD.log"; exit 1; }
    sleep 10
done

log "waiting for boot_completed"
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 20
done

# boot_completed only means the boot animation would have stopped. The package
# manager arrives well after it, and installing into that gap is what corrupts
# system_server.
log "waiting for the package service to settle"
adb shell 'until pm list packages >/dev/null 2>&1; do sleep 5; done' >/dev/null 2>&1
sleep 90

log "ready: $(adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r') on $(adb shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
