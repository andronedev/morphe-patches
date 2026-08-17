# Morphe Patches

Patch definitions for Transit and AdMobile.

## Included patches

### AdMobile — `io.stark.admob`

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
Neither touches the purchase state, so pro features stay gated as they are in the stock app.

## ⚠️ Warning

- On recent Transit versions, any re-signed APK may break in-app maps.
- use `Custom Maps API Key` with a Google Maps Android key to restore maps functionality in re-signed APKs.

## Notes

- The upstream Morphe Gradle plugin is private and may require credentials to run generation tasks.
- `patches-list.json` and `patches-bundle.json` were regenerated manually to match the current state.

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=andronedev/morphe-patches&type=date&legend=top-left)](https://www.star-history.com/#andronedev/morphe-patches&type=date&legend=top-left)
