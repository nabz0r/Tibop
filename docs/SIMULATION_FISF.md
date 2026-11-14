# NAB contre Macondo en classique

NAB Recherche avancée affronte Macondo v0.13.9 dans un processus local. Macondo génère ses coups avec son propre moteur et choisit le meilleur score immédiat. Lorsqu’il ne trouve aucun placement, il échange si le règlement le permet, en gardant les jokers et les S. Chaque graine fournit deux parties complètes sur le même tirage initial, avec inversion du siège de NAB. [Macondo v0.13.9](https://github.com/domino14/macondo/tree/v0.13.9)

Le laboratoire construit le graphe des mots à partir d’une seule liste UTF-8 de formes jouables. Les deux moteurs et l’arbitre emploient ce graphe. Le rapport indique le nom déclaré du lexique, le nombre de formes normalisées, le SHA-256 du fichier d’origine, le SHA-256 du moteur externe et les paramètres de NAB. Le modèle entraîné s’applique lorsque l’empreinte correspond à celle de son lexique d’entraînement. Pour un autre lexique, le banc utilise la politique Expert de base avec la même recherche avancée.

Le laboratoire applique les règles de jeu du Scrabble classique FISF de septembre 2026. Chaque moteur dispose de 20 minutes pour décider de ses coups. La durée de décision est mesurée avec une horloge monotone et arrondie à la milliseconde supérieure. Elle inclut la communication avec Macondo. À expiration, le moteur passe et les échanges deviennent indisponibles pour les deux sièges. Le journal conserve les temps par tour et les points de reliquat. Le banc conteste automatiquement les mots hors lexique avant la pioche lorsque la fenêtre réglementaire le permet. Les deux moteurs génèrent leurs coups avec la même liste, donc les contestations restent exceptionnelles. Des scénarios de test vérifient aussi les pénalités de contestation à tort et la fenêtre du dernier coup. [Règlement FISF, sections 2.4, 3.3 à 3.5 et 5](https://www.ffsc.fr/files/public/fichiers/reglements/classique/Reglement.international.du.Scrabble.classique.pdf)

Un moteur Macondo qui ne rend pas de réponse dans son temps restant augmenté de cinq secondes interrompt la campagne. Le journal partiel permet de diagnostiquer le problème. Le rapport est écrit après la dernière partie. Une réponse reçue pendant ces cinq secondes donne une passe pour temps épuisé.

Le taux de points de match compte chaque victoire pour un point et chaque nulle pour un demi-point. L’intervalle de confiance est calculé sur les paires de parties. L’écart Elo est calculé à partir du taux avec un lissage d’un demi-point. L’écart moyen en points de jeu utilise les scores finaux bruts. Cette mesure compare les deux politiques dans cette configuration.

## Répétition sur Grammalecte 7.7

Le 14 novembre 2026, NAB a joué 1 000 parties complètes sur 500 tirages appariés, à partir de la graine `984230000`. Les deux moteurs utilisaient les 406 312 formes de Grammalecte 7.7. NAB a obtenu 651 victoires, une nulle et 348 défaites, soit **65,15 % des points de match** (intervalle à 95 % de 59,33 à 70,97 %). L’écart est de **+109 Elo relatif à Macondo au meilleur score immédiat** et de +39,84 points bruts par partie.

Le contrôle indépendant a recalculé les 23 151 tours. Toutes les parties se sont terminées par épuisement d’un chevalet, sans expiration de pendule ni contestation. Les scénarios de test couvrent ces deux cas. La campagne sur ODS 9 attend une liste intégrale utilisable localement.

## Comparer NAB avant et après l’entraînement

Le protocole entraîne la politique Expert du moteur sur le lexique de jeu, puis garde la même recherche avancée pour les deux versions de NAB. La commande `train` évalue 20 réglages sur 60 000 parties contre l’Expert de base et contrôle le meilleur sur 12 000 autres parties internes. Elle écrit le modèle dans `results/`, séparément du modèle utilisé par le studio.

Pour chaque test contre Macondo, `LETTRINE_FISF_NAB_MODEL=base` sélectionne explicitement l’Expert de base. Un chemin vers un modèle sélectionne les poids entraînés après vérification de l’empreinte du lexique. Le rapport identifie le choix et indique l’empreinte du modèle lorsqu’il est chargé. La commande suivante suppose que le chemin du lexique est déjà configuré et que l’image a été construite.

```sh
lot=results/nab-entrainement
mkdir -p "$lot"
docker compose -f compose.fisf.yaml run --rm benchmark train "$lot/modele.json"
docker compose -f compose.fisf.yaml run --rm -e LETTRINE_FISF_NAB_MODEL=base benchmark \
  benchmark-fisf 500 984350000 "$lot/base.json" "$lot/base.jsonl"
docker compose -f compose.fisf.yaml run --rm -e LETTRINE_FISF_NAB_MODEL="/app/$lot/modele.json" benchmark \
  benchmark-fisf 500 984350000 "$lot/entraine.json" "$lot/entraine.jsonl"
python3 tools/verify_fisf.py "$LETTRINE_FISF_WORDS" "$lot/base.json" "$lot/base.jsonl"
python3 tools/verify_fisf.py "$LETTRINE_FISF_WORDS" "$lot/entraine.json" "$lot/entraine.jsonl"
python3 tools/compare_fisf_training.py "$lot/base.json" "$lot/base.jsonl" \
  "$lot/entraine.json" "$lot/entraine.jsonl" "$lot/comparaison.json"
```

Le comparateur vérifie les paramètres communs, les deux sièges et les chevalets initiaux pour chaque graine. Son estimation appariée donne le changement de points de match et de marge de score avec un intervalle d’incertitude. Les décisions peuvent changer les lettres piochées après le départ, même à partir du même sac. La campagne mesure si les réglages choisis contre l’Expert interne améliorent les résultats de NAB contre Macondo sur des graines réservées.

### Résultats sur Lettres en vrac

Le modèle a été sélectionné avec les 400 526 formes normalisées de Lettres en vrac. Il a retenu les mêmes poids que le précédent entraînement sur Grammalecte. Sur 12 000 parties internes réservées, il obtient 51,81 % des points de match (IC à 95 % de 51,17 à 52,45 %) contre l’Expert de base. [Sources et licence MPL 2.0](https://lettresenvrac.com/a-propos/)

| NAB contre Macondo au meilleur score immédiat | Victoires | Nulles | Défaites | Points de match | Elo relatif à Macondo |
|---|---:|---:|---:|---:|---:|
| De base | 650 | 1 | 349 | 65,05 % | +108 |
| Entraîné | 627 | 5 | 368 | 62,95 % | +92 |

Les deux versions ont joué avec la même recherche avancée sur 500 nouveaux tirages, dans chacun des deux sièges. L’écart apparié de la version entraînée est de −2,10 points de pourcentage (IC à 95 % de −4,63 à +0,43) et de −3,03 points de score par partie (IC de −7,92 à +1,87). Le gain mesuré contre l’Expert interne ne se retrouve pas dans l’estimation contre Macondo. L’intervalle apparié laisse ouverte la possibilité d’un faible gain.

Les 46 907 tours ont été vérifiés. La pendule de 20 minutes et les contestations étaient actives. Les deux campagnes se sont déroulées sans expiration ni contestation. Les journaux et le modèle sont conservés dans `results/nab-lettres-en-vrac-entrainement/`.

## Entraînement direct contre Macondo

Sur les 400 526 formes normalisées de Lettres en vrac (MPL 2.0), neuf réglages, dont la base et le réglage retenu par autojeu, ont disputé chacun 200 parties contre Macondo v0.13.9 au meilleur score immédiat. Les trois meilleurs réglages autres que la base et la base elle-même ont ensuite joué chacun 300 parties de plus. Les tirages étaient communs à tous les réglages d’un même tour, avec les deux ordres de départ. Les deux tours ont constitué 3 000 parties de sélection. Le classement privilégiait les points de match, puis la marge de score moyenne. [Lexique et licence MPL 2.0](https://lettresenvrac.com/a-propos/)

Le réglage retenu a marqué 67,50 % des points sur ses 500 parties de sélection, contre 65,00 % pour la base sur les mêmes tirages. Il porte de 12 à 22 le nombre de coups présélectionnés par l’Expert. Il conserve les poids de base, la comparaison de cinq variantes en recherche avancée et le même budget de résolution de fin de partie.

Le test final porte sur 500 tirages réservés à partir de la graine `984570000`. Chaque version de NAB a joué les deux sièges, soit 1 000 parties par version.

| NAB contre Macondo v0.13.9 au meilleur score immédiat | Victoires | Nulles | Défaites | Points de match | Marge moyenne | Elo relatif à Macondo |
|---|---:|---:|---:|---:|---:|---:|
| De base | 630 | 1 | 369 | 63,05 % | +38,27 | +93 |
| Entraîné directement | 626 | 1 | 373 | 62,65 % | +38,34 | +90 |

Sur les mêmes tirages, la variante entraînée obtient −0,40 point de pourcentage de points de match (IC à 95 % de −1,73 à +0,93) et +0,07 point de marge moyenne par partie (IC de −2,48 à +2,62). Le test réservé n’établit aucun gain de taux de victoire ni de marge. Le modèle reste expérimental et la base reste active.

Le contrôle indépendant a vérifié les 46 873 tours. La pendule de 20 minutes et l’arbitrage des contestations étaient actifs, sans expiration ni contestation. Les journaux complets restent dans `results/nab-lettres-en-vrac-macondo-direct/`. [Plan](../data/campaign/macondo-lettres-en-vrac/protocole.json) · [Sélection](../data/campaign/macondo-lettres-en-vrac/selection-macondo.json) · [Modèle expérimental](../data/campaign/macondo-lettres-en-vrac/modele-macondo.json) · [Rapport apparié](../data/campaign/macondo-lettres-en-vrac/comparaison.json)

Pour rejouer le test final, téléchargez la [liste source de Lettres en vrac](https://lettresenvrac.com/licences/mots.txt). Préparez-la avec `board_form` de `tools/lexicon.py`. Supprimez les lignes rejetées, dédoublonnez, triez et écrivez les 400 526 formes en ASCII avec un saut de ligne après chaque mot. L’empreinte du fichier de jeu doit être `4346fed90d914ecdf12db0eb8ffb0a760ced1330621f8673c8cfe3fe2a23d0dc`. Configurez `LETTRINE_LEXICON` et `LETTRINE_FISF_WORDS`, puis construisez l’image comme indiqué ci-dessous. Le chargement du modèle vérifie l’empreinte.

```sh
lot=results/replique-macondo
mkdir -p "$lot"
cp data/campaign/macondo-lettres-en-vrac/modele-macondo.json "$lot/modele.json"
docker compose -f compose.fisf.yaml run --rm -e LETTRINE_FISF_NAB_MODEL=base benchmark \
  benchmark-fisf 500 984570000 "$lot/base.json" "$lot/base.jsonl"
docker compose -f compose.fisf.yaml run --rm -e LETTRINE_FISF_NAB_MODEL="/app/$lot/modele.json" benchmark \
  benchmark-fisf 500 984570000 "$lot/entraine.json" "$lot/entraine.jsonl"
python3 tools/verify_fisf.py "$LETTRINE_FISF_WORDS" "$lot/base.json" "$lot/base.jsonl"
python3 tools/verify_fisf.py "$LETTRINE_FISF_WORDS" "$lot/entraine.json" "$lot/entraine.jsonl"
python3 tools/compare_fisf_training.py "$lot/base.json" "$lot/base.jsonl" \
  "$lot/entraine.json" "$lot/entraine.jsonl" "$lot/comparaison.json"
```

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

Dans WordRivet 0.15.2, **Profil → Réglages → Lexique personnel** accepte une liste autorisée pour les parties hors ligne. L’écran affiche le nombre de formes importées et permet de copier l’empreinte de la liste normalisée. Les parties d’entraînement restent associées à cette empreinte. Le banc avec la pendule et l’arbitrage tourne dans le laboratoire. [APK](../android/artifacts/lettrine.apk)

Le banc mesure la stratégie de jeu de NAB Recherche avancée. Sur Android, SmolLM2 135M Instruct sélectionne sur demande un détail de l’explication vérifiée par le moteur, comme les mots croisés, une case bonus ou un joker. L’essai sur deux versions de l’APK mesure l’utilité de ce choix pour expliquer le score et le délai de réponse en mode avion. [Essai dans l’APK](SIMULATIONS_ANDROID.md#vérifier-wordrivet-0152-sur-android) · [Modèle embarqué et licences](../android/app/src/main/assets/SmolLM2-NOTICE.md)
