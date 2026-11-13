# Résultats du laboratoire français

Campagne terminée le 12 novembre 2026 sur le Mac. Le moteur `fr-classic-v1` a joué **100 000 duels complets**, soit **2 754 615 tours**. PostgreSQL conserve chaque partie avec sa graine, les placements, les jokers, le chevalet avant chaque coup et le décompte final. Les 10 paires de niveaux ont chacune joué 10 000 duels. Entre niveaux différents, chacun commence 5 000 fois. Chaque duel utilise une graine distincte à partir de `20261112`.

La campagne utilise **Grammalecte 7.7**, soit 406 312 formes de 2 à 15 lettres après filtrage, dédoublonnage et normalisation. L’identifiant du moteur et l’empreinte du lexique figurent dans le manifeste. [Construction du lexique](../data/fr/README.md) · [Règles FISF classique, septembre 2026](https://www.ffsc.fr/files/public/fichiers/reglements/classique/Reglement.international.du.Scrabble.classique.pdf)

## Écart de niveau

| Niveau | Apparitions | Score moyen | Victoires | Elo relatif |
|---|---:|---:|---:|---:|
| Découverte | 50 000 | 294,9 | 23,9 % | 748 |
| Initié | 50 000 | 346,8 | 38,8 % | 1 016 |
| Stratège | 50 000 | 434,1 | 62,0 % | 1 409 |
| Expert | 50 000 | 494,8 | 74,8 % | 1 627 |

L’Elo est ajusté sur les résultats entre niveaux différents avec Bradley–Terry, une moyenne fixée à 1 200 et une demi-victoire pour une nulle. Il s’applique à ces quatre politiques et à ce lexique. Expert gagne 77,12 % de ses duels face à Stratège et 99,79 % face à Découverte. Ces taux dépendent du classement des placements par chaque bot. Les profils humains et les cotes FISF suivent un autre étalonnage.

## Le rythme des parties

Duels entre bots de même niveau, 10 000 par ligne. Un bingo pose sept jetons en un tour et rapporte une prime de 50 points. Les points par tour comptent les passes et les échanges à zéro, avant les ajustements de fin de partie.

| Duel | Tours par partie | Jetons par placement | Points par tour | Bingos par tour | Chevalet vidé |
|---|---:|---:|---:|---:|---:|
| Découverte × Découverte | 33,73 | 3,05 | 18,76 | 1,96 % | 89,31 % |
| Initié × Initié | 30,13 | 3,37 | 23,62 | 4,27 % | 95,24 % |
| Stratège × Stratège | 25,22 | 3,97 | 33,49 | 11,07 % | 99,28 % |
| Expert × Expert | 22,68 | 4,38 | 41,21 | 18,74 % | 99,90 % |

Toutes paires confondues, le score moyen par joueur est de 392,65 points, avec un maximum de 897. Le premier siège gagne 52,491 % des duels, le second 47,268 %. Les 241 parties restantes finissent à égalité. Les simulations comptent 228 188 bingos, 97 011 fins par épuisement du chevalet et 2 989 fins après six passes. Les bots n’échangent que lorsqu’ils n’ont aucun placement et que le sac le permet. Le taux d’échange de 0,003 % reflète ce choix de politique.

## Effet du lexique

La liste FFSc ODS 9 compte 81 formes de deux lettres. Grammalecte en retient 149 après préparation. Les listes partagent 74 formes, soit 91,36 % des formes FFSc. Il manque sept formes de la liste FFSc et 75 formes sont propres à Grammalecte dans cette comparaison. Dans les simulations, `KO` et `KM` apparaissent respectivement 5 752 et 5 313 fois comme mots principaux. Ces deux formes sont absentes de la liste FFSc. Les écarts touchent donc des raccords courants et modifient la fréquence des ouvertures, les scores et l’efficacité des reliquats. Le comparateur permet de chiffrer l’écart sur toutes les longueurs lorsqu’un fichier ODS autorisé est disponible. [Mots de deux lettres FFSc](https://www.ffsc.fr/initiation.php?page=mots-2-lettres) · [Lexique Grammalecte](https://www.grammalecte.net/#telechargement)

## Travailler les parties fortes

Les **10 000 duels Expert × Expert** ont leur filtre et leur export JSONL. Le rejeu affiche le plateau au tour choisi, le score du coup et le chevalet. L’analyse propose des placements alternatifs, mesure l’écart au meilleur score immédiat et évalue les répliques en échantillonnant les lettres inconnues. Un placement de plus faible score peut garder un meilleur reliquat, fermer un raccord ou limiter le prochain coup adverse.

Les archives humaines fournissent quatre parties de compétition et 108 tours documentés, avec leurs références et le lexique d’époque. Sur 94 placements dont l’archive fournit le meilleur score immédiat, 32 égalent ce score, 62 rapportent moins et 28 laissent au moins dix points immédiats. Au tour 3 de la première manche de la finale 2015, Schélick Ilagou Rekawe joue `AISEE` pour 9 contre un maximum de 26. Le placement bloque des raccords en S et garde un reliquat moins chargé en voyelles. En finale 2025, Quentin Mallégol décrit comment `HO` ferme deux possibilités de bingo et laisse une lettre dans le sac. Ces exemples donnent des positions de test pour la valeur du reliquat et la défense du plateau. [Corpus et détails](../data/expert/README.md) · [Finale 2015, manche 1](https://www.crds.fr/menus/classique/classique.php?ref=150720CHMRF1) · [Scrabblerama n° 513](https://www.ffsc.fr/mafiche.exporter.scrabblerama.php?numero=513)

### NAB Android sur les mêmes positions

Le banc rejoue les 108 tours documentés et présente au niveau Maître le plateau, le chevalet du joueur et le nombre de tuiles dans le sac. Il compare 79 placements avec le lexique Grammalecte de l’APK. NAB retrouve exactement 23 coups. En points immédiats, il marque plus sur 48 positions, autant sur 26 et moins sur 5. Le banc écarte 16 placements qui forment au moins un mot absent de Grammalecte, un écart de score, 11 échanges et une tentative invalidée. [Script de mesure](../tools/android_gameplay/benchmark_experts.py)

```sh
python3 tools/android_gameplay/benchmark_experts.py
```

Les archives utilisaient ODS 6 à 8. Les coups proposés par NAB sont vérifiés avec Grammalecte. Les points mesurent le gain du tour sur un plateau donné. L’étalonnage humain demande des duels complets contre des joueurs cotés avec une édition du lexique, un règlement et une cadence communs. Il faut figer la version de NAB, alterner le premier joueur et conserver les journaux, puis estimer la cote avec un intervalle d’incertitude. [Protocole du studio](ENTRAINEMENT.md)

## Vérification

La copie PostgreSQL contient 100 000 identifiants et 100 000 graines distinctes. Un contrôle indépendant a reconstruit 992 parties réparties sur toute la campagne, soit 27 399 tours et 27 134 placements. Il a vérifié les mots principaux et croisés, le centre, les cases bonus, les jokers, la cohérence des chevalets et du sac, le calcul des points et les ajustements de fin.

La partie nº 100 000 donne un exemple de contrôle manuel. Le premier joueur ouvre avec `LYCEE` pour 34 points, puis vide son chevalet au tour 21. Le reliquat adverse `GGO` vaut 5 points. Les scores passent de 520–463 à 525–458.


## Expert entraîné et sélection par modèle

L’Expert entraîné obtient 51,93 % des points de match sur 12 000 duels réservés face à l’Expert de référence. L’intervalle estimé est de 51,27 à 52,58 %. L’écart moyen est de +6,28 points et le gain relatif de +13 Elo. [Modèle et essais](../data/models/fr-bots-v1.json)

| Modèle Ollama | Adversaire | V–N–D | Δ Elo pilote | Choix valides |
|---|---|---:|---:|---:|
| `llama3.2:3b` | Expert entraîné | 0–0–12 | −559 | 146 |
| `qwen3:0.6b` | Expert entraîné | 1–0–11 | −354 | 145 |

Chaque pilote réutilise six tirages dans les deux sièges, avec le même lexique et le même adversaire. Chaque modèle choisit parmi les six meilleurs placements présélectionnés par le moteur. Les écarts Elo sont régularisés et l’incertitude est large. Ces résultats décrivent le banc pilote, avec son prompt et son budget. Llama reste le modèle de commentaire du studio et Qwen Embedding le modèle d’indexation. [Données des pilotes](../data/models/llm-benchmarks.json)

## Index des mots, ouvertures et cases bonus

Le studio agrège les placements en PostgreSQL par profil de langue. L’index utilise le mot principal inscrit dans le journal de simulation. Les raccords sont compris dans le score total du coup et ne sont pas comptés comme des occurrences supplémentaires. Une ouverture est le premier placement de la partie, même si un échange ou une passe l’a précédé. Un bingo correspond au nombre de jetons posés prévu par le profil.

| Profil | Parties | Tours | Placements | Mots distincts | Bingos |
|---|---:|---:|---:|---:|---:|
| Français | 100 000 | 2 754 615 | 2 727 121 | 106 131 | 228 188 |
| Anglais | 12 000 | 338 708 | 332 530 | 41 044 | 14 773 |
| Allemand | 8 000 | 231 136 | 226 050 | 44 754 | 12 205 |
| Russe | 8 000 | 257 045 | 248 079 | 49 881 | 7 019 |

En français, un placement qui active au moins une case bonus rapporte 30,98 points en moyenne. La moyenne des autres placements est de 11,63. Les coups qui activent une case mot triple rapportent 37,47 points en moyenne. Le score comprend le placement complet, ses raccords et l’éventuelle prime de bingo. L’écart observé dépend aussi du choix du bot, du chevalet et de la géométrie du plateau. Il mesure une association et ne permet pas d’isoler les points dus au multiplicateur.

La carte compte les jetons nouvellement placés dans chaque case. La moyenne d’une case utilise le score du coup complet à chacune de ses activations. Un coup peut donc contribuer aux statistiques de plusieurs cases et de plusieurs types de bonus. La fréquence des actions utilise l’ensemble des tours. Le taux de bingos de l’index utilise les placements seuls.

```bash
python3 tools/index_moves.py
python3 tools/index_moves.py --profile en-wespa-v1 --db results/en-wespa-v1.sqlite
python3 tools/index_moves.py --profile de-sdev-v1 --db results/de-sdev-v1.sqlite
python3 tools/index_moves.py --profile ru-mattel-v1 --db results/ru-mattel-v1.sqlite
```

Le premier exemple lit l’archive française de référence. Une archive d’une autre langue se lit avec `--profile`, `--archive` et `--metadata`. Les enregistrements de provenance contiennent le profil, la graine, le nom de la source et l’empreinte du lexique. Relancer l’index remplace les agrégats du profil sélectionné dans une transaction. [Script d’indexation](../tools/index_moves.py) · [Schéma](../infra/move_analytics.sql)

## Réplication, recherche avancée et probabilités

Le vérificateur indépendant a relu les 100 000 duels français et recalculé les 2 754 615 tours. Il contrôle les placements, les mots croisés, les primes et les scores de fin. L’Expert entraîné a ensuite obtenu 52,08 % des points sur une réplication séparée de 100 000 duels contre l’Expert de référence, en 43,1 secondes sur le Mac. [Vérification](../data/campaign/verification-fr.json) · [Réplication](../data/models/reference-holdout-fr.json)

La recherche avancée de N A B échantillonne uniquement les lettres inconnues et peut résoudre exactement des fins de partie lorsque le sac est vide. Sur 2 000 duels français réservés contre l’Expert entraîné, elle obtient 55,58 % des points, avec un intervalle de 53,79 à 57,36 %. Ce test compare deux politiques simulées. L’Elo humain demande des adversaires communs dans le même lexique. [Rapport de recherche avancée](../data/models/peak-fr-v1.json)

La réplication du 14 novembre 2026 a joué 4 000 parties complètes sur 2 000 nouveaux tirages, dans les deux ordres de départ. Avec le profil classique du laboratoire et Grammalecte 7.7 pour les deux moteurs, NAB Recherche avancée obtient 2 235 victoires, 10 nulles et 1 755 défaites contre l’Expert entraîné. Cela donne 56,00 % des points de match (intervalle à 95 % de 54,73 à 57,27 %), un écart moyen de +16,61 points et un Elo relatif de +42 (environ +33 à +51). La cadence et les contestations du règlement FISF ne sont pas simulées. La [campagne du moteur Android](SIMULATIONS_ANDROID.md#réplication-sur-1-440-parties-complètes) est distincte.

```sh
cargo run --release --locked -- benchmark-peak 2000 results/nab-simulation-fr-2026-11-14.json 984110000
```

Le [banc classique NAB contre Macondo](SIMULATION_FISF.md) rejoue des duels complets contre un moteur externe avec une pendule et un arbitre logiciel.

Les courbes de rejeu estiment les chances à partir des écarts de points observés dans les duels à niveau égal, du stade de partie et de l’écart Elo entre références. La calibration est vérifiée sur un lot réservé par langue. Les jetons piochés sont reconstruits depuis les chevalets enregistrés. La probabilité de tirer un joker utilise l’inventaire inconnu avant le coup. [Calibrage](../data/models/win-model.json)
