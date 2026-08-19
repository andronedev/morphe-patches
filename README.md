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

AdMobile 2.4.8 shows AdMob native ads only. There is no banner, interstitial, rewarded or app open
ad in the APK. Four placements exist (home, apps, app info, mediation) and they share one pipeline:
the base fragment asks for an ad when the user is not pro, the fragment builds
`AdLoader.Builder(context, getString(R.string.ad_*_native))`, and the loaded ad reaches
`AdNativeView.setNativeAd(NativeAd)`, which binds it and makes the view visible.

**Hide Ads** returns early from `setNativeAd`, after releasing the ad. **Disable Ad Requests**
blanks the `ad_home_native`, `ad_apps_native` and `ad_app_info_native` string resources; an empty ad
unit id makes the request fail instead of returning an ad. Use both: the first guarantees nothing is
drawn, the second stops the network traffic.

Both anchors survive obfuscation. `AdNativeView` is referenced from layout XML so R8 keeps the class
name, `setNativeAd` is kept by the default ProGuard rule for `set*` on `View` subclasses, and
resource names are never obfuscated.

### Pro Unlock

Pro is one boolean field on the app's store, written in exactly one place: the `verifyAppPurchase`
body, which checks the persisted purchase json and signature against the stored Play public key.
Everything else only reads it, and the check is entirely local, so nothing else has to be defeated.

**Pro Unlock** forces both writes to that field to `true`: the one after a failed verification, and
the one taken when no purchase is persisted at all. The second is what makes it work on a device
that never bought anything. The method is found by the log prefix it emits, a string R8 leaves
alone. **Pro Badge**, which comes with it, relabels the profile screen's premium button so the state
is visible.

Pro Unlock also suppresses the ads on its own, since the ad loader sits behind the same flag. The
two ad patches stay useful as a narrower option.

### Signing in on a re-signed build

A re-signed APK cannot use Google Sign-In. Google validates the calling package against the SHA-1
the OAuth Android client was registered with and answers `DEVELOPER_ERROR` (10). This is not
specific to these patches, and microG does not help: it reports the app's real certificate to the
same endpoint. Installing in mount mode on a rooted device avoids it by keeping the original
signature.

**Custom AdMob Credentials** removes the need for either. Google Sign-In only obtains the initial
authorization code; everything after it is plain HTTPS that does not care how the APK is signed. The
app exchanges and refreshes tokens itself against `oauth2.googleapis.com/token` and reads the
reports from `admob.googleapis.com`. Supply your own OAuth client and the GMS step disappears.

The client secret never ships in the APK: the app downloads it from the developer's Firestore after
a successful Firebase sign-in. That is why injecting only a refresh token cannot work, and why the
patch takes your own OAuth client instead.

Credentials are entered in the app. The launch screen's **Sign in** button opens the form instead of
the Google flow, so setup is one tap from the screen a fresh install lands on. There is no second
launcher icon and the app's navigation is untouched. Saving restarts the app. The values live in the
app's private preferences, so a patched build carries no secret and one build works for anybody;
until they are filled in, every hook falls through and the app behaves as it did before patching.

#### What you need

1. A Google Cloud project with the **AdMob API** enabled, and the **AdSense Management API**
   alongside it. The reports come from the first, the payments card on the profile screen from the
   second. Leaving the second one off is not fatal: everything else works, and the profile screen
   shows Google's own "has not been used in project ... or it is disabled" error.
2. An OAuth consent screen (External, Testing) with your own account added as a test user.
3. An OAuth client of type **Desktop**, which gives you a client id and a client secret.

Open AdMobile, tap **Sign in**, paste the two values into the form and save. The consent screen runs
in the browser and the publisher id, currency and time zone are read back automatically.

#### Without the Morphe toolchain

`tools/apply-admobile-screen.py` performs the same edits on an apktool-decoded APK. The extension
has to be compiled and injected by hand; the script's docstring gives the full sequence. Its anchors
are the obfuscated names of AdMobile 2.4.8, so it needs updating for later versions, whereas the
patches find theirs through fingerprints.

`tools/check-admobile-api.py` replays the same API calls from a desktop, which tells you whether a
problem is in the build or in the Google project.

#### Limits

- The patch fabricates the single selected account. The account switcher stays empty, and there is
  no way to add a second account.
- The Firebase session is never established, so anything backed by the developer's Firestore is
  unavailable. Pro Unlock already covers the part of that which is gated on purchases.
- Refresh tokens issued by a consent screen still in Testing expire after seven days.

## Transit

On recent Transit versions, any re-signed APK may break in-app maps. Use **Custom Maps API Key**
with your own Google Maps Android key to restore them.

## Notes

- The upstream Morphe Gradle plugin is private and may require credentials to run generation tasks.
- `patches-list.json` and `patches-bundle.json` are maintained by hand to match the current state.

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=andronedev/morphe-patches&type=date&legend=top-left)](https://www.star-history.com/#andronedev/morphe-patches&type=date&legend=top-left)
