# Morphe Patches

Patch definitions for Transit and AdMobile.

## Included patches

### AdMobile (`io.stark.admob`)

- **Custom AdMob Credentials**
  - Description: Sign in with your own OAuth client and refresh token, entered in the app
  - Source: `patches/src/main/kotlin/app/morphe/patches/admobile/auth/CustomCredentialsPatch.kt`
  - Extension: `extensions/admobile/`
- **Pro Unlock**
  - Description: Unlock all pro features in AdMobile
  - Source: `patches/src/main/kotlin/app/morphe/patches/admobile/misc/ProUnlockPatch.kt`
- **Hide Ads**
  - Description: Hide the native ads shown on the home, apps, app info and mediation screens
  - Source: `patches/src/main/kotlin/app/morphe/patches/admobile/ads/HideAdsPatch.kt`
- **Disable Ad Requests**
  - Description: Clear the AdMob ad unit ids so no ad is ever requested from the network
  - Source: `patches/src/main/kotlin/app/morphe/patches/admobile/ads/DisableAdRequestsPatch.kt`

### Transit (`com.thetransitapp.droid`)

- **Pro Features Unlock**
  - Description: Unlock all pro features in Transit
  - Source: `patches/src/main/kotlin/app/morphe/patches/transit/misc/TransitUnlockPatch.kt`
- **Custom Maps API Key**
  - Description: Replace Transit Google Maps API key with your own key for re-signed APKs
  - Source: `patches/src/main/kotlin/app/morphe/patches/transit/misc/TransitMapsApiKeyPatch.kt`

## AdMobile

Hide Ads, Disable Ad Requests and Pro Unlock need no setup. Pro Unlock also hides the ads on its own,
since the ad loader sits behind the same flag; the two ad patches are the narrower option. Pro Badge
comes with Pro Unlock and relabels the profile screen's premium button.

### Signing in on a re-signed build

A re-signed APK cannot use Google Sign-In: Google checks the calling package against the SHA-1 the
OAuth client was registered with and answers `DEVELOPER_ERROR` (10). microG does not help.

**Custom AdMob Credentials** replaces it with your own OAuth client. Everything past the
authorization code is plain HTTPS that does not care how the APK is signed. You need:

1. A Google Cloud project with the **AdMob API** and the **AdSense Management API** enabled. The
   second one only fills the payments card; without it the rest still works.
2. An OAuth consent screen (External, Testing) with your account as a test user.
3. An OAuth client of type **Desktop**.

Open AdMobile, tap **Sign in**, paste the client id and secret, save. The consent screen runs in the
browser; publisher id, currency and time zone are read back automatically. Nothing is compiled into
the APK, so one build works for anybody.

Limits: one account, no switcher. Refresh tokens from a Testing consent screen expire after 7 days.

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
there, that a hook injected into a suspend function guards the parameters Kotlin nulls out when it
resumes, that an unrecognised key still falls through to the app's own storage, and that the ads,
pro flag and resources came out as intended.

## Transit

On recent Transit versions, any re-signed APK may break in-app maps. Use **Custom Maps API Key**
with your own Google Maps Android key to restore them.

## Notes

- The upstream Morphe Gradle plugin is private and may require credentials to run generation tasks.
- `patches-list.json` and `patches-bundle.json` are maintained by hand.

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=andronedev/morphe-patches&type=date&legend=top-left)](https://www.star-history.com/#andronedev/morphe-patches&type=date&legend=top-left)
