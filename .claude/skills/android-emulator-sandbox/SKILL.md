---
name: android-emulator-sandbox
description: Run an Android emulator and drive a real APK inside a sandboxed container that has no KVM — booting it, installing the app, granting TLS trust for an intercepting proxy, and reading app state when the UI tooling is unusable. Use this whenever running the app would settle a question that reasoning cannot: testing a patched or re-signed APK, reproducing a crash or a user-reported bug on a real Android runtime, verifying an in-app flow end to end, or when someone says "run the APK", "test it on a device", or "stop making me test this for you". Also use it to rescue an emulator that is already misbehaving — system_server dying, installs failing with "Can't find service: package", uiautomator returning a null root node, or the app hitting "Trust anchor for certification path not found".
---

# Running an Android app in a container without KVM

Agent sandboxes are virtual machines that do not expose nested virtualisation, so
`/dev/kvm` is absent and the Android emulator falls back to QEMU's TCG interpreter.
It does work. Everything simply takes minutes instead of seconds, and that single
fact causes every failure in this skill: Android's own watchdogs are written for
hardware speeds, so they start firing at parts of the system that are merely slow.

Recognising that pattern is most of the battle. When something dies, ask whether it
died because it was wrong or because it was late. It is almost always late.

## Start here

Three scripts do the fiddly parts. Read them before running them — they are short,
and the comments explain the flags:

```sh
skill_dir=.claude/skills/android-emulator-sandbox

# Boot. Add --writable-system if you will need the system trust store (see below).
$skill_dir/scripts/boot-emulator.sh test --writable-system

# Install, once the boot has actually settled.
$skill_dir/scripts/install-apk.sh path/to/app.apk

# Only if HTTPS from inside the app fails on certificate validation.
$skill_dir/scripts/trust-proxy-ca.sh /root/.ccr/ca-bundle.crt
```

Each takes several minutes. Run them with `run_in_background: true` and let the
completion notification wake you, or poll with an `until` loop — chained `sleep`
calls get blocked, and a foreground call will hit its timeout before the emulator
finishes booting.

## Decisions that are hard to reverse

**Pick a system image without Google Play services.** This matters more than any
other choice. `google_apis` images run the whole Play services stack, and under TCG
that background load is enough to keep `system_server` busy past Android's 60-second
watchdog timeout. The watchdog then kills it, taking `package`, `activity` and the
rest with it, and every subsequent command fails in a way that looks like your fault.
`system-images;android-28;default;x86_64` has a fraction of the services and stays up.

If the app genuinely needs Play services you are in for a fight, and it is worth
asking first whether the code path you care about needs them at all — an app that
talks to a REST API over HTTPS usually does not.

**Decide about `-writable-system` before booting.** It cannot be added to a running
emulator. If there is any chance you will need to modify `/system` (installing a CA
is the common reason), boot with it from the start rather than rebooting later.

**`default` images are userdebug**, so `adb root` works. You need it for
`/data/data`, for `/system`, and for starting activities that are not exported.

## Waiting properly

`sys.boot_completed` becoming `1` does not mean the device is ready. It means the
boot animation would have stopped. The package manager comes up well after that, and
installing into the gap is what corrupts `system_server`. Wait for the property, then
wait again for the service itself:

```sh
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 20; done
adb shell 'until pm list packages >/dev/null 2>&1; do sleep 5; done'
sleep 90   # and still give it room
```

`install-apk.sh` does this for you. It is spelled out here because the same shape
applies to anything else you drive after a boot.

## A trap that will cost you two cycles

`pgrep -f qemu-system` matches **your own shell command line**, because the string
you are searching for is in the command you are running. So it reports the emulator
as alive when you have just killed it, and you conclude the kill failed and go round
again. The same applies to `pkill -f` — it will happily kill the shell running it.

Ask adb instead. It has no opinion about your command line:

```sh
adb devices | grep -q emulator && echo up || echo down
```

## When the app cannot reach HTTPS

Sandboxes route egress through a proxy that terminates TLS with its own certificate
authority. Your container trusts it; a freshly booted Android does not, so the app
fails with:

```
SSLHandshakeException: Trust anchor for certification path not found
```

This is the environment, not the app. Any app would fail the same way, and it is
worth saying so plainly rather than debugging the app's networking.

