Les mises à jour dans l’application, des dossiers choisis plutôt que saisis, des titres qui demandent avant de partir, un dessin plus fin.

- **Mises à jour dans l’application.** Chaque jour, Houselights cherche une nouvelle version, la propose sur l’accueil, la télécharge et la vérifie ; Android vous demande de confirmer (Réglages › À propos › Mises à jour).
- **Ajouter un dossier en le choisissant.** Le NAS, son dossier partagé puis le dossier se choisissent dans des listes. Un dossier peut aussi être sur le lecteur, disque interne ou USB (#33). Un deuxième dossier n’est plus refusé pour un chemin complet (#79).
- **Champs de texte.** Gauche et Droite déplacent le curseur, une croix efface le champ, l’année d’Identifier peut rester vide (#78).
- **La première analyse** montre vos affiches remplir un mur ; « Aller à l’accueil » la laisse continuer.
- **Des titres mieux reconnus.** Les identifiants des noms de fichiers ({tmdb-…}) sont lus, et les résultats de TMDB pesés plutôt que le premier pris. Les incertains attendent dans Réglages › Bibliothèque › À identifier.
- **Les fichiers supprimés** quittent la bibliothèque à l’analyse suivante (#62) ; ce que vous en aviez fait revient avec eux, même renommés par Radarr ou Sonarr.
- **Des titres qui demandent avant de partir.** Si beaucoup de titres d’un dossier disparaissent d’un coup, ou si le dossier est supprimé ou déplacé sur un NAS qui répond, rien ne part : l’accueil demande, « Garder » ou « Retirer ».
- **Menu sur une affiche** : Marquer comme vu, Ajouter aux favoris ou Modifier.
- **Les images sur une planche-contact**, par langue. L’affiche et le fond d’écran d’une saga se choisissent aussi.
- **La page d’une saga devient « Les volets » :** un grand volet par film, celui sélectionné s’ouvrant sur son film.
- **Épisodes.** Les animés numérotés depuis le début (One Piece - 1071) rejoignent les saisons de TMDB, et Modifier › Ordre des épisodes propose l’ordre DVD ou absolu. Une série d’une seule saison dit « Marquer la saison comme vue » (#31).
- **Dessin.** Un texte plus fin en trois graisses, des coins moins arrondis, des lignes plus denses ; sur la fiche, l’accroche ouvre le résumé et le générique se lit comme une affiche ; le premier écran présente ses deux choix en portes, comme l’accueil.
- **Un film en plusieurs copies.** Reprendre relance la copie vue en dernier ; la fiche montre les formats de la copie que Lire lance ; les copies gardent leur numéro dans Versions ; retirer le dossier de sa première copie ne perd plus l’affiche ni la copie choisies. Jellyfin lit la copie choisie dans Versions.
- **Analyses.** Une analyse ne fait plus attendre vos actions sur TMDB ou le NAS, et s’interrompt pendant un film. Les serveurs NFS en espace utilisateur (unfs3) ne semblent plus injoignables, et un lien qui ne mène nulle part vaut un fichier supprimé. Des noms copiés depuis un Mac ne dédoublent plus une série.
- **Plus solide.** Sur un firmware signé avec des clés de test (celui du Z1000 Pro), un lecteur Zidoo mis à jour ne reçoit vos mots de passe qu’une fois que vous lui faites confiance. Un mot de passe que le coffre de clés n’a pas pu ouvrir est dit conservé, jamais refusé. Une langue de piste enregistrée avec une région (ar-EG) apparaît bien choisie. Le lancement, les Réglages et Trakt restent fluides.

## Notes de bêta

- La 0.3.0 n’a pas de mise à jour intégrée : installez celle-ci à la main (README, Install) ; les suivantes arriveront par l’application.
- Testée sur un Zidoo Z1000 Pro (Android 9) avec un NAS Synology en NFS ; SMB, les disques USB, Jellyfin et Trakt moins : vos retours sont bienvenus dans les issues.
- Les traductions hors anglais et français restent à relire par des locuteurs natifs.
- Préférez NFS. En SMB, utilisez un compte du NAS dédié, en lecture seule (README, Security).
