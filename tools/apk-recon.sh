#!/usr/bin/env bash
#
# Static recon of an Android package, to gather everything a Morphe patch needs
# before a single fingerprint is written.
#
# Usage: tools/apk-recon.sh <file.apk|file.xapk|file.apks|file.apkm>
#
# Only needs unzip and grep. Reads the dex string pools directly, so it works
# without apktool, baksmali or jadx.

set -euo pipefail

INPUT="${1:-}"
if [ -z "$INPUT" ] || [ ! -f "$INPUT" ]; then
    echo "usage: $0 <file.apk|file.xapk|file.apks|file.apkm>" >&2
    exit 2
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

section() { printf '\n== %s ==\n' "$1"; }

# A bundle holds the base APK plus config splits; a plain APK is used as is.
case "$INPUT" in
    *.xapk | *.apks | *.apkm)
        unzip -qo "$INPUT" -d "$WORK/bundle"
        section "Bundle"
        BASE=""
        BASE_SIZE=0
        while IFS= read -r apk; do
            size="$(wc -c <"$apk" | tr -d ' ')"
            printf '  %s (%s bytes)\n' "$(basename "$apk")" "$size"
            # The base APK is the one carrying the dex; it is always the largest.
            if [ "$size" -gt "$BASE_SIZE" ]; then
                BASE_SIZE="$size"
                BASE="$apk"
            fi
        done < <(find "$WORK/bundle" -name '*.apk' | sort)
        ;;
    *)
        BASE="$INPUT"
        ;;
esac

if [ -z "${BASE:-}" ]; then
    echo "no APK found inside $INPUT" >&2
    exit 1
fi

unzip -qo "$BASE" -d "$WORK/apk"
APK="$WORK/apk"

DEX=("$APK"/classes*.dex)
if [ ! -e "${DEX[0]}" ]; then
    echo "no dex file in $(basename "$BASE")" >&2
    exit 1
fi

# Search the dex string pools. Type descriptors and string literals both live there.
dexgrep() { grep -ahoE "$1" "${DEX[@]}" 2>/dev/null | sort -u || true; }
dexhas() { grep -aqF "$1" "${DEX[@]}" 2>/dev/null; }

section "Build"
echo "  base apk: $(basename "$BASE")"
echo "  dex files: ${#DEX[@]}"
find "$APK/lib" -name '*.so' 2>/dev/null | sort | while IFS= read -r so; do
    printf '  %s (%s bytes)\n' "${so#"$APK/"}" "$(wc -c <"$so" | tr -d ' ')"
done

section "Runtime"
# Which runtime the UI is written in decides whether bytecode patching is useful at all.
if find "$APK" -name 'libflutter.so' 2>/dev/null | grep -q .; then
    echo "  Flutter. UI and business logic are compiled into libapp.so, not into dex."
    echo "  Bytecode patches only reach the plugin layer (ads SDK, billing SDK)."
elif find "$APK" \( -name 'libhermes.so' -o -name 'index.android.bundle' \) 2>/dev/null | grep -q .; then
    echo "  React Native. Logic lives in the JS bundle, not in dex."
    echo "  Patch the bundle as a resource, not as bytecode."
else
    echo "  No Flutter or React Native marker. Treat as a native Kotlin/Java app."
fi

section "Google Mobile Ads entry points"
# These are the public API classes that a Hide ads patch neutralizes.
FOUND_ADS=0
for cls in \
    'Lcom/google/android/gms/ads/MobileAds;' \
    'Lcom/google/android/gms/ads/BaseAdView;' \
    'Lcom/google/android/gms/ads/AdView;' \
    'Lcom/google/android/gms/ads/AdLoader;' \
    'Lcom/google/android/gms/ads/admanager/AdManagerAdView;' \
    'Lcom/google/android/gms/ads/interstitial/InterstitialAd;' \
    'Lcom/google/android/gms/ads/appopen/AppOpenAd;' \
    'Lcom/google/android/gms/ads/rewarded/RewardedAd;' \
    'Lcom/google/android/gms/ads/rewardedinterstitial/RewardedInterstitialAd;' \
    'Lcom/google/android/gms/ads/nativead/NativeAd;'; do
    if dexhas "$cls"; then
        echo "  present  $cls"
        FOUND_ADS=1
    else
        echo "  absent   $cls"
    fi
done
if [ "$FOUND_ADS" -eq 0 ]; then
    echo "  no unobfuscated GMA public API: either R8 renamed it, or another network is used"
fi

section "Other ad networks"
NETWORKS=0
for cls in \
    'Lcom/applovin/' \
    'Lcom/unity3d/ads/' \
    'Lcom/ironsource/' \
    'Lcom/facebook/ads/' \
    'Lcom/vungle/' \
    'Lcom/mbridge/'; do
    if dexhas "$cls"; then
        echo "  present  $cls"
        NETWORKS=1
    fi
done
if [ "$NETWORKS" -eq 0 ]; then
    echo "  none found"
fi

section "AdMob identifiers"
echo "  app id (manifest APPLICATION_ID):"
grep -ahoE 'ca-app-pub-[0-9]+~[0-9]+' "$APK/AndroidManifest.xml" "${DEX[@]}" 2>/dev/null \
    | sort -u | sed 's/^/    /' || true
echo "  ad unit ids (usable verbatim as fingerprint strings):"
dexgrep 'ca-app-pub-[0-9]+/[0-9]+' | sed 's/^/    /'

section "Billing and paywall"
BILLING=0
for cls in \
    'Lcom/android/billingclient/api/' \
    'Lcom/revenuecat/purchases/' \
    'Lcom/qonversion/android/sdk/' \
    'Lcom/adapty/' \
    'Lcom/superwall/'; do
    if dexhas "$cls"; then
        echo "  present  $cls"
        BILLING=1
    fi
done
if [ "$BILLING" -eq 0 ]; then
    echo "  no billing SDK found"
fi
echo "  entitlement and product strings:"
dexgrep '[A-Za-z_]*([Pp]remium|[Ss]ubscription|[Ee]ntitlement|unlock_[a-z_]+|is_?[Pp]ro)[A-Za-z_]*' \
    | head -60 | sed 's/^/    /'

section "Manifest metadata"
# The binary manifest still carries its strings in a readable pool.
grep -ahoE '[ -~]{6,}' "$APK/AndroidManifest.xml" 2>/dev/null \
    | grep -iE 'ads|admob|billing|applovin|unity|gms\.version|APPLICATION_ID' \
    | sort -u | sed 's/^/  /' || true

section "Next step"
cat <<'EOF'
  - Ad unit id strings above are the strongest fingerprint anchors. A Morphe
    Fingerprint(strings = listOf("ca-app-pub-.../...")) matches the exact app method
    that builds the ad request, and survives obfuscation and version bumps.
  - If the GMA public API classes are present, admobile/ads/HideAdsPatch.kt already
    covers them and needs no app specific fingerprint.
  - Entitlement strings above are the anchors for a Pro unlock patch, the same way
    "activate_royale_subscription" anchors the Transit patch.
EOF
