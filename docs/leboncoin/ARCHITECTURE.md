# Morphe LBC — mod framework pour `fr.leboncoin`

Objectif : faire pour leboncoin ce qu'Aliucord/Vencord font pour Discord — patcher l'APK
**une seule fois** avec un injecteur minimal, puis tout implémenter sous forme de **plugins
chargés à l'exécution**, mis à jour sans repatcher.

---

## 1. Pourquoi pas des patchs bytecode classiques

L'approche « un patch Morphe par fonctionnalité » (comme les patchs Transit de ce repo) suppose
des *fingerprints* stables. Sur `fr.leboncoin` :

- release toutes les 1 à 2 semaines,
- R8 en mode `full` : noms de classes/méthodes renommés à chaque build,
- UI en Jetpack Compose : pas d'ID de ressource à accrocher, tout est des lambdas inline.

Un patch « No ads » écrit contre la version N est mort à la version N+1. D'où la même conclusion
qu'Aliucord : **le patch ne fait qu'injecter un chargeur**, et la logique vit dans un dex externe.

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
  "apkVersion": "8.x.y",
  "patchedAt": "2026-08-19T00:00:00Z",
  "classes": {
    "okhttp.clientBuilder": "okhttp3.OkHttpClient$Builder",
    "okhttp.interceptor":   "okhttp3.Interceptor",
    "app.application":      "fr.leboncoin.LBCApplication"
  },
  "methods": {
    "okhttp.clientBuilder.build": "build"
  },
  "endpoints": {
    "search":     "https://api.leboncoin.fr/finder/search",
    "adDelete":   "…",
    "adCreate":   "…"
  }
}
```

Quand leboncoin sort une nouvelle version, on repatche : les fingerprints reproduisent le
`bindings.json`, le `runtime.dex` et les plugins ne bougent pas.

## 3. Interception réseau plutôt que patch d'UI

Toutes les fonctionnalités demandées passent par le réseau, pas par l'UI :

| Fonction        | Où ça se joue                                                              |
|-----------------|----------------------------------------------------------------------------|
| No ads          | Réponses JSON de `/finder/search` : on retire les blocs sponsorisés, et on court-circuite les requêtes vers les régies (Criteo, AppLovin, Google Ads, Batch). |
| Meilleurs filtres | Post-traitement de la même réponse : exclusion mots-clés, prix/km réels, exclusion pros, dédoublonnage, blacklist vendeurs. |
| Auto-repost     | Rejoue `DELETE` puis `POST` d'annonce avec le client OkHttp **déjà authentifié** de l'app. |

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

Ce qui est écrit ici a été conçu **sans accès à l'APK ni au SDK Android** (voir §6), donc rien
n'est encore compilé ni testé sur appareil. Statut par brique :

| Brique | Statut |
|--------|--------|
| `docs/leboncoin/` (ce document) | fait |
| `tools/lbc-recon.py` — extraction des infos manquantes depuis l'APK | fait, à exécuter sur ton APK |
| `runtime/leboncoin/` — squelette du runtime (loader, hooks, http, plugins) | écrit, à compiler |
| `patches/.../leboncoin/` — injecteur Morphe | écrit, **fingerprints à résoudre** |
| Résolution `bindings.json` | bloqué : besoin de la sortie de la recon |
| Écran de réglages in-app | pas commencé (phase 2) |
| App manager (installer/MAJ plugins) | pas commencé (phase 3) |

### Ordre de travail recommandé

1. Lancer `tools/lbc-recon.py` sur l'APK → produit `recon/` (strings, endpoints, candidats).
2. Écrire les fingerprints de l'injecteur à partir de ça, patcher, vérifier que l'app démarre
   toujours et que `Lbc.init()` est bien appelé (logcat `MorpheLBC`).
3. Brancher `HttpBridge`, vérifier qu'on voit passer `/finder/search`.
4. Activer `NoAds` puis `BetterFilters` (purement côté réponse, sans risque).
5. `AutoRepost` en dernier, avec quotas — c'est le seul qui écrit côté serveur.

## 6. Limites de l'environnement de dev utilisé

Ce scaffolding a été produit dans un conteneur sans :

- l'APK `fr.leboncoin` (il est sur ta machine),
- SDK Android / apktool / baksmali,
- accès au registre Maven privé `maven.pkg.github.com/MorpheApp/registry` (HTTP 401), donc
  `./gradlew :patches:build` ne peut pas tourner.

Conséquence : **le code Kotlin des patchs n'a pas été compilé**, et les fingerprints sont des
emplacements marqués `TODO(recon)`. Il faut une passe locale (SDK + APK + credentials Morphe)
pour valider.

## 7. Risques à connaître

- **Auto-repost** : les CGU leboncoin interdisent la suppression/republication pour remonter une
  annonce (c'est leur option payante « remontée »). Les doublons sont détectés côté serveur.
  Risque réel de suspension du compte — d'où les quotas par défaut (§ `AutoRepostPlugin`).
- **Re-signature** : l'APK repatché est signé avec ta clé. Les fonctions qui reposent sur
  l'intégrité (paiement intégré, SafetyNet/Play Integrity si utilisé) peuvent tomber.
- **Hooking sans root** : Pine dépend de la version d'ART. Prévoir un fallback « runtime désactivé »
  qui laisse l'app fonctionner normalement si l'init échoue — c'est déjà le comportement de `Lbc.init()`.
- **Distribution** : ne pas redistribuer l'APK patché (l'APK d'origine est sous copyright leboncoin).
  Le repo ne contient que des patchs, jamais de binaire de l'app.
