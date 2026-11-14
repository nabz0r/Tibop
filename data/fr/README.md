# Lexique français du laboratoire

Le moteur utilise `fr-grammalecte-7.7-v1`, une liste de 406 312 formes jouables issue de Grammalecte 7.7. Les mots sont classés par fréquence décroissante, puis dans l’ordre alphabétique en cas d’égalité. La fréquence mesure l’usage dans les corpus de Grammalecte. Elle ne mesure pas la valeur stratégique d’un coup. [Lexique Grammalecte](https://www.grammalecte.net/#telechargement)

## Reproduction et licence

`words.txt` dérive du lexique Grammalecte 7.7 par Olivier R. et les contributeurs de Dicollecte. Le fichier dérivé est distribué sous la licence Mozilla Public License 2.0. Le texte intégral se trouve dans [MPL-2.0.txt](MPL-2.0.txt). L’archive d’origine et le générateur ci-dessous donnent accès à la forme source du lexique. [Archive originale](https://www.grammalecte.net/dic/lexique-grammalecte-fr-v7.7.zip) · [Licence MPL 2.0](https://www.mozilla.org/en-US/MPL/2.0/)

Depuis la racine du projet, avec Python 3 :

```sh
python3 tools/lexicon.py build
```

L’option `--archive` permet d’utiliser une copie locale de l’archive. Le générateur vérifie son empreinte, écarte les formes avec majuscule, les noms propres signalés, les catégories non lexicales, les graphies archaïques et les extensions expérimentales. Il conserve les variantes orthographiques modernes des sous-dictionnaires classique et réformé. Il replie les accents et les ligatures françaises, garde les chaînes de 2 à 15 lettres A–Z et élimine les doublons en gardant la fréquence maximale. Les formes composées avec espace, tiret ou apostrophe restent hors liste. Le manifeste associe au résultat une empreinte SHA-256 et les effectifs par longueur.

## Lexique des nouvelles parties classiques sur Android

Le profil `fr-grammalecte-7.7-classic-v1` retire 258 formes de la liste du laboratoire, dont 71 mots de deux lettres. Il conserve 406 054 formes, dont 78 de deux lettres. La colonne `Notes` de Grammalecte identifie les symboles (`symb`), les abréviations typographiques (`abty`) et les sigles (`sig`). Une forme reste jouable lorsqu’une autre entrée lui donne un sens lexical. BU reste ainsi jouable comme participe de « boire » et PI comme lettre grecque. Les troncations lexicalisées comme VÉLO et PHOTO restent également jouables.

Trois exceptions conservent un sens lexical absent des entrées retenues par le générateur. BA désigne une composante de la personne dans l’Égypte ancienne, DAL un plat indien et IN signifie « à la mode ». [BA dans la liste de la FFSc](https://www.ffsc.fr/initiation.php?page=mots-2-lettres) · [DAL dans Le Robert](https://dictionnaire.lerobert.com/definition/dal) · [IN dans Larousse](https://www.larousse.fr/dictionnaires/francais/in/42086)

La génération Android utilise la liste et les exclusions du dépôt. Pour contrôler la provenance des exclusions, comparez-les avec celles recalculées à partir de l’archive originale.

```sh
python3 tools/lexicon.py classic --output /tmp/wordrivet-classic
python3 tools/lexicon.py classic-exclusions --archive /chemin/vers/grammalecte-7.7.zip | diff - data/fr/classic-exclusions.txt
```

Le profil alimente aussi les nouveaux défis français et les nouveaux duels en ligne entre joueurs compatibles. Le serveur filtre les mots formés avec le même fichier d’exclusions. Les anciennes activités gardent leur lexique et leurs positions.

## Officiel du Scrabble et comparaison locale

La 9e édition de l’Officiel du Scrabble (ODS 9) sert de référence aux compétitions francophones depuis le 1er janvier 2024. Larousse indique environ 69 000 entrées et fournit les indications nécessaires pour obtenir les formes fléchies admissibles. La FISF annonce l’ODS 10 pour le 1er janvier 2028 en compétition. La FISF indique que Larousse exploite les droits patrimoniaux de l’ODS et demande un accord préalable écrit pour diffuser des extraits. [FAQ de la FISF](https://fisf.net/lofficiel-du-scrabble/questions-reponses/) · [Éditions Larousse](https://www.editions-larousse.fr/livre/lofficiel-du-scrabble-9e-ed-9782036026377/)

Le lexique du laboratoire contient 149 mots de deux lettres et produit des parties propres à cette sélection. Le chevauchement avec l’ODS et les résultats qu’il produirait restent à mesurer sur une liste locale autorisée et complète de ses formes fléchies. Le comparateur accepte des fichiers UTF-8 contenant un mot par ligne. Il affiche les effectifs, l’intersection et la couverture globale et par longueur, dont celle des mots de deux lettres. Le rapport contient uniquement des nombres.

```sh
python3 tools/lexicon.py compare /chemin/prive/ods9.txt data/fr/words.txt
```

## Règles de référence

- Plateau de 15 × 15, premier mot d’au moins deux lettres couvrant la case centrale. Les coups suivants se raccordent à la grille. Toutes les suites nouvelles dans les deux sens comptent.
- Le chevalet accueille jusqu’à sept jetons. Le sac français en compte 102, dont deux jokers. La lettre représentée par un joker est choisie à la pose et sa valeur reste nulle.
- Les primes de lettre et de mot s’appliquent à leur première occupation, dans chaque mot concerné. Poser sept jetons donne 50 points de bonification.
- À partir de sept jetons en réserve, un échange consomme le tour. On pioche avant de remettre les jetons écartés dans le sac.
- Sac vide et chevalet vidé, les points des reliquats sont retirés aux adversaires et ajoutés au joueur qui a fini. En cas de blocage, chaque joueur perd la valeur de son reliquat. Le règlement de tournoi à deux prévoit aussi une fin après six passes consécutives.

Le règlement de tournoi de la FISF est conçu pour le duel, avec chronomètre et contestation. Le jeu familial original prévoit de deux à quatre joueurs. Une application qui valide les mots automatiquement ou extrapole la règle des passes à trois ou quatre joueurs applique ses propres conventions de jeu. [Règlement classique international de la FISF, septembre 2026](https://www.ffsc.fr/files/public/fichiers/reglements/classique/Reglement.international.du.Scrabble.classique.pdf) · [Jeu familial français Mattel](https://service.mattel.com/us/productPopup.aspx?prodno=Y9593&siteid=27)
