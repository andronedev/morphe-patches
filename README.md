# Transit Patches

This repository now contains only Transit patch definitions.

## Included patches

- **Pro Features Unlock**
  - Description: Unlock all pro features in Transit
  - Target package: `com.thetransitapp.droid`
  - Source: `patches/src/main/kotlin/app/morphe/patches/transit/misc/TransitUnlockPatch.kt`
- **Custom Maps API Key**
  - Description: Replace Transit Google Maps API key with your own key for re-signed APKs
  - Target package: `com.thetransitapp.droid`
  - Source: `patches/src/main/kotlin/app/morphe/patches/transit/misc/TransitMapsApiKeyPatch.kt`

## ⚠️ Warning

- On recent Transit versions, any re-signed APK may break in-app maps.
- use `Custom Maps API Key` with a Google Maps Android key to restore maps functionality in re-signed APKs.

## Notes

- The upstream Morphe Gradle plugin is private and may require credentials to run generation tasks.
- `patches-list.json` and `patches-bundle.json` were regenerated manually to match the current Transit-only state.

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=andronedev/morphe-patches&type=date&legend=top-left)](https://www.star-history.com/#andronedev/morphe-patches&type=date&legend=top-left)