Installing a user certificate does not help: since API 24, apps ignore user CAs
unless their network security config opts in, and a third-party APK will not have.
The certificate has to go in the system store, which is what
`trust-proxy-ca.sh` does. Two details it handles that are easy to get wrong: Android
looks the file up by **`subject_hash_old`**, not the modern OpenSSL hash, and a
bundle usually holds many certificates that need splitting first.

## Driving the app

Under TCG, SystemUI spends much of its life in ANR, and `uiautomator dump` fails
outright whenever it does:

```
ERROR: null root node returned by UiTestAutomationBridge
```

The dialog on screen is also not necessarily what has focus. Trust this instead:

```sh
adb shell dumpsys window | grep -m1 mCurrentFocus
```

When a dump does succeed, harvest it — record the bounds of every control you might
need, then tap those coordinates blind for the rest of the session. Taps work even
while dumps do not.

Two things make the screen far more workable:

```sh
adb shell wm size 640x1280      # more room than the default tiny AVD screen
adb shell wm density 100        # smaller UI, so more of the form fits without scrolling
```

Scrolling is worth avoiding: `input swipe` frequently fails to move a ScrollView on
a slow device, and you cannot tell whether the swipe missed or the dump was stale.
Shrinking the density until everything fits sidesteps the question.

To open a screen directly, including one that is not exported (after `adb root`):

```sh
adb shell am start -n com.example/.SomeActivity
```

## Reading state, which is what actually convinces

The screenshot is the least reliable artefact you will produce here. A slow emulator
renders half-drawn frames, and a screenshot cannot show you what was written to disk.
When you need to know what the app *did*, read its own storage:

```sh
adb shell "cat /data/data/<pkg>/shared_prefs/<name>.xml"
adb shell "sqlite3 /data/data/<pkg>/databases/<db> 'select * from <table>;'"
adb logcat -d | grep -E "<your log tag>|FATAL EXCEPTION"
```

These three answer nearly every question worth asking: what the app stored, what it
persisted across a restart, and whether it crashed. They are also quotable — a
preferences file showing the value you expected is evidence, where "the screen looked
right" is not.

This matters especially for anything the app writes just before restarting itself.
`SharedPreferences.apply()` only queues the write, so a process that exits
immediately afterwards loses it. Reading the file back after the restart is how you
tell a real persistence bug from an imagined one.

## Seeding a specific state

Clicking your way to the state you want to test is slow and often impossible when the
UI is half-responsive. Write the state directly instead, then launch:

```sh
adb root
adb shell am force-stop <pkg>
adb push prefs.xml /data/local/tmp/prefs.xml
adb shell "cp /data/local/tmp/prefs.xml /data/data/<pkg>/shared_prefs/<name>.xml"
adb shell "chown \$(stat -c %u:%g /data/data/<pkg>) /data/data/<pkg>/shared_prefs/<name>.xml"
adb shell "chmod 660 /data/data/<pkg>/shared_prefs/<name>.xml"
```

This is how you reproduce a user's exact situation — a half-finished sign in, an
expired token, a missing field — instead of approximating it.

## Symptom table

| What you see | What it means | What to do |
|---|---|---|
| `cmd: Can't find service: package` | The watchdog killed `system_server` | Reboot, wait longer, use a lighter image |
| NPE in `StorageManagerService.allocateBytes` | Same, caught mid-recovery | Same |
| `adb install` succeeds then everything dies | Streaming install was too heavy | `adb install --no-streaming` |
| `DeadSystemException` across many processes | `system_server` died under you | Reboot the device, not just the app |
| `ERROR: null root node` | SystemUI is in ANR | Use `dumpsys window`, tap known coordinates |
| `Trust anchor ... not found` | Proxy TLS interception | `trust-proxy-ca.sh`, needs `-writable-system` |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | APK has libs for another architecture | Use an x86_64 image, or strip `lib/` if the app runs without it |
| Emulator "still running" after you killed it | `pgrep -f` matched your own shell | Check `adb devices` |

## Budget

Roughly, on four cores: three to five minutes to boot, two to install, one per app
launch. A full cycle of boot, install, seed, drive and read is fifteen to twenty
minutes, and a mistake that forces a reboot costs the whole boot again. That is worth
saying out loud to whoever is waiting, and it is worth batching your steps into one
background script rather than discovering a missing `adb root` at minute twelve.
