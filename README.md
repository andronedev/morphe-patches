# Morphe Patches

Patch definitions for Transit and AdMobile.

## Included patches

### AdMobile (`io.stark.admob`)

- **Serverless Sign-In**
  - Description: Sign in with your own Google OAuth client, so the app talks to Google and nobody else
  - Source: `patches/src/main/kotlin/app/morphe/patches/admobile/auth/ServerlessSignInPatch.kt`
  - Extension: `extensions/admobile/`
- **Pro Unlock**
  - Description: Unlock every pro feature, in every process that checks for one
  - Source: `patches/src/main/kotlin/app/morphe/patches/admobile/misc/ProUnlockPatch.kt`

### Transit (`com.thetransitapp.droid`)

- **Pro Features Unlock**
  - Description: Unlock all pro features in Transit
  - Source: `patches/src/main/kotlin/app/morphe/patches/transit/misc/TransitUnlockPatch.kt`
- **Custom Maps API Key**
  - Description: Replace Transit Google Maps API key with your own key for re-signed APKs
  - Source: `patches/src/main/kotlin/app/morphe/patches/transit/misc/TransitMapsApiKeyPatch.kt`

## AdMobile

Two patches. Both are worth having; neither needs the other.

### Pro Unlock

Pro is one boolean field, written only by a local signature check against the last Play purchase.
Everything else reads it: the real report charts instead of demo data, the ad unit and ad source
breakdowns, more than one account, the premium accents, the home screen widgets, and the branch
that decides whether to load an ad.

The patch answers **every read** of that field with true, rather than forcing the one write. That
distinction matters: the write only happens once the billing client processes a purchase list, so
anything that starts the process on its own — a widget worker, most visibly — used to read the flag
before it was set and behave as if you had never paid. It also seeds the LiveData the screens
observe, which is created empty, so a cold start looks paid from the first frame instead of after
billing connects.

Because the ad loader sits behind that same flag, no ad is ever requested or drawn. There is
nothing else to disable, and nothing cosmetic is added: the app simply does what it does for
someone who bought the subscription.

### Serverless Sign-In

A re-signed APK cannot use Google Sign-In: Google checks the calling package against the SHA-1 the
OAuth client was registered with and answers `DEVELOPER_ERROR` (10). microG does not help.

The patch replaces it with your own OAuth client, which also removes the developer from the
picture entirely. The app used to fetch its client secret from the developer's Firestore after a
Firebase sign-in; now nothing leaves your device except calls to Google's own APIs, signed with
credentials only you hold. No backend, no Firebase session, no third party holding your tokens or
seeing what you earn. Nothing is compiled into the APK either, so one build works for anybody.

It is not offline: your reports live at `admob.googleapis.com` and that is where they come from.

You need:

1. A Google Cloud project with the **AdMob API** and the **AdSense Management API** enabled. The
   second one only fills the payments card; without it the rest still works.
2. An OAuth consent screen (External, Testing) with your account as a test user.
3. An OAuth client of type **Desktop**.

Open AdMobile, tap **Sign in**, paste the client id and secret, save. The consent screen runs in the
browser; publisher id, currency and time zone are read back automatically.

Limits: one account, no switcher. Refresh tokens from a Testing consent screen expire after 7 days,
which publishing the consent screen removes.

### Without the Morphe toolchain

`tools/apply-admobile.py` makes the same edits on an apktool-decoded APK; its docstring gives the
full sequence, including building and injecting the extension dex.

Two packaging traps, both of which install as "app not compatible with this device". Decode a
**universal** APK, not one split of the set. And if the merged APK carries only `lib/armeabi-v7a`,
drop `lib/` before signing: it makes the build look 32-bit only, which a 64-bit-only phone refuses.
AdMobile's one native library is an optional DataStore component the app runs fine without.

`tools/check-admobile-api.py` replays the API calls from a desktop, to tell a build problem from a
Google project problem.

`tools/extension-tests/run.sh` runs the extension's tests on the JVM against hand-written Android
stubs. It needs javac and nothing else.

`tools/verify-admobile.py` checks a patched, decoded tree before it is built: that every hook is
there, that no read of the pro flag survives, that a hook injected into a suspend function guards
the parameters Kotlin nulls out when it resumes, and that an unrecognised key still falls through
to the app's own storage.

### Running a build

`.claude/skills/android-emulator-sandbox/` boots an Android emulator, installs an APK and drives it
from a container with no KVM, which is where a patched build can be tried before it reaches a phone.
Its scripts are usable on their own; the SKILL.md is the reasoning behind them.

## Transit

On recent Transit versions, any re-signed APK may break in-app maps. Use **Custom Maps API Key**
with your own Google Maps Android key to restore them.

## Notes

- The upstream Morphe Gradle plugin is private and may require credentials to run generation tasks.
- `patches-list.json` and `patches-bundle.json` are maintained by hand.

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=andronedev/morphe-patches&type=date&legend=top-left)](https://www.star-history.com/#andronedev/morphe-patches&type=date&legend=top-left)
