# Lettrine pour Android

L’[APK 0.6.0](artifacts/lettrine.apk) fonctionne hors ligne à partir d’Android 8 (API 26). Il contient le modèle d’explication des coups et porte la même signature que l’APK précédent pour permettre une mise à jour directe. Le [bundle 0.6.0](artifacts/lettrine.aab) est destiné à l’importation dans la Play Console.

## Jouer

- **Défi du jour** propose une nouvelle position selon la date locale, même en mode avion. Validez un coup légal pour compter la journée dans votre série. Vous pouvez rejouer pour améliorer le record du jour.
- **Bats mon score** partage une carte PNG avec la date, le score et, lorsqu’elle s’applique, la série. Le lien associé mène à une [page d’invitation](https://nabz0r.github.io/scrabnble/defi/) avec le bouton **Ouvrir dans Lettrine**. L’application retrouve le même plateau et le même chevalet, puis compare le coup du destinataire au score partagé. **Copier le lien** est disponible pour les messageries qui séparent les images du texte. Une invitation pour une autre date se joue hors série.
- **Premier défi** ouvre une position courte depuis l’accueil. Prolongez le mot sur le plateau, validez votre pose, puis comparez votre score à la proposition de NAB. Vous pouvez retenter le défi à tout moment.
- **Entre amis** accueille deux à quatre joueurs sur le même appareil. Le chevalet est masqué à chaque changement de joueur. Les parties solo et locales en cours sont sauvegardées après chaque tour.
- **Bluetooth** relie deux appareils préalablement associés dans Android. L’un choisit **Héberger** et l’autre choisit l’appareil hôte. Les deux APK doivent embarquer le même lexique. Une coupure interrompt la partie.
- **Contre un bot** propose quatre niveaux et un niveau adaptatif. Celui-ci lit l’Elo local et ajuste sa difficulté pendant la partie en fonction de l’écart de score. Les parties avec aide au placement restent dans l’historique et sont exclues de l’Elo.
- Toucher une lettre puis une case pour la poser, ou la faire glisser. Pincer pour zoomer. Les réglages donnent accès à trois thèmes et à l’ambiance sonore.
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

## Préparer la bêta Google Play

```sh
./android/scripts/build.sh --bundle
bundletool validate --bundle=android/artifacts/lettrine.aab
```

Ce build produit un [Android App Bundle signé](artifacts/lettrine.aab) pour `fr.lettrine.game`. Sur le Mac de publication, il utilise la clé d’envoi Lettrine stockée dans `~/.android/lettrine`. Sur un autre poste, `LETTRINE_PLAY_KEYSTORE`, `LETTRINE_PLAY_PASSWORD_FILE` et `LETTRINE_PLAY_KEY_ALIAS` permettent de retrouver la même clé. Le mot de passe se lit depuis un fichier privé. Conserver une sauvegarde sécurisée des éléments de signature en dehors du dépôt.

Dans la Play Console, créer la version sur le canal de test souhaité, configurer Play App Signing au premier envoi, puis importer le bundle. Le certificat SHA-256 de la clé d’envoi est `6F:7A:E3:53:5D:E5:FA:76:CE:43:0D:C2:C6:44:7B:B6:8F:6B:8F:FF:DB:75:10:24:52:90:7E:99:DF:0E:1E:2A`. Les APK distribués par Play porteront le certificat de signature de l’application affiché par la console. Pour passer de l’APK du dépôt à la version Play, exporter d’abord l’historique puis désinstaller l’APK direct. Android exige la même signature pour une mise à jour en place. La série et la partie en cours sont locales à l’installation. [Signature Play](https://support.google.com/googleplay/android-developer/answer/9842756) · [Préparation d’une version](https://support.google.com/googleplay/android-developer/answer/9859348)

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

L’APK joue en français avec le moteur embarqué. Les salons web et l’Elo du studio sont accessibles dans le navigateur. L’application conserve son historique et son Elo sur le téléphone et transfère les parties au studio par export JSON.
