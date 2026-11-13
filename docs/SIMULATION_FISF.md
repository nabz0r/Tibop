# NAB contre Macondo en classique

NAB Recherche avancée affronte [Macondo v0.13.9](https://github.com/domino14/macondo/tree/v0.13.9) dans un processus local. Macondo génère ses coups avec son propre moteur et choisit le meilleur score immédiat. Lorsqu’il ne trouve aucun placement, il échange si le règlement le permet, en gardant les jokers et les S. Chaque graine fournit deux parties complètes sur le même tirage initial, avec inversion du siège de NAB.

Le laboratoire construit le graphe des mots à partir d’une seule liste UTF-8 de formes jouables. Les deux moteurs et l’arbitre emploient ce graphe. Le rapport indique le nom déclaré du lexique, le nombre de formes normalisées, le SHA-256 du fichier d’origine, le SHA-256 du moteur externe et les paramètres de NAB. Le modèle entraîné s’applique lorsque l’empreinte correspond à celle de son lexique d’entraînement. Pour un autre lexique, le banc utilise la politique Expert de base avec la même recherche avancée.

Le laboratoire applique les règles de jeu du Scrabble classique FISF de septembre 2026. Chaque moteur dispose de 20 minutes pour décider de ses coups. La durée de décision est mesurée avec une horloge monotone et arrondie à la milliseconde supérieure. Elle inclut la communication avec Macondo. À expiration, le moteur passe et les échanges deviennent indisponibles pour les deux sièges. Le journal conserve les temps par tour et les points de reliquat. Le banc conteste automatiquement les mots hors lexique avant la pioche lorsque la fenêtre réglementaire le permet. Les deux moteurs génèrent leurs coups avec la même liste, donc les contestations restent exceptionnelles. Des scénarios de test vérifient aussi les pénalités de contestation à tort et la fenêtre du dernier coup. [Règlement FISF, sections 2.4, 3.3 à 3.5 et 5](https://www.ffsc.fr/files/public/fichiers/reglements/classique/Reglement.international.du.Scrabble.classique.pdf)

Un moteur Macondo qui ne rend pas de réponse dans son temps restant augmenté de cinq secondes interrompt la campagne. Le journal partiel permet de diagnostiquer le problème. Le rapport est écrit après la dernière partie. Une réponse reçue pendant ces cinq secondes donne une passe pour temps épuisé.

## Lancer en local avec Docker

```sh
mkdir -p results
export LETTRINE_LEXICON='Grammalecte 7.7'
export LETTRINE_FISF_WORDS="$PWD/data/fr/words.txt"
docker compose -f compose.fisf.yaml build benchmark
docker compose -f compose.fisf.yaml run --rm benchmark \
  benchmark-fisf 500 984230000 results/nab-macondo.json results/nab-macondo.jsonl
python3 tools/verify_fisf.py "$LETTRINE_FISF_WORDS" \
  results/nab-macondo.json results/nab-macondo.jsonl
```

Le service s’exécute sans réseau. Il monte le fichier de mots en lecture seule et écrit le rapport et le journal dans le dossier local `results/`. Chaque lancement demande deux nouveaux chemins de sortie. Le contrôle indépendant reconstruit les placements, les mots croisés, l’inventaire des lettres, les points et les temps à partir du journal. La construction récupère les sources des dépendances épinglées dans `go.mod` et `Cargo.lock`. Le code de Macondo est sous GPL-3.0. [Sources Macondo](https://github.com/domino14/macondo/tree/v0.13.9) · [Adaptateur](../tools/fisf_reference/main.go)

Pour un examen sur ODS 9, indiquez dans `LETTRINE_FISF_WORDS` un fichier intégral que vous êtes autorisé à utiliser, avec une forme jouable par ligne, puis indiquez `LETTRINE_LEXICON='ODS 9'`. Laissez ce fichier et les journaux de la campagne hors du dépôt. La publication d’une application qui distribue la base ODS suit les conditions du titulaire des droits. [FAQ officielle de la FISF](https://fisf.net/lofficiel-du-scrabble/questions-reponses/)

## Android

Dans WordRivet 0.15.1, **Profil → Réglages → Lexique personnel** accepte une liste autorisée pour les parties hors ligne. L’écran affiche le nombre de formes importées et permet de copier l’empreinte de la liste normalisée. Les parties d’entraînement restent associées à cette empreinte. Le banc avec la pendule et l’arbitrage tourne dans le laboratoire. [APK](../android/artifacts/lettrine.apk)
