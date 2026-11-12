# Lettrine pour Android

L’APK de test est disponible dans `artifacts/lettrine.apk`. Il fonctionne hors ligne à partir d’Android 8 (API 26). Il est signé pour une installation directe. Pour une distribution sur Google Play, compiler une version de production avec une clé de publication.

## Jouer

- **Entre amis** accueille deux à quatre joueurs sur le même appareil. Le chevalet est masqué à chaque changement de joueur. Les parties solo et locales en cours sont sauvegardées après chaque tour.
- **Bluetooth** relie deux appareils préalablement associés dans Android. L’un choisit **Héberger** et l’autre choisit l’appareil hôte. Les deux APK doivent embarquer le même lexique. Une coupure interrompt la partie.
- **Contre un bot** propose quatre niveaux et un niveau adaptatif. Celui-ci lit l’Elo local et ajuste sa difficulté pendant la partie en fonction de l’écart de score. Les parties avec aide au placement restent dans l’historique et sont exclues de l’Elo.
- Toucher une lettre puis une case pour la poser, ou la faire glisser. Pincer pour zoomer. Les réglages donnent accès à trois thèmes et à l’ambiance sonore.
- **Historique** présente les résultats, les statistiques et la relecture coup par coup. Exporter une partie ou tout l’historique en JSON, puis déposer le fichier dans l’administration du studio Lettrine.

Le jeu valide automatiquement les placements avec Grammalecte 7.7. Les scores affichés par le moteur Android sont liés à cette édition du lexique. Le niveau Maître applique les poids sélectionnés lors de l’entraînement du moteur Mac pour le même lexique. Le générateur de coups Android et ses cotes locales sont propres à l’application.

## Compiler sur le Mac

Installer le SDK Android 36, les outils de compilation Android, Java 17 et Python 3. Le lanceur Gradle est fourni et récupère les dépendances au premier lancement.

```sh
./android/scripts/build.sh
```

Sur un poste avec les dépendances en cache, `LETTRINE_GRADLE_OFFLINE=1` active le mode hors ligne. Pour tester un autre lexique autorisé, définir `LETTRINE_WORDS` avec un chemin de fichier UTF-8 contenant un mot par ligne et `LETTRINE_WORDS_LABEL` avec le nom de l’édition. Les poids entraînés s’activent uniquement si leur empreinte correspond au lexique de l’APK.

Installation et jeu vérifiés sur Android 8 et Android 16. L’appairage et la synchronisation de coups Bluetooth ont été vérifiés entre deux émulateurs Android 16. Le test sur deux téléphones physiques reste à faire.
