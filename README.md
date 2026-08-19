# Morphe Patches

Patch definitions for Transit and AdMobile.

## Included patches

### AdMobile — `io.stark.admob`

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

### Transit — `com.thetransitapp.droid`

- **Pro Features Unlock**
  - Description: Unlock all pro features in Transit
  - Source: `patches/src/main/kotlin/app/morphe/patches/transit/misc/TransitUnlockPatch.kt`
- **Custom Maps API Key**
  - Description: Replace Transit Google Maps API key with your own key for re-signed APKs
  - Source: `patches/src/main/kotlin/app/morphe/patches/transit/misc/TransitMapsApiKeyPatch.kt`

## How the AdMobile patches work

AdMobile 2.4.8 monetises with **AdMob native ads only** — there is no banner, interstitial,
rewarded or app open ad anywhere in the APK. Four placements exist (home, apps, app info and
mediation) and they all share the same pipeline:

1. `oe.m` (the base fragment, obfuscated) observes the purchase state. When the user is not pro it
   calls the fragment's ad hook.
2. The fragment builds `AdLoader.Builder(context, getString(R.string.ad_*_native))` and requests a
   native ad.
3. The loaded ad is delivered to `io.stark.admob.ui.widget.ads.AdNativeView.setNativeAd(NativeAd)`,
   which binds it and calls `setVisibility(VISIBLE)`. The view is `GONE` until that point.

The two patches cut the pipeline at both ends, and each one is anchored on a name that R8 cannot
rename, so they survive app updates:

- **Hide Ads** returns early from `AdNativeView.setNativeAd`, after releasing the ad. The class name
  is kept because the view is referenced from layout XML, and `setNativeAd` is kept by the default
  Android ProGuard rule for `set*` members on `View` subclasses.
- **Disable Ad Requests** blanks the `ad_home_native`, `ad_apps_native` and `ad_app_info_native`
  string resources. `AdLoader.Builder` only null-checks the context, so an empty ad unit id makes
  the request fail instead of returning an ad. Resource names are never obfuscated.

Use both together: the first guarantees nothing is drawn, the second stops the network traffic.

## How the AdMobile pro unlock works

Pro is a single boolean field on the app's DataStore wrapper. It is written in exactly one place —
the `verifyAppPurchase` suspend body, which reads the purchase json and signature persisted after
the last Play purchase and checks them against the stored Play public key with an RSA verification.
Everything else only reads it:

- The billing client mirrors it into a `MutableLiveData` at startup and again after every purchase
  list it processes, including an empty one.
- The base fragment exposes it to each screen, which drives the real report charts instead of the
  demo data, the full ad unit and ad source breakdowns instead of a single teaser row, more than one
  AdMob account, the premium accents in the profile, and the branch that skips loading an ad.

**Pro Unlock** forces both writes to that flag to `true`: the one after a failed verification and
the one taken when no purchase is persisted at all. The second is what makes it work on a device
that never bought anything. The method is located by the log prefix it emits, a string constant R8
does not touch.

The verification is entirely local, so nothing else has to be defeated. Note that Pro Unlock also
suppresses the ads on its own, since the ad loader sits behind the same flag — the two ad patches
stay useful as a standalone, narrower option.

## Signing in on a re-signed AdMobile

A re-signed APK cannot use Google Sign-In: Google validates the calling package against the SHA-1
the OAuth Android client was registered with, and answers `DEVELOPER_ERROR` (10). That is not
specific to these patches — it applies to any re-signed build, and microG does not help, because it
reports the app's real (new) certificate to the same endpoint. Installing in **mount mode** on a
rooted device avoids it entirely by keeping the original signature.

**Custom AdMob Credentials** removes the need for either. Google Sign-In is only used to obtain the
initial authorization code; everything after it is plain HTTPS that does not care how the APK is
signed. The app exchanges and refreshes tokens itself against `https://oauth2.googleapis.com/token`,
sending `client_id` (read once when its store is built), `client_secret` and `refresh_token` (both
decrypted out of its DataStore), and reads the reports straight from `https://admob.googleapis.com/`.
Supply those three values yourself and the GMS step disappears.

Note that the client secret never ships in the APK: the app downloads it from the developer's
Firestore *after* a successful Firebase sign-in, along with the Play public key. That is why
injecting only a refresh token cannot work, and why the patch takes your own OAuth client instead.

The values are entered in the app. While nothing is stored, the launch screen's **Sign in** button
opens the credentials form instead of the Google flow, so setup is one tap from the screen a fresh
install already lands on — there is no second launcher icon and the app's navigation is untouched.
Saving restarts the app, which is all it takes for the account to be picked up.

They live in the app's private preferences, so nothing is compiled into the APK: a patched build
carries no secret and one build works for anybody. Until they are filled in, every hook falls
through and the app behaves exactly as it did before patching.

### What you need

1. A Google Cloud project with the **AdMob API** enabled, and the **AdSense Management API**
   alongside it — the reports come from the first, the payments card on the profile screen from the
   second. Leaving the second one off is not fatal: everything else works, and the profile screen
   shows Google's own "has not been used in project … or it is disabled" error.
2. An OAuth consent screen (External, Testing) with your own account added as a test user.
3. An OAuth client of type **Desktop**, which gives you a client id and a client secret.
4. A refresh token for that client on the `https://www.googleapis.com/auth/admob.readonly` and
   `https://www.googleapis.com/auth/adsense.readonly` scopes, obtained by running the consent flow
   once with `access_type=offline`. The form does this for you; the scopes matter only if you get
   the token some other way.
5. Your AdMob publisher id (`pub-…`).

Open AdMobile, tap **Sign in**, paste them into the form, and save.

### Without the Morphe toolchain

`tools/apply-admobile-screen.py` performs the same edits on an apktool-decoded APK. The extension
has to be compiled and injected by hand; the script's docstring gives the full sequence.

`tools/apply-admobile-credentials.py` is the older variant that bakes fixed values into the smali
instead, for a personal build with no configuration screen.

### Limits

- The patch fabricates the single selected account. The account switcher stays empty, and adding a
  second account still needs the Google Sign-In flow.
- The Firebase session is never established, so anything backed by the developer's Firestore is
  unavailable. `Pro Unlock` already covers the part of that which is gated on purchases.
- The extension compiles, the edits assemble, and the resulting APK builds and carries the
  extension classes, but the OAuth flow has not been exercised against a live account.

## ⚠️ Warning

- On recent Transit versions, any re-signed APK may break in-app maps.
- use `Custom Maps API Key` with a Google Maps Android key to restore maps functionality in re-signed APKs.

## Notes

- The upstream Morphe Gradle plugin is private and may require credentials to run generation tasks.
- `patches-list.json` and `patches-bundle.json` were regenerated manually to match the current state.

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=andronedev/morphe-patches&type=date&legend=top-left)](https://www.star-history.com/#andronedev/morphe-patches&type=date&legend=top-left)
