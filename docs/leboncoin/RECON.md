# Recon — `fr.leboncoin` 100.120.1 (versionCode 100120100)

Relevé produit par `python3 tools/lbc-recon.py fr.leboncoin.apk -o recon/` sur l'APK de base du
bundle (split APK : `fr.leboncoin.apk` + `config.*`). Les fichiers bruts ne sont pas versionnés
(cf. `.gitignore`), seules les conclusions le sont.

## Chiffres

| | |
|---|---|
| dex | 7 (`classes.dex` … `classes7.dex`), ~60 500 classes |
| chaînes uniques | 192 232 |
| types | 67 720 |
| permissions | 29 |
| activités | 239 |
| `debuggable` | false |

## Le point le plus important : le code de l'app n'est pas renommé

Contrairement à l'hypothèse de départ, R8 **conserve les noms sous `fr.leboncoin.*`** ; seules les
bibliothèques tierces sont obfusquées. On lit directement :

```
fr.leboncoin.app.PolarisApplicationRelease                                  <- classe Application
fr.leboncoin.libraries.core.search.SearchRequest{,Model}
fr.leboncoin.repositories.admanagement.api.PublishedClassifiedApiService    <- gestion de mes annonces
fr.leboncoin.repositories.adownerlistingrepository.AdOwnerListingApiService <- liste de mes annonces
fr.leboncoin.repositories.dynamicaddeposit.api.classifieds.DynamicDepositClassifiedApiService
fr.leboncoin.repositories.continuoustopad.ContinuousTopAdApiService         <- remontée payante
fr.leboncoin.domains.dynamicaddeposit.models.adsubmit.AdSubmitClassifiedData
```

Conséquences directes :

- l'injecteur peut viser `fr.leboncoin.app.PolarisApplicationRelease` sans fingerprint fragile ;
- le runtime et les plugins peuvent hooker les classes de l'app **par leur vrai nom**, ce qui rend
  `bindings.json` nécessaire uniquement pour les bibliothèques tierces (OkHttp, Retrofit) ;
- un patch reste sensible aux refactorings de l'app, mais plus au renommage build à build.

## Endpoints utiles (base `https://api.leboncoin.fr`)

Gestion de ses propres annonces — API « pintad » :

| Chemin | Usage |
|---|---|
| `/api/pintad/v1/public/manual/prolongation/{list_id}` | **prolonger une annonce** |
| `/api/pintad/v1/public/manual/delete/ads` | supprimer (liste d'annonces) |
| `/api/pintad/v1/public/manual/pause/ads` | mettre en pause |
| `/api/pintad/v1/public/manual/unpause/ads` | réactiver |
| `/api/pintad/v1/public/manual/classified/{list_id}` | détail d'une de mes annonces |
| `/api/pintad/v1/public/manual/ad_options/{list_id}` | options d'une annonce |
| `/api/pintad/v1/public/expired` | annonces expirées |
| `/api/services/v1/ads/{ad_id}/auto-renewal` | renouvellement automatique |
| `/api/options/v1/panda/classifieds/automatic-renewal` | renouvellement automatique (offre pro) |

Recherche et consultation :

| Chemin | Usage |
|---|---|
| `/api/adfinder/v1/owner_listing` | annonces d'un vendeur |
| `/api/adfinder/v1/classified/{list_id}` | détail d'annonce |
| `/api/adfinder/v1/around_me` | autour de moi |
| `/api/mysearch/v1/searches` | recherches sauvegardées |
| `/api/same/v5/search/{list_id}` | annonces similaires |

Dépôt : `/api/adsubmit/dynamic-deposit/config` pour le formulaire dynamique ; la soumission passe
par `DynamicDepositClassifiedApiService` (chemin exact à confirmer par capture réseau, l'annotation
Retrofit ne ressort pas telle quelle dans la table des chaînes).

Autres bases : `https://auth.leboncoin.fr/api/authorizer/v2/token/` (jetons),
`https://api.leboncoin.fr/api/knocker/router/v1` (notifications Adevinta « Knocker »).

## Pile réseau

- **OkHttp 5.4.0**, obfusqué. Ancres exploitables pour un fingerprint : `network interceptor `,
  `Unexpected status line: `, `unexpected end of stream on `, `okhttp/5.4.0`.
- **Retrofit 2**, obfusqué (seule `retrofit2.HttpException` garde son nom) ; les messages d'erreur
  habituels de Retrofit sont supprimés, donc pas d'ancre facile de ce côté — passer par les
  interfaces `*ApiService` de l'app, qui, elles, sont nommées.

## Anti-bot : DataDome

`co.datadome.sdk.DataDomeInterceptor`, `DataDomeCookieJar`, « Blocked request by DataDome ».

C'est la contrainte structurante pour l'auto-repost et l'auto-prolongation : **rejouer une requête
depuis un client HTTP maison (HttpURLConnection) sera vu comme du trafic non signé et bloqué.** Les
appels doivent passer par la pile OkHttp de l'app, où l'intercepteur DataDome est déjà branché.

Ce projet n'implémente rien pour contourner ou falsifier DataDome : on réutilise le chemin de
requête normal de l'app, ou on n'automatise pas.

## SDK publicitaires et traçage détectés

`google-ads` / `admob`, `applovin`, `smart-adserver`, `prebid`, `adjust`, `batch`,
`firebase-analytics`, `didomi` (consentement).

Chemins publicitaires repérés dans les chaînes : `/pagead/adview`, `/pcs/view`,
`/pagead/conversion`, `/dbm/ad`, `/Leboncoin/Android/Adview/SmallBanner/`,
`/Leboncoin/Android/Listing/MPU/`.

## Ce que ça change pour le projet

1. **L'auto-repost n'est pas la bonne primitive.** L'app expose une **prolongation** de première
   partie (`/manual/prolongation/{list_id}`, plus le deeplink `leboncoin.fr/annonce/%s/prolonger`).
   Automatiser la prolongation atteint le même objectif — garder l'annonce vivante — sans doublon,
   sans suppression, et sans aller contre les CGU comme le fait le couple supprimer/redéposer.
   `AutoProlongPlugin` devient donc le plugin recommandé, `AutoRepostPlugin` reste disponible mais
   déconseillé.
2. **L'injection est fiable** : la classe `Application` est nommée.
3. **DataDome impose de passer par le client de l'app** pour toute action automatisée.
