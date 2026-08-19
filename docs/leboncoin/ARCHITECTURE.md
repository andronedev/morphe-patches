# Morphe LBC — mod framework pour `fr.leboncoin`

Objectif : faire pour leboncoin ce qu'Aliucord/Vencord font pour Discord — patcher l'APK
**une seule fois** avec un injecteur minimal, puis tout implémenter sous forme de **plugins
chargés à l'exécution**, mis à jour sans repatcher.

---

## 1. Pourquoi pas des patchs bytecode classiques

L'approche « un patch Morphe par fonctionnalité » (comme les patchs Transit de ce repo) suppose
des *fingerprints* stables. Sur `fr.leboncoin` :

- release toutes les 1 à 2 semaines,
- UI en Jetpack Compose : pas d'ID de ressource à accrocher, tout est des lambdas inline,
- ~60 500 classes réparties sur 7 dex : chaque repatch coûte cher.

Bonne surprise confirmée par la recon (cf. [`RECON.md`](RECON.md)) : **R8 ne renomme pas le code
`fr.leboncoin.*`**, seules les bibliothèques tierces sont obfusquées. Les patchs restent donc
lisibles d'une version à l'autre — mais il faut quand même repatcher l'APK à chaque mise à jour de
l'app, ce qui est le vrai coût.

D'où la conclusion d'Aliucord, qui tient toujours : **le patch ne fait qu'injecter un chargeur**, la
logique vit dans un dex externe, et une correction de plugin ne demande pas de repatcher.

## 2. Vue d'ensemble

```
┌─ APK patché (une fois) ──────────────────────────────────────────┐
│                                                                  │
│  AndroidManifest.xml   ← permissions ajoutées                    │
│  assets/morphe/bindings.json  ← noms obfusqués résolus au patch  │
│  Application.attachBaseContext()                                 │
│        │                                                         │
│        └──> Injector.init(ctx)   (smali généré par le patch)     │
│                    │                                             │
└────────────────────┼─────────────────────────────────────────────┘
                     │  DexClassLoader
                     ▼
┌─ runtime.dex (remplaçable à chaud) ──────────────────────────────┐
│  Lbc.init()                                                      │
│   ├─ Bindings      : lit assets/morphe/bindings.json             │
│   ├─ Hooks         : backend de hook ART (Pine), sans root       │
│   ├─ HttpBridge    : intercepteur global sur le client OkHttp    │
│   └─ PluginManager : charge /sdcard/Morphe/LBC/plugins/*.zip     │
│         ├─ NoAds                                                 │
│         ├─ BetterFilters                                         │
│         └─ AutoRepost                                            │
└──────────────────────────────────────────────────────────────────┘
```

### La pièce centrale : `bindings.json`

C'est ce qui rend le runtime indépendant de la version de l'app. Le **patch** (qui a accès au
bytecode et aux fingerprints) résout les noms obfusqués au moment du patch et les écrit dans les
assets. Le **runtime** ne code jamais un nom obfusqué en dur, il lit le binding :

```json
{
  "apkVersion": "100.120.1",
  "classes": {
    "okhttp.clientBuilder": "<nom obfusqué résolu par fingerprint>",
    "okhttp.interceptor":   "<nom obfusqué résolu par fingerprint>",
    "app.application":      "fr.leboncoin.app.PolarisApplicationRelease"
  },
  "methods": {
    "okhttp.clientBuilder.build": "<nom obfusqué>"
  },
  "endpoints": {
    "apiBase":     "https://api.leboncoin.fr",
    "adProlong":   "https://api.leboncoin.fr/api/pintad/v1/public/manual/prolongation/{id}",
    "adDelete":    "https://api.leboncoin.fr/api/pintad/v1/public/manual/delete/ads",
    "ownerListing":"https://api.leboncoin.fr/api/adfinder/v1/owner_listing"
  }
}
```

Comme le code `fr.leboncoin.*` garde ses noms, `bindings.json` ne sert en pratique qu'aux
bibliothèques tierces (OkHttp, Retrofit) et aux endpoints. Les classes de l'app, les plugins les
adressent directement par leur nom réel.

