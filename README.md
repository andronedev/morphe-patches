# Transit Patches

This repository now contains only Transit patch definitions.

## Included patch

- **Pro Features Unlock**
  - Description: Unlock all pro features in Transit
  - Target package: `com.thetransitapp.droid`
  - Source: `patches/src/main/kotlin/app/morphe/patches/transit/misc/TransitUnlockPatch.kt`

## Warning

- On recent Transit versions, the in-app map may be broken after patching.
- A fix/workaround is currently being researched.

## Notes

- The upstream Morphe Gradle plugin is private and may require credentials to run generation tasks.
- `patches-list.json` and `patches-bundle.json` were regenerated manually to match the current Transit-only state.

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=andronedev/morphe-patches&type=date&legend=top-left)](https://www.star-history.com/#andronedev/morphe-patches&type=date&legend=top-left)