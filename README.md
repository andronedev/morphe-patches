# Transit Patches

This repository now contains only Transit patch definitions.

## Included patch

- **Pro Features Unlock**
  - Description: Unlock all pro features in Transit
  - Target package: `com.thetransitapp.droid`
  - Source: `patches/src/main/kotlin/app/morphe/patches/transit/misc/TransitUnlockPatch.kt`

## Notes

- The upstream Morphe Gradle plugin is private and may require credentials to run generation tasks.
- `patches-list.json` and `patches-bundle.json` were regenerated manually to match the current Transit-only state.
