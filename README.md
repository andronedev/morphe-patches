# Morphe Patches

## Included patches

### AdMobile (`io.stark.admob`)

- **Hide ads**
  - Description: Stops the app from requesting Google Mobile Ads and collapses the banner containers
  - Source: `patches/src/main/kotlin/app/morphe/patches/admobile/ads/HideAdsPatch.kt`
  - Status: not yet verified against the APK, see [docs/ADS_PATCHING.md](docs/ADS_PATCHING.md)

### Transit (`com.thetransitapp.droid`)

- **Pro Features Unlock**
  - Description: Unlock all pro features in Transit
  - Source: `patches/src/main/kotlin/app/morphe/patches/transit/misc/TransitUnlockPatch.kt`
- **Custom Maps API Key**
  - Description: Replace Transit Google Maps API key with your own key for re-signed APKs
  - Source: `patches/src/main/kotlin/app/morphe/patches/transit/misc/TransitMapsApiKeyPatch.kt`

## Tooling

- `tools/apk-recon.sh <file.apk|xapk|apks|apkm>` — static recon of a target package:
  runtime (Flutter / React Native / native), ad SDK entry points, AdMob unit IDs,
  billing SDK and entitlement strings. Needs only `unzip` and `grep`.
- [docs/ADS_PATCHING.md](docs/ADS_PATCHING.md) — how the ad removal patch works and
  how to anchor a Pro unlock patch on the strings the recon script surfaces.

## ⚠️ Warning

- On recent Transit versions, any re-signed APK may break in-app maps.
- use `Custom Maps API Key` with a Google Maps Android key to restore maps functionality in re-signed APKs.

## Notes

- The upstream Morphe Gradle plugin is private and may require credentials to run generation tasks, so
  the AdMobile patch has not been compiled yet.
- `patches-list.json` and `patches-bundle.json` still describe the v2.3.0 Transit-only release; they are
  regenerated at release time.

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=andronedev/morphe-patches&type=date&legend=top-left)](https://www.star-history.com/#andronedev/morphe-patches&type=date&legend=top-left)