Quand leboncoin sort une nouvelle version, on repatche : les fingerprints reproduisent le
`bindings.json`, le `runtime.dex` et les plugins ne bougent pas.

## 3. Interception réseau plutôt que patch d'UI

Toutes les fonctionnalités demandées passent par le réseau, pas par l'UI :

| Fonction        | Où ça se joue                                                              |
|-----------------|----------------------------------------------------------------------------|
| No ads          | Réponses JSON de recherche : on retire les blocs sponsorisés, et on court-circuite les requêtes vers les régies (AppLovin, Smart AdServer, Prebid, Google Ads, Adjust, Batch). |
| Meilleurs filtres | Post-traitement de la même réponse : exclusion mots-clés, prix/km réels, exclusion pros, dédoublonnage, blacklist vendeurs. |
| Auto-prolongation | `POST /api/pintad/v1/public/manual/prolongation/{id}` — l'action de prolongation native de l'app, déclenchée automatiquement. **Voie recommandée.** |
| Auto-repost     | `DELETE` puis redépôt de l'annonce. Même objectif, mais contraire aux CGU et détecté comme doublon — cf. §7. |

### Auto-prolongation plutôt qu'auto-repost

La recon a montré que l'app dispose déjà d'une action **« prolonger »** de première partie
(`/api/pintad/v1/public/manual/prolongation/{list_id}`, deeplink `leboncoin.fr/annonce/%s/prolonger`),
ainsi que d'un renouvellement automatique (`/api/services/v1/ads/{ad_id}/auto-renewal`).

Automatiser cette action atteint l'objectif — garder l'annonce vivante et visible — sans supprimer
ni recréer, donc sans doublon et sans aller contre les CGU. C'est `AutoProlongPlugin`, activé en
premier. `AutoRepostPlugin` reste fourni parce qu'il a été explicitement demandé, mais il est
désactivé par défaut et en `dryRun`.

### DataDome

L'app embarque **DataDome** (`co.datadome.sdk.DataDomeInterceptor`, `DataDomeCookieJar`, message
« Blocked request by DataDome »). Toute requête rejouée depuis un client HTTP maison sera vue comme
du trafic non signé et rejetée.

Donc : les actions automatisées doivent passer par **la pile OkHttp de l'app**, où l'intercepteur
DataDome est déjà branché — c'est le chemin de requête normal de l'application, pas un
contournement. Ce projet n'implémente rien pour falsifier, résoudre ou éviter DataDome ; si une
action ne peut pas passer par le client de l'app, elle n'est pas automatisée.

Le premier jet de `AutoRepostPlugin`/`AutoProlongPlugin` utilise `HttpURLConnection` avec les
en-têtes capturés : c'est le mode « v1 », **qui sera probablement bloqué**. Le passage par le client
de l'app (capture de l'instance Retrofit / des `*ApiService`) est la suite à implémenter.

C'est beaucoup plus stable que de hooker du Compose : le format JSON de l'API publique bouge
lentement, alors que les noms de classes changent à chaque build.

`HttpBridge` s'accroche sur `OkHttpClient$Builder.build()` (nom résolu via `bindings.json`),
insère un intercepteur maison en tête de chaîne, et redistribue chaque requête/réponse aux
plugins abonnés.

## 4. API plugin

Un plugin est un zip contenant `plugin.json` + `classes.dex` :

```json
{
  "id": "fr.moi.monplugin",
  "name": "Mon plugin",
  "version": "1.0.0",
  "author": "moi",
  "entry": "fr.moi.monplugin.MonPlugin",
  "minRuntime": 1
}
```

```java
public final class MonPlugin extends Plugin {
    @Override public void onStart() {
        http().onResponse("/finder/search", (req, body) -> {
            // body est mutable, on renvoie le JSON modifié (ou null pour ne rien changer)
            return null;
        });
    }
}
```

Les trois fonctionnalités demandées sont livrées comme plugins **intégrés** (`app.morphe.lbc.plugins`),
donc elles servent aussi d'exemples de référence pour des plugins tiers.

## 5. État actuel / ce qui reste à faire

