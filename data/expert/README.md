# Parties classiques de compétition

`sample.json` regroupe quatre parties d’archives avec 108 tours documentés, dont 96 placements, 11 échanges et une tentative à zéro sans coordonnée. Les références de Trois-Rivières 2025 et de Tunis 2026 fournissent en plus les scores officiels de dix manches de finale. Pour 2025, l’entretien de Quentin Mallégol fournit aussi le placement défensif `HO` en `H1` pour 17 points lors de la cinquième manche. [Comité Dauphiné-Savoie](https://www.scrabblecrds.fr/index.php?PG=9) · [Palmarès FISF](https://fisf.net/competitions/scrabble-classique/championnat-de-monde/) · [Entretien avec Mallégol, Scrabblerama n° 513](https://www.ffsc.fr/mafiche.exporter.scrabblerama.php?numero=513)

## Reproduire l’import

```sh
python3 tools/expert_archive.py
```

On peut sélectionner une autre partie dont la référence figure dans l’archive et écrire vers un fichier distinct :

```sh
python3 tools/expert_archive.py 160721CHPMP1 --output /tmp/partie-experte.json
```

L’import conserve les faits de jeu et le meilleur score immédiat affiché par l’archive. Les coordonnées `H8` désignent un placement horizontal et les coordonnées `8H` un placement vertical. `?` représente un joker sur le chevalet. `blank_offsets` désigne les positions de joker indiquées en minuscules dans le mot d’origine, à partir de zéro. L’édition ODS est celle qui s’appliquait à la date de la partie. Les parties historiques gardent leur lexique et leurs règles d’époque. [Historique de l’ODS](https://fisf.net/lofficiel-du-scrabble/)

Pour les quatre parties retenues, `final_scores` reprend les scores officiels de la FISF dans l’ordre des joueurs. `archive_score_totals` additionne les valeurs enregistrées coup par coup et les ajustements de fin. En 2016, le total d’archive d’Abib Alabi est 411 pour un score officiel de 413. En 2021, celui d’Eric Salvador Tchouyo est 450 pour un score officiel de 441. Le drapeau `score_discrepancy` signale ces deux écarts. Le détail des manches 2025 et 2026 reste dans les retransmissions, sauf le placement de Mallégol signalé dans l’entretien. [Palmarès FISF](https://fisf.net/competitions/scrabble-classique/championnat-de-monde/) · [Trois-Rivières 2025](https://ffsc.fr/endirect/endirect.php?tournoi_id=25341) · [Tunis 2026](https://ffsc.fr/endirect/endirect.php?tournoi_id=25971)

## Premières positions à examiner

Sur 94 placements comparables, 32 égalent le meilleur score immédiat affiché, 62 rapportent moins et 28 cèdent au moins dix points. Ces comptes décrivent les quatre parties retenues. Ils mesurent le rendement immédiat. Les exemples suivants permettent d’évaluer des raisons de conserver des lettres, de fermer des appuis ou de jouer un tour supplémentaire.

- **Louvain-la-Neuve 2015, manche 1, tour 3.** Schélick Ilagou Rekawe joue `AISEE` en `F6` pour 9 contre un maximum affiché de 26. Il ferme des raccords en S et évite un reliquat très riche en voyelles. Dans la même partie, Richards choisit `CHUM` en `E6` pour 22 contre un maximum de 40. Son reliquat contient le Q, dont le coût stratégique dépend des ouvertures restantes. [Rejouer la manche 1](https://www.crds.fr/menus/classique/classique.php?ref=150720CHMRF1)
- **Louvain-la-Neuve 2015, manche 3, tour 16.** Richards marque 55 avec `EX` en `L11` et garde son joker, alors que l’archive trouve un placement à 86 en le dépensant. Cette position permet de tester la valeur du joker et du reliquat quand le joueur mène. [Rejouer la manche 3](https://www.crds.fr/menus/classique/classique.php?ref=150720CHMRF3)
- **Trois-Rivières 2025, manche 5.** Quentin Mallégol décrit son choix de `HO` en `H1` pour 17 points. Ce placement ferme les appuis du C et du S, bloque deux scrabbles dont disposait son adversaire sur le C et laisse une lettre dans le sac. Le récit donne aussi une autre défense à examiner, `MORTS`. [Entretien avec Mallégol, Scrabblerama n° 513](https://www.ffsc.fr/mafiche.exporter.scrabblerama.php?numero=513)
