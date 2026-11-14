# Profils de jeu et langues

Un profil associe le plateau, le sac, le décompte, les conditions de fin, la normalisation et un lexique. L’identifiant du profil et l’empreinte du lexique doivent accompagner chaque campagne. Les règles, les cotes et les statistiques se comparent à profil et lexique identiques.

| Profil | État | Jetons | Chevalet | Arrêt sans pose |
|---|---|---:|---:|---|
| [fr-classic-v1](../data/profiles/fr-classic-v1.json) | Français, profil par défaut | 102 | 7 | 3 passes consécutives par joueur |
| [en-wespa-v1](../data/profiles/en-wespa-v1.json) | Anglais, ENABLE2K fourni | 100 | 7 | 6 tours à zéro au total |
| [ru-mattel-v1](../data/profiles/ru-mattel-v1.json) | Russe, OpenRussian fourni | 104 | 7 | 2 passes consécutives par joueur |
| [de-sdev-v1](../data/profiles/de-sdev-v1.json) | Allemand, ENZ fourni | 102 | 7 | 2 passes consécutives par joueur |
| [ja-nihongo-v0](../data/profiles/ja-nihongo-v0.json) | Japonais, variante documentée | À vérifier | 8 au départ | À vérifier |

## Français classique

La FISF publie le règlement applicable depuis le 1er septembre 2026. En duel, l’échange demande au moins sept jetons dans le sac. Trois passes consécutives de chaque joueur déclenchent la fin à six passes. Si un joueur vide le chevalet quand le sac est vide, il reçoit la valeur des lettres adverses, qui est aussi déduite du score adverse. À trois ou quatre joueurs, Lettrine applique trois tours de table de passes. La validation des mots est automatique. [Règlement FISF](https://www.ffsc.fr/files/public/fichiers/reglements/classique/Reglement.international.du.Scrabble.classique.pdf) · [Table officielle des jetons français](https://fisf.net/wp-content/uploads/2025/08/GOA-ODS6-Septembre2011.pdf)

Le profil fournit Grammalecte 7.7 sous MPL-2.0. Sur le plateau, les accents sont neutralisés et les ligatures `Œ` et `Æ` deviennent deux lettres. Les mots avec ponctuation interne sont filtrés. L’ODS 9 reste la référence des compétitions francophones et se charge à partir d’un fichier local dont l’utilisateur dispose selon les conditions de l’éditeur. [Lexique français et comparateur](../data/fr/README.md) · [Questions ODS, FISF](https://fisf.net/lofficiel-du-scrabble/questions-reponses/)

Sur Android 0.16.0, les nouvelles parties classiques françaises en solo et sur le même appareil utilisent **Grammalecte 7.7 · Classique**. Ce sous-ensemble retire 258 formes exclusivement marquées comme symboles, sigles ou abréviations typographiques. Il a sa propre empreinte. Les campagnes du studio, les défis, les duels en ligne, le Bluetooth et les parties déjà commencées utilisent Grammalecte 7.7 d’origine. Le jeu avec une liste importée reste accessible dans **Profil → Réglages → Lexique personnel**. [Méthode et exclusions](../data/fr/README.md#lexique-des-nouvelles-parties-classiques-sur-android) · [Guide Android](../android/README.md#jouer-avec-un-lexique-personnel)

## Anglais international et Amérique du Nord

Le profil anglais prend le duel WESPA v5.1 comme référence. Il reprend le sac de 100 jetons, la prime de 50 pour sept jetons, l’échange si le sac en contient au moins sept et six tours à zéro, y compris les passes et les échanges. À l’épuisement, il applique le décompte recommandé par WESPA. Le joueur sortant gagne le double de la valeur des lettres adverses et le score adverse reste fixe. Le règlement accepte aussi la méthode de transfert simple, qui donne le même écart de score en duel. La validation dans le moteur est automatique. [WESPA Game Rules v5.1, §§3.2 et 5](https://www.wespa.org/wp-content/uploads/2024/09/Rules-V5.1.pdf) · [Notice Hasbro](https://instructions.hasbro.com/en-us/instruction/scrabble-board-game)

WESPA utilise CSW24 depuis janvier 2025. La NASPA publie un règlement distinct pour le Canada et les États-Unis et emploie NWL2023 depuis février 2024. Ces listes se chargent séparément avec les droits appropriés. Le laboratoire fournit ENABLE2K, liste du domaine public, avec 169 266 formes, son nom et son empreinte propres. [Kit WESPA CSW24](https://wespa.org/docs/WESPA_Initiation_Kit_2024.pdf) · [Règlement NASPA](https://www.scrabbleplayers.org/w/Tournament_rules) · [NWL2023 et conditions développeurs](https://blog.scrabbleplayers.org/w/NWL2023) · [Notice d’ENABLE2K](https://github.com/BartMassey/wordlists/blob/main/README-enable2k.txt)

## Russe classique Mattel

Le profil reprend l’édition russe du livret Mattel 51284-0627 (2004) et les 104 jetons photographiés d’un coffret Spear. Il préserve `Е` et `Ё` comme jetons distincts, avec huit `Е` à un point et un `Ё` à trois points. Le plateau fait 15 × 15, la case centrale double le premier mot, le chevalet contient sept jetons et la prime de pose complète vaut 50. Les règles de boîte permettent de piocher des remplaçants avant de remettre les jetons défaussés si le sac suffit. Le livret distingue une variante de tournoi qui exige sept jetons dans le sac. Deux tours complets de passes mettent fin à la partie. Le profil fixe ces règles de boîte, avec validation automatique. [Notice originale Mattel](https://gaga.ru/gaga/files/pdf/rules/ru/24-327307.pdf) · [Photographie du sac complet](https://www.gtoal.com/wordgames/details/russian/spears-russian-tiles2.jpg)

Le livret demande de convenir d’un dictionnaire avant la partie. Lettrine fournit 540 468 formes issues des sauvegardes OpenRussian, sous CC BY-SA 4.0. La préparation élimine les lemmes à capitale initiale, les formes ponctuées et les marques de transcription de l’accent tonique. Le manifeste décrit la source, la licence et la transformation. [Lexique russe et empreintes](../data/ru/README.md)

## Allemand classique SDeV

Le profil reprend le plateau de 15 × 15 et le sac allemand de 102 jetons, avec `Ä`, `Ö` et `Ü` distincts. L’eszett se joue avec `SS`. Le chevalet tient sept jetons, la prime de pose complète est de 50 points et le sac doit contenir au moins sept jetons pour un échange. Deux tours de table de passes mettent fin à la partie. En cas de sortie, le reliquat est transféré au joueur qui termine. [Règles classiques SDeV](https://scrabble-info.de/wp-content/uploads/2016/03/Allgemeine-Scrabble-Spielregeln.pdf) · [ORZ allemand 2026](https://scrabble-info.de/wp-content/uploads/2026/03/ORZ-20260315.pdf)

Le laboratoire fournit ENZ, une liste de jeux de mots sous CC0, avec 590 897 formes normalisées. Les choix de vocabulaire du projet ENZ diffèrent de ceux de la liste officielle SDeV. [Lexique allemand et empreintes](../data/de/README.md) · [Liste officielle SDeV](https://scrabble-info.de/woerterliste/)

## Japonais

L’association にほんごスクラブル協会 décrit un jeu qui commence avec huit jetons hiragana. Les joueurs trouvent des homophones écrits en kanji. La longueur de la lecture détermine les points, doublés si le joueur a trouvé la forme sans consulter le dictionnaire. Le jeu cite le dictionnaire Kōjien. Il demande donc un lien entre lectures et graphies et un état qui distingue les phases de recherche. La distribution exacte du sac, le détail du plateau et les conditions de fin demandent un règlement complet. Le profil reste au stade de la documentation. Les compétitions annoncées par le Tokyo Scrabble Club utilisent l’anglais CSW24. [Entretien avec le secrétariat de l’association](https://kikutoko.org/post/201) · [Tokyo Scrabble Club](https://tokyoscrabble.club/)

JMdict fournit des lectures en kana associées à des graphies et des sens. C’est une source possible pour un prototype en japonais, avec attribution et partage à l’identique selon la licence EDRDG. L’équivalence avec le vocabulaire du Kōjien resterait à mesurer. [Données JMdict](https://www.edrdg.org/dokuwiki/doku.php?do=&id=wiki_index.php_jmdict-edict_dictionary_project) · [Licence EDRDG](https://www.edrdg.org/edrdg/newlic.html)

## Lancer un profil classique

Le laboratoire joue des duels. Chaque profil charge son lexique fourni par défaut. Utiliser une base de campagne distincte. Pour substituer un lexique autorisé, définir `LETTRINE_WORDS` vers un fichier UTF-8 avec une forme normalisée par ligne et `LETTRINE_LEXICON` avec son nom exact. L’anglais utilise A à Z, l’allemand ajoute `Ä`, `Ö` et `Ü`, et le russe utilise son alphabet cyrillique avec `Ё` distinct de `Е`.

```sh
LETTRINE_PROFILE=en-wespa-v1 \
LETTRINE_DB=results/anglais.sqlite \
LETTRINE_GAMES=1000 \
cargo run --release -- run
```

Le répertoire `data/profiles` contient les fiches JSON, que l’API expose à `/api/profiles`. Sur Android, **EN / FR** change la langue des menus et du jeu. Les parties et la progression sont propres à chaque langue. Le moteur du laboratoire prend en charge les plateaux classiques 15 × 15 et un symbole Unicode par jeton. Le cyrillique et les umlauts allemands conservent leurs propres caractères. Les digrammes sur un jeton, les kana combinés et les mécaniques de variantes nécessitent une segmentation et un moteur qui les représentent explicitement.

## Ajouter un profil

1. Choisir une édition ou un règlement précis. Archiver les références, la date, le plateau, la distribution, les points et les règles de fin.
2. Publier un JSON sous un identifiant versionné avec les jetons dans un ordre stable. Le premier identifiant représente le joker de valeur zéro. Ne pas réutiliser un identifiant lorsqu’une règle, un barème ou un ordre de jetons change.
3. Construire le lexique dans un processus reproductible. Conserver la provenance, les conditions d’usage, le nombre de formes, leur empreinte et les exclusions.
4. Vérifier le premier coup, les mots croisés, les jokers, les primes, les échanges et les fins avec des positions propres au profil. Contrôler le même rejeu depuis l’export.
5. Mesurer l’équilibre et recalibrer les bots sur ce profil. Les cotes francophones de la campagne de référence ne constituent pas des cotes anglaises ou russes.
