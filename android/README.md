# Lettrine pour Android

L’[APK 0.2.0](artifacts/lettrine.apk) fonctionne hors ligne à partir d’Android 8 (API 26). Il contient le modèle d’explication des coups et porte la même signature que l’APK précédent pour permettre une mise à jour directe.

## Jouer

- **Entre amis** accueille deux à quatre joueurs sur le même appareil. Le chevalet est masqué à chaque changement de joueur. Les parties solo et locales en cours sont sauvegardées après chaque tour.
- **Bluetooth** relie deux appareils préalablement associés dans Android. L’un choisit **Héberger** et l’autre choisit l’appareil hôte. Les deux APK doivent embarquer le même lexique. Une coupure interrompt la partie.
- **Contre un bot** propose quatre niveaux et un niveau adaptatif. Celui-ci lit l’Elo local et ajuste sa difficulté pendant la partie en fonction de l’écart de score. Les parties avec aide au placement restent dans l’historique et sont exclues de l’Elo.
- Toucher une lettre puis une case pour la poser, ou la faire glisser. Pincer pour zoomer. Les réglages donnent accès à trois thèmes et à l’ambiance sonore.
- **Historique** présente les résultats, les statistiques et la relecture coup par coup. Exporter une partie ou tout l’historique en JSON, puis déposer le fichier dans l’administration du studio Lettrine.
- **Expliquer le dernier coup** s’affiche dans une partie après le premier tour. En relecture, **Expliquer ce coup** s’applique au tour affiché. Les scores, mots croisés, cases bonus et jokers proviennent du moteur. Le modèle embarqué choisit le détail à mettre en avant en français. Si le modèle ne répond pas, le moteur affiche son explication.

Le jeu valide automatiquement les placements avec Grammalecte 7.7. Les scores affichés par le moteur Android sont liés à cette édition du lexique. Le niveau Maître applique les poids sélectionnés lors de l’entraînement du moteur Mac pour le même lexique. Le générateur de coups Android et ses cotes locales sont propres à l’application.

SmolLM2 135M Instruct quantifié en 4 bits tourne sur le processeur via llama.cpp. Le premier commentaire prépare une copie des poids dans le stockage privé de l’application. L’APK pèse environ 95 Mo et cette copie utilise environ 92 Mo supplémentaires. Les explications utilisent les données publiques d’un coup déjà joué. Elles restent disponibles en mode avion et n’affectent pas l’Elo. [Modèle, empreinte et licences](app/src/main/assets/SmolLM2-NOTICE.md)

## Compiler sur le Mac

Installer le SDK Android 36, le NDK `28.2.13676358`, Java 17, Python 3, CMake 3.22 ou plus récent et Ninja. Le premier build récupère les dépendances Gradle, le modèle et la source de llama.cpp. Le script vérifie les empreintes des deux téléchargements et compile le moteur d’inférence pour ARM et x86 en 32 et 64 bits.

```sh
./android/scripts/build.sh
```

Sur un poste où les dépendances sont en cache, `LETTRINE_GRADLE_OFFLINE=1` compile sans téléchargement. `ANDROID_NDK_HOME` permet de sélectionner le NDK. Pour tester un autre lexique autorisé, définir `LETTRINE_WORDS` avec un chemin de fichier UTF-8 contenant un mot par ligne et `LETTRINE_WORDS_LABEL` avec le nom de l’édition. Les poids entraînés du bot s’activent si leur empreinte correspond au lexique de l’APK.

## Vérification de la version 0.2.0

- Android 8 et 16 sur émulateur. Mise à jour depuis la version 0.1.0, conservation de la partie en cours et de l’historique, inférence en mode avion en partie et en relecture. L’inférence a aussi été vérifiée sur Android 16 avec des pages mémoire de 16 Kio.
- Parties complètes contre le bot adaptatif, recalcul de l’Elo, rejeu et export JSON via le sélecteur de fichiers Android. Une explication laisse la partie cotée. Une aide au placement la conserve dans l’historique sans recalculer l’Elo.
- Le Bluetooth a été testé entre deux émulateurs Android 16 lors de la vérification de la version 0.1.0. Le test sur deux téléphones physiques reste à faire.

L’APK joue en français avec le moteur embarqué. Les salons web et l’Elo du studio sont accessibles dans le navigateur. L’application conserve son historique et son Elo sur le téléphone et transfère les parties au studio par export JSON.
