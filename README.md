# Morphe Patches

Patch definitions for Transit, plus the in-progress **Morphe LBC** mod framework for leboncoin.

## Work in progress: Morphe LBC (`fr.leboncoin`)

An Aliucord-style mod framework: the APK is patched once with a minimal loader, and features
(no-ads, better filters, auto-repost) ship as runtime plugins that can be updated without
repatching.

- Design, roadmap and known risks: [`docs/leboncoin/ARCHITECTURE.md`](docs/leboncoin/ARCHITECTURE.md)
- Runtime (loaded at execution, separate Gradle build): `runtime/leboncoin/`
- Injector patches: `patches/src/main/kotlin/app/morphe/patches/leboncoin/`
- APK reconnaissance tool: `tools/lbc-recon.py` (`python3 tools/lbc-recon.py app.apk -o recon/`)

These patches are marked `use = false` and are **not yet validated on a device** — the fingerprints
still need to be resolved against a real APK.

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
