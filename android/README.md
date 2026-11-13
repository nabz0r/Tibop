# Lettrine pour Android

L’[APK 0.10.0](artifacts/lettrine.apk) fonctionne en invité hors ligne à partir d’Android 8 (API 26). Il contient le modèle d’explication des coups et porte la même signature que l’APK précédent pour permettre une mise à jour directe. Le [bundle 0.10.0](artifacts/lettrine.aab) est destiné à l’importation dans la Play Console.

## Jouer

- **Défi du jour** propose une nouvelle position selon la date locale, même en mode avion. Validez un coup légal pour compter la journée dans votre série. Vous pouvez rejouer pour améliorer le record du jour.
- **Bats mon score** partage une carte PNG avec la date, le score et, lorsqu’elle s’applique, la série. Le lien associé mène à une [page d’invitation](https://nabz0r.github.io/scrabnble/defi/) avec le bouton **Ouvrir dans Lettrine**. L’application retrouve le même plateau et le même chevalet, puis compare le coup du destinataire au score partagé. **Copier le lien** est disponible pour les messageries qui séparent les images du texte. Une invitation pour une autre date se joue hors série.
- **Profil** réunit le pseudo, la photo, le pays, l’âge et le sexe, tous facultatifs. Les exploits se débloquent à partir de vos parties et de vos défis. La photo, le pays, l’âge et le sexe restent sur cet appareil.
- **Amis** propose une invitation à usage unique, des duels à un coup sur un plateau commun, la revanche et un classement calculé sur les duels terminés entre membres de votre cercle d’amis. Les deux joueurs peuvent jouer à des moments différents. Le score de l’adversaire est dévoilé une fois les deux coups envoyés.
- **Revanche miroir** apparaît après un duel terminé où chacun avait un chevalet distinct. Rejouez sur le même plateau, chacun avec le chevalet que son ami avait au premier duel. Le résultat affiche aussi le cumul des deux manches. Les deux joueurs utilisent Android 0.10 pour les duels à chevalets distincts. Les anciens duels à chevalet commun gardent la revanche classique.
- **Tournoi du week-end** se trouve dans **Amis**. Du samedi à 0 h au lundi à 0 h UTC, jouez un coup sur chacun des trois plateaux communs. Le classement entre amis additionne les points des trois défis terminés et attribue la même place en cas d’égalité. L’app affiche les heures dans votre fuseau. Le tournoi et l’envoi des coups demandent une connexion au serveur social.
- **Nocturne dorée** se débloque dès que le serveur confirme les trois coups d’un même week-end. Le fond est appliqué aux cartes **Bats mon score** et reste disponible hors ligne sur l’appareil. L’onglet **Cartes** de la Collection permet de le prévisualiser ou de revenir au fond classique. Sur un autre appareil, retrouver le même compte social puis ouvrir le tournoi restaure le déblocage.
- **Collection**, dans **Profil**, permet de choisir séparément le plateau et les tuiles. La porcelaine se débloque après un défi quotidien, Marée après trois jours de défi, Ambre après trois parties solo terminées et Ardoise après cinq. Les parties et défis déjà enregistrés comptent. La collection fonctionne hors ligne.
- **Comment jouer**, depuis l’accueil, propose un premier coup guidé et une page sur les règles, NAB et le modèle d’explication embarqué. Dans un défi, faites glisser une tuile depuis le chevalet ou touchez une lettre puis une case. Vous pouvez déplacer un jeton déjà posé, zoomer dans les deux sens et vérifier la validité du coup avant de l’envoyer.
- **Premier défi** ouvre une position courte depuis l’accueil. Prolongez le mot sur le plateau, validez votre pose, puis comparez votre score à la proposition de NAB. Vous pouvez retenter le défi à tout moment.
- **Mes parties** permet de garder plusieurs solos et parties à tour de rôle sur l’appareil. Chaque partie reprend avec son score, son tour et ses tuiles en brouillon. Un abandon demande confirmation. Une connexion Bluetooth active reste liée à une seule partie.
- **Entre amis** accueille deux à quatre joueurs sur le même appareil. Le chevalet est masqué à chaque changement de joueur. Les parties solo et locales en cours sont sauvegardées après chaque tour.
- **Bluetooth** relie deux appareils préalablement associés dans Android. L’un choisit **Héberger** et l’autre choisit l’appareil hôte. Les deux APK doivent embarquer le même lexique. Une coupure interrompt la partie.
- **Contre un bot** propose quatre niveaux et un niveau adaptatif. La première partie propose Promeneur et l’application mémorise ensuite le niveau choisi. L’adaptatif lit l’Elo local et ajuste sa difficulté pendant la partie en fonction de l’écart de score. Les parties avec aide au placement restent dans l’historique et sont exclues de l’Elo.
- En partie, glissez une lettre depuis le chevalet ou touchez une lettre puis une case. Déplacez les tuiles encore en brouillon directement sur le plateau. Le jeu affiche la validité, le score ou la raison du refus avant la validation. Pincez pour zoomer, ou utilisez les boutons du plateau.
- Dans **Profil → Réglages**, choisissez une ambiance originale et son volume, et activez séparément les sons de tuiles. Les sons se jouent hors ligne.
- **Historique** présente les résultats, les statistiques et la relecture coup par coup. Exporter une partie ou tout l’historique en JSON, puis déposer le fichier dans l’administration du studio Lettrine.
- **Évolution de l’Elo** se trouve dans l’historique. Elle suit la cote de départ puis les parties solo cotées. Sélectionnez un point pour retrouver la cote, sa variation et le niveau de NAB utilisé.
- **Expliquer le dernier coup** s’affiche dans une partie après le premier tour. En relecture, **Expliquer ce coup** s’applique au tour affiché. Les scores, mots croisés, cases bonus et jokers proviennent du moteur. Le modèle embarqué choisit le détail à mettre en avant en français. Si le modèle ne répond pas, le moteur affiche son explication.
- **Bilan avec NAB** est disponible en fin de partie et depuis son rejeu. NAB sélectionne jusqu’à trois tours à revoir. Alternez entre le coup joué et la proposition pour comparer leurs scores immédiats sur le même plateau. **Expliquer la comparaison** s’appuie sur les données du moteur et fonctionne hors ligne.
- **Rejouer mes coups manqués** ouvre la position et le chevalet du tour sélectionné dans le bilan. Ce bouton apparaît quand NAB a trouvé un placement qui marque plus de points et que le journal contient le chevalet du tour. Jouez votre propre tentative ou affichez la proposition. Les exercices ne modifient ni la partie ni l’Elo.

Le jeu valide automatiquement les placements avec Grammalecte 7.7. Les scores affichés par le moteur Android sont liés à cette édition du lexique. Le niveau Maître applique les poids sélectionnés lors de l’entraînement du moteur Mac pour le même lexique. Le générateur de coups Android et ses cotes locales sont propres à l’application.

SmolLM2 135M Instruct quantifié en 4 bits tourne sur le processeur via llama.cpp. Le premier commentaire prépare une copie des poids dans le stockage privé de l’application. L’APK pèse environ 94 Mo et cette copie utilise environ 92 Mo supplémentaires. Le modèle reçoit les faits vérifiés par le moteur. Les explications restent disponibles en mode avion et n’affectent pas l’Elo. [Modèle, empreinte et licences](app/src/main/assets/SmolLM2-NOTICE.md)

En solo, le bilan se concentre sur vos tours. Les variantes sont calculées à partir du plateau et du chevalet du tour, avec le lexique de la partie. Le classement privilégie les plus grands écarts de points immédiats parmi les placements trouvés. Pour les parties Bluetooth et les anciens journaux sans chevalets, le bilan peut expliquer les coups visibles.

## Compiler sur le Mac

Installer le SDK Android 36, le NDK `28.2.13676358`, Java 17, Python 3, CMake 3.22 ou plus récent et Ninja. Le premier build récupère les dépendances Gradle, le modèle et la source de llama.cpp. Le script vérifie les empreintes des deux téléchargements et compile le moteur d’inférence pour ARM et x86 en 32 et 64 bits.

```sh
./android/scripts/build.sh
```

Sur un poste où les dépendances sont en cache, `LETTRINE_GRADLE_OFFLINE=1` compile sans téléchargement. `ANDROID_NDK_HOME` permet de sélectionner le NDK. Pour tester un autre lexique autorisé, définir `LETTRINE_WORDS` avec un chemin de fichier UTF-8 contenant un mot par ligne et `LETTRINE_WORDS_LABEL` avec le nom de l’édition. Les poids entraînés du bot s’activent si leur empreinte correspond au lexique de l’APK.

Les livrables du dépôt compilent sans AdMob. Une variante distincte permet de tester une vidéo facultative qui ouvre un défi supplémentaire. [Configuration et récompenses](../docs/ECONOMIE_ANDROID.md) · [Simulations du moteur Android](../docs/SIMULATIONS_ANDROID.md)

## Préparer la bêta Google Play

```sh
./android/scripts/build.sh --bundle
bundletool validate --bundle=android/artifacts/lettrine.aab
```

Ce build produit un [Android App Bundle signé](artifacts/lettrine.aab) pour `fr.lettrine.game`. Sur le Mac de publication, il utilise la clé d’envoi Lettrine stockée dans `~/.android/lettrine`. Sur un autre poste, `LETTRINE_PLAY_KEYSTORE`, `LETTRINE_PLAY_PASSWORD_FILE` et `LETTRINE_PLAY_KEY_ALIAS` permettent de retrouver la même clé. Le mot de passe se lit depuis un fichier privé. Conserver une sauvegarde sécurisée des éléments de signature en dehors du dépôt.

Dans la Play Console, créer la version sur le canal de test souhaité, configurer Play App Signing au premier envoi, puis importer le bundle. Le certificat SHA-256 de la clé d’envoi est `6F:7A:E3:53:5D:E5:FA:76:CE:43:0D:C2:C6:44:7B:B6:8F:6B:8F:FF:DB:75:10:24:52:90:7E:99:DF:0E:1E:2A`. Les APK distribués par Play porteront le certificat de signature de l’application affiché par la console. Pour passer de l’APK du dépôt à la version Play, exporter d’abord l’historique puis désinstaller l’APK direct. Android exige la même signature pour une mise à jour en place. La série et la partie en cours sont locales à l’installation. [Signature Play](https://support.google.com/googleplay/android-developer/answer/9842756) · [Préparation d’une version](https://support.google.com/googleplay/android-developer/answer/9859348)

## Amis et duels en bêta

Le service des amis est séparé du studio. Pour le démarrer sur le serveur de la bêta, exécuter `docker compose -f compose.social.yaml up -d --build`. La base sociale est stockée dans le volume `social_data`. Publier le service derrière un domaine HTTPS en adaptant [l’exemple Nginx](../infra/social-nginx.conf.example). Le proxy limite les inscriptions anonymes et les appels, et expose seulement `/api/social/`. Les joueurs d’un même groupe renseignent la même origine HTTPS dans **Amis → Serveur des amis**. `LETTRINE_SOCIAL_URL` permet de fournir cette origine par défaut lors de la compilation des deux livrables. Le lien public d’amitié mène à la [page d’invitation](https://nabz0r.github.io/scrabnble/amis/) et peut aussi être collé dans l’onglet Amis.

Le compte social invité suffit pour jouer en ligne. Pour proposer la connexion Google, créer un client OAuth Web et des clients Android pour `fr.lettrine.game` avec les certificats des distributions à tester. Passer l’identifiant du client Web au serveur avec `LETTRINE_GOOGLE_CLIENT_ID`. Le serveur valide la signature Google, le destinataire du jeton et le nonce avant d’associer le compte. Le profil local, les parties hors ligne et l’Elo restent sur l’appareil. La connexion Google peut restaurer le compte social sur un autre appareil. [Connexion Google sur Android](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation) · [Validation du jeton Google](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token)

## Vérification de la version 0.2.0

- Android 8 et 16 sur émulateur. Mise à jour depuis la version 0.1.0, conservation de la partie en cours et de l’historique, inférence en mode avion en partie et en relecture. L’inférence a aussi été vérifiée sur Android 16 avec des pages mémoire de 16 Kio.
- Parties complètes contre le bot adaptatif, recalcul de l’Elo, rejeu et export JSON via le sélecteur de fichiers Android. Une explication laisse la partie cotée. Une aide au placement la conserve dans l’historique sans recalculer l’Elo.
- Le Bluetooth a été testé entre deux émulateurs Android 16 lors de la vérification de la version 0.1.0. Le test sur deux téléphones physiques reste à faire.

## Vérification de la version 0.3.0

- Sur émulateurs Android 8 et 16, la mise à jour depuis la 0.2.0 a conservé la partie en cours et les archives. Deux parties complètes contre NAB Adaptatif ont validé le recalcul de la cote et l’ouverture du bilan à la fin.
- En mode avion, les trois tours du bilan s’ouvrent et alternent entre la pose jouée et sa variante. L’explication embarquée, les points de la courbe d’Elo, le rejeu d’une ancienne partie et l’export JSON ont été vérifiés.

## Vérification de la version 0.4.0

- En mode avion sur une installation neuve d’Android 16, le premier défi a pris 16,3 secondes entre le bouton d’accueil et le résultat. Une pose invalide, l’affichage volontaire de la solution et la possibilité de réessayer ont aussi été vérifiés.
- Sur émulateurs Android 8 et 16, la mise à jour depuis la 0.3.0 a conservé les sauvegardes à l’identique. Retenter un tour archivé restaure le plateau et le chevalet d’origine, puis rend le même tour du bilan sans modifier les sauvegardes. Les journaux sans chevalet, Bluetooth ou issus d’un autre lexique masquent le bouton d’exercice.
- En mode avion, une partie contre NAB Adaptatif a validé le recalcul de l’Elo et l’accès au bilan. La courbe, la relecture, l’export JSON et l’explication par le modèle embarqué ont aussi été vérifiés.

## Vérification de la version 0.5.0

- En mode avion sur Android 16, deux dates consécutives ont affiché des positions différentes. Le record s’améliore au rejeu et la série avance une fois par jour. Une journée manquée rompt la série. Un défi ouvert avant un changement de date reste associé au jour où il a été ouvert.
- Le partage Android affiche la date, le record et la série. Afficher seulement la proposition de NAB ne crée aucun score. Les 19 tests Android couvrent notamment quatre années de positions jouables avec le lexique embarqué.
- La mise à jour depuis la 0.4.0 a conservé les fichiers de partie à l’identique sur émulateurs Android 8 et 16. La partie contre NAB Adaptatif, la courbe d’Elo, le rejeu et l’export JSON ont été vérifiés. L’interface du défi a été contrôlée à 320 dp avec une taille de texte à 160 %.

## Vérification de la version 0.6.0

- La mise à jour directe depuis la 0.5.0 a conservé les archives sur émulateurs Android 8 et 16. Une application de test a reçu la carte PNG de 1 080 × 1 080 pixels et son lien, avec un accès en lecture limité à la carte partagée.
- Depuis Chrome Android, la page HTTPS et son bouton **Ouvrir dans Lettrine** ont lancé la position attendue, avec le plateau et le chevalet du défi. En mode avion, l’invitation a fonctionné au démarrage et quand Lettrine était déjà ouverte. Les cas de victoire, d’égalité, de score inférieur et d’invitation incompatible ont été vérifiés.
- Les 22 tests Android passent. Le bundle signé a été validé avec `bundletool`. Les APK générés depuis le bundle se sont installés sur Android 16 avec des pages de 16 Kio. Le défi, le partage et l’inférence du modèle embarqué ont fonctionné en mode avion.

## Vérification de la version 0.7.0

- Les 26 tests Android et les 19 tests Rust passent. Le serveur de test a conservé l’amitié et le duel en cours après redémarrage. L’API a été vérifiée pour l’invitation à usage unique, la légalité des coups, les résultats, le classement, la revanche et le blocage.
- Sur Android 16, Chrome a ouvert un lien public d’amitié jusqu’à la confirmation dans Lettrine, avec un serveur de test. Deux appareils ont joué à des moments différents. Le résultat de 17 à 15 est apparu dans le classement et la revanche a ouvert une nouvelle position. Le profil local a été vérifié en mode avion, notamment la validation de l’âge, l’import et le retrait de la photo, et la persistance après redémarrage.
- Le bundle signé a été validé avec `bundletool` et les APK qui en sont issus ont été installés sur Android 16 avec des pages de 16 Kio. Le défi du jour et le partage de carte fonctionnent toujours en mode avion. La connexion à un compte Google réel requiert la configuration OAuth du projet.

## Vérification de la version 0.8.0

- Deux invités amis ont terminé les trois défis sur émulateurs Android 16 avec le serveur de test. Le classement est passé à 33 et 31 points, a survécu à la mise à jour et a réapparu après reconnexion. Le serveur de production refuse les coups avant l’ouverture. Les tests couvrent aussi la clôture, les égalités et les envois répétés.
- En mode avion, trois défis quotidiens joués ont débloqué Porcelaine et Marée. Des parties solo archivées ont débloqué Ambre et Ardoise. Les deux sélections se combinent sur le plateau et le chevalet et persistent après redémarrage.
- Le tutoriel a été terminé avec 11 points sur Android 8 en mode avion. Le zoom avant et arrière, la remise à 100 %, le glissement depuis le chevalet, le déplacement d’un jeton posé et les retours de validité ont été vérifiés. Les données de partie existantes ont été conservées.

## Vérification de la version 0.9.0

- 480 parties moteur Android et 30 défis du jour simulés avec le lexique embarqué. Les 28 tests Android passent. En mode avion sur Android 8, une partie complète contre l’Adaptatif a validé le glissement, le score, le calcul Elo, le bilan et l’inférence embarquée.
- Sur Android 16 en mode avion, le solo et la partie locale ont gardé des tours indépendants. Le brouillon a survécu au changement de partie et au redémarrage. L’abandon d’une partie a conservé les autres, la sauvegarde d’origine et l’historique.
- Le bundle est signé et validé avec `bundletool`. L’installation des APK issus du bundle et le tutoriel en mode avion ont été vérifiés sur Android 16 avec des pages de 16 Kio.
- La variante de démonstration AdMob a présenté les choix UMP et une vidéo de test Google. La récompense a ouvert le défi après un redémarrage en mode avion, sans effet sur les fichiers de partie et la progression quotidienne.

## Vérification de la version 0.10.0

- Deux comptes invités ont terminé un duel et sa revanche sur le même plateau avec leurs chevalets échangés. Deux demandes simultanées retrouvent la même revanche. La migration SQLite a conservé un duel de la 0.9 et sa revanche classique.
- Le fond Nocturne dorée est resté verrouillé jusqu’au troisième défi validé. Il est resté accessible dans la Collection après redémarrage en mode avion. Le partage a généré une carte de 1 080 × 1 080 pixels avec le fond débloqué.
- La mise à jour directe sur Android 8 a conservé les fichiers des parties et de l’historique. Les 29 tests Android et les 24 tests Rust passent. Le scénario Rust ignoré par défaut lance le serveur local utilisé pour les essais du tournoi sur émulateur.

L’APK joue en français avec le moteur embarqué. Les salons web et l’Elo du studio sont accessibles dans le navigateur. L’application conserve son historique et son Elo sur le téléphone et transfère les parties au studio par export JSON.
