# Patcher les pubs AdMob — méthode

Notes de travail pour la cible `io.stark.admob` (AdMobile - AdMob Metrics, Wixel Store).
La méthode vaut pour n'importe quelle app qui intègre le Google Mobile Ads SDK.

## 1. Reconnaissance de la cible

| Champ | Valeur |
| --- | --- |
| Package | `io.stark.admob` |
| Nom | AdMobile - AdMob Metrics |
| Éditeur | Wixel Store (`wixeless.github.io`) |
| Version | 2.4.8, versionCode 68 |
| Mise à jour | 27 décembre 2025 |
| minSdk | Android 6.0 (API 23) |
| Installs | 50 000+ |
| Note | 4,6 |
| Distribution | XAPK ~9 Mo, split de config `armeabi-v7a` |
| Monétisation | badges Play « Contains ads » **et** « In-app purchases » (0,99 $ – 39,99 $ par article) |

Deux surfaces de monétisation distinctes, donc deux patches distincts :

1. **les pubs** servies par le SDK Google Mobile Ads dans l'app ;
2. **le déblocage Pro** derrière l'achat intégré.

Le split par ABI implique des bibliothèques natives. Il faut vérifier au premier
déballage si l'app est native Kotlin/Java, Flutter ou React Native : si c'est du
Flutter, la logique métier est dans `libapp.so` et le patch bytecode n'atteint que
la couche plugin (SDK pub, SDK billing), pas le code Dart. `tools/apk-recon.sh`
répond à cette question en premier.

## 2. Récupérer et inspecter l'APK

```sh
tools/apk-recon.sh AdMobile_2.4.8.xapk
```

Le script n'a besoin que de `unzip` et `grep` : il lit directement les pools de
chaînes des `classes*.dex`, donc pas d'apktool ni de jadx pour la première passe.
Il sort :

- le runtime (Flutter / React Native / natif) et les `.so` embarqués ;
- quelles classes publiques du SDK GMA sont présentes et non obfusquées ;
- les autres régies éventuelles (AppLovin, Unity, IronSource…) ;
- l'App ID AdMob et **les ad unit IDs** (`ca-app-pub-…/…`) ;
- le SDK de facturation et les chaînes d'entitlement.

Pour lire le bytecode ensuite : `apktool d base.apk` (smali) ou `jadx-gui` (Java).

## 3. Les quatre points d'entrée à neutraliser

Le SDK GMA expose une API publique que R8 **ne renomme pas** : `play-services-ads`
embarque des règles ProGuard « consumer » qui la conservent. On peut donc cibler
par nom de classe, sans fingerprint spécifique à l'app. Tout ce qui est en dessous
(`com.google.android.gms.ads.internal.**`, les classes `zz*`) est déjà pré-obfusqué
et ne doit pas être touché.

| Format | Classe | Méthode |
| --- | --- | --- |
| Bannière | `com.google.android.gms.ads.BaseAdView` (parent de `AdView`, `AdManagerAdView`) | `loadAd(AdRequest)` |
| Native | `com.google.android.gms.ads.AdLoader` | `loadAd`, `loadAds` |
| Interstitiel | `com.google.android.gms.ads.interstitial.InterstitialAd` | `load(...)` statique |
| App open | `com.google.android.gms.ads.appopen.AppOpenAd` | `load(...)` statique |
| Rewarded | `com.google.android.gms.ads.rewarded.RewardedAd` | `load(...)` statique |

Toutes ces méthodes retournent `void`, donc un `return-void` en tête suffit :
la requête n'est jamais émise, et pour les formats plein écran le callback de
chargement ne se déclenche jamais — l'app n'a donc jamais de pub à afficher.

### Le piège de la bannière

Neutraliser `loadAd` ne suffit pas pour une bannière. `AdView.onMeasure` retombe
sur les dimensions de l'`AdSize` quand la vue n'a pas d'enfant : le SDK ne charge
plus rien, mais le layout garde un trou de 320×50. Il faut aussi masquer le
conteneur. Comme `BaseAdView` est une `View`, on écrase le corps de `loadAd` par :

```smali
const/16 p1, 0x8
invoke-virtual { p0, p1 }, Landroid/view/View;->setVisibility(I)V
return-void
```

`p1` porte l'`AdRequest`, qui est mort dès le `return-void` injecté : on le
réutilise comme registre de travail, ce qui évite d'aller chercher un registre
libre avec `FreeRegisterProvider`.

### Ce qu'on ne touche pas

- `MobileAds.initialize(...)` : le neutraliser fait planter certaines branches du
  SDK qui supposent l'initialisation faite. Aucun bénéfice, du risque en plus.
- Le meta-data `com.google.android.gms.ads.APPLICATION_ID` du manifeste : le
  supprimer fait crasher le SDK au démarrage. Le remplacer par l'App ID de test
  Google ne supprime pas les pubs, ça les remplace par des pubs de test.

## 4. Le patch

`patches/src/main/kotlin/app/morphe/patches/admobile/ads/HideAdsPatch.kt`.

Il itère sur une table de points d'entrée, résout chaque classe par descripteur,
neutralise **toutes** les surcharges `void` du nom de méthode visé (les listes de
paramètres changent selon la version du SDK, pas les noms), et lève une
`PatchException` seulement si rien n'a été trouvé — une classe absente est normale,
l'app n'embarque que les formats qu'elle utilise.

## 5. Déblocage Pro — la piste

Même approche que `TransitUnlockPatch` : ancrer un `Fingerprint` sur une chaîne
stable plutôt que sur une signature de méthode.

```kotlin
internal object IsPremiumFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    strings = listOf("activate_royale_subscription", "product1234"),
)
```

Pour AdMobile, les ancres candidates sortent de la section « Billing and paywall »
du script de recon : identifiants de produit Play, clé d'entitlement RevenueCat, ou
la chaîne du SKU d'abonnement. On repère le `MOVE_RESULT` du test d'abonnement et
on le remplace par `const/4 vX, 0x1`, exactement comme pour Transit.

Les ad unit IDs sont aussi d'excellentes ancres si l'on veut cibler le code de
l'app plutôt que le SDK : `Fingerprint(strings = listOf("ca-app-pub-…/…"))` tombe
sur la méthode exacte qui construit la requête, et survit à l'obfuscation comme aux
montées de version.

## 6. Limites connues

- L'APK n'a pas pu être récupéré depuis cet environnement (Cloudflare bloque les
  CDN de miroirs APK depuis une IP datacenter). Les points d'entrée du SDK sont
  donc issus de l'API publique GMA, pas d'une lecture du bytecode de cette app :
  passer `tools/apk-recon.sh` sur le XAPK confirme lesquels sont réellement présents.
- Le plugin Gradle `app.morphe.patches` vit dans un registre GitHub Packages privé
  (`MorpheApp/registry`), donc la compilation n'a pas pu être vérifiée ici.
- Un APK re-signé perd l'accès aux achats Play déjà effectués et à toute API liée à
  la signature. Voir l'avertissement du README sur Transit et les cartes.