Rien n'a encore été compilé ni exécuté sur appareil (voir §6). Statut par brique :

| Brique | Statut |
|--------|--------|
| `docs/leboncoin/` (ce document, `RECON.md`) | fait |
| `tools/lbc-recon.py` | fait, **exécuté sur `fr.leboncoin` 100.120.1** |
| Recon : classe `Application`, endpoints, SDK pub, DataDome | fait, cf. [`RECON.md`](RECON.md) |
| `runtime/leboncoin/` — loader, hooks, pont HTTP, plugins | écrit, **jamais compilé** |
| `patches/.../leboncoin/` — injecteur Morphe | écrit, **jamais compilé** |
| `bindings.json` — endpoints | rempli avec les valeurs relevées |
| `bindings.json` — classes OkHttp obfusquées | à résoudre par fingerprint (ancres identifiées) |
| Shim `Interceptor` injecté dans l'APK | pas commencé — sans lui, `HttpBridge` ne reçoit rien |
| Appels via le client de l'app (contrainte DataDome) | pas commencé |
| Écran de réglages in-app | pas commencé (phase 2) |
| App manager (installer/MAJ plugins) | pas commencé (phase 3) |

### Ordre de travail recommandé

1. Compiler le runtime (`cd runtime/leboncoin && ./gradlew extractRuntimeDex`) — c'est la première
   chose à valider, le code n'a jamais vu un compilateur.
2. Patcher, installer, vérifier que l'app démarre et que `Lbc.init()` est appelé
   (`adb logcat -s MorpheLBC`). Tant que `runtime.dex` est absent, l'injection est inerte : c'est
   voulu, ça permet de tester le patch seul.
3. Écrire le shim `Interceptor` (fingerprint OkHttp via l'ancre `network interceptor `) et vérifier
   qu'on voit passer les appels `/api/adfinder/...`.
4. Activer `NoAds` puis `BetterFilters` — purement en lecture, aucun risque côté compte.
5. `AutoProlong` ensuite, en `dryRun`, puis réel avec quotas.
6. `AutoRepost` en dernier, si vraiment nécessaire (§7).

## 6. Limites de l'environnement de dev utilisé

Ce scaffolding a été produit dans un conteneur sans :

- SDK Android / apktool / baksmali,
- accès au registre Maven privé `maven.pkg.github.com/MorpheApp/registry` (HTTP 401), donc
  `./gradlew :patches:build` ne peut pas tourner.

L'APK, lui, a bien été récupéré et analysé (`tools/lbc-recon.py`), d'où [`RECON.md`](RECON.md).

Conséquence : **ni le Kotlin des patchs ni le Java du runtime n'ont été compilés**. Attendre des
erreurs de compilation au premier build local, en particulier sur :

- l'API exacte du patcher Morphe (`dependsOn`, `mutableClassDefBy`, `get("assets", false)`) ;
- l'API de Pine dans `PineBackend` (seul fichier qui en dépend, isolé exprès) ;
- l'allocation de registres et le smali de `LeboncoinLoaderPatch`.

## 7. Risques à connaître

- **Auto-repost** : les CGU leboncoin interdisent la suppression/republication pour remonter une
  annonce (c'est leur option payante « remontée »). Les doublons sont détectés côté serveur, et
  DataDome voit passer le trafic. Risque réel de suspension du compte — d'où les quotas par défaut
  (§ `AutoRepostPlugin`) et le `dryRun` activé d'origine. **Préférer `AutoProlong`**, qui utilise
  l'action de prolongation prévue par l'app.
- **Re-signature** : l'APK repatché est signé avec ta clé. Les fonctions qui reposent sur
  l'intégrité (paiement intégré, SafetyNet/Play Integrity si utilisé) peuvent tomber.
- **Hooking sans root** : Pine dépend de la version d'ART. Prévoir un fallback « runtime désactivé »
  qui laisse l'app fonctionner normalement si l'init échoue — c'est déjà le comportement de `Lbc.init()`.
- **Distribution** : ne pas redistribuer l'APK patché (l'APK d'origine est sous copyright leboncoin).
  Le repo ne contient que des patchs, jamais de binaire de l'app.
