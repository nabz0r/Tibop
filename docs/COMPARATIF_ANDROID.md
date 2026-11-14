# Jeux de lettres Android

Relevé du 12 novembre 2026 sur Google Play et dans les aides des éditeurs. Les paliers de téléchargement concernent chaque application Android. Ils indiquent son volume cumulé par tranche. Les modes ci-dessous sont documentés par les éditeurs.

| Jeu | Google Play | Joueurs, modes et difficulté |
| --- | ---: | --- |
| Wordscapes | 100 M+ | Casse-têtes en solo, jouables hors connexion. Plus de 6 000 grilles annoncées, difficulté progressive et essais illimités. Cinq langues dont le français. [Google Play](https://play.google.com/store/apps/details?hl=fr&id=com.peoplefun.wordcross) |
| Scrabble GO | 10 M+ | Classique en duel, jusqu’à quatre joueurs en ligne et Duels rapides. Entraînement solo contre Zoey à cinq niveaux. Historique, zoom et personnalisation des jetons. Scopely indique l’absence de mode à tour de rôle sur le même appareil. L’aide répertorie le Larousse 2019 pour le français. [Google Play](https://play.google.com/store/apps/details?hl=fr&id=com.pieyel.scrabble) · [Multijoueur](https://scopely.helpshift.com/hc/en/28-scrabble-go/faq/6134-what-is-multiplayer/) · [Aide](https://scopely.helpshift.com/hc/en/28-scrabble-go/section/1671-new-player-guide/) · [Dictionnaires](https://scopely.helpshift.com/hc/en/28-scrabble-go/faq/5383-what-scrabble-word-dictionaries-are-available-1701425775/) |
| Wordfeud | 10 M+ | Duels asynchrones entre amis ou avec un adversaire aléatoire. Jusqu’à 30 parties en parallèle, délai de 72 h par tour, plateau standard ou primes réparties aléatoirement. Dix langues dont le français. Statistiques dans l’édition Premium. Le bonus de sept lettres vaut 40 points. [Google Play](https://play.google.com/store/apps/details?hl=fr&id=com.hbwares.wordfeud.free) · [Règles](https://wordfeud.com/wfweb/help/) · [Premium](https://play.google.com/store/apps/details?hl=fr&id=com.hbwares.wordfeud.full) |
| Words With Friends | 10 M+ | Parties entre amis ou via Smart Match. Progression en solo contre les WordMasters, mini-jeu d’anagrammes Word Wheel et styles de jetons. La fiche ne chiffre pas les paliers de difficulté du solo. [Google Play](https://play.google.com/store/apps/details?hl=fr&id=com.zynga.words3) |
| Classic Words | 10 M+ | Solo hors ligne contre l’ordinateur, six niveaux, définitions intégrées et sept langues. L’édition Plus affiche 100 k+ téléchargements et ajoute le jeu à deux sur le même appareil. [Google Play](https://play.google.com/store/apps/details?hl=fr&id=com.lulo.scrabble.classicwords) · [Édition Plus](https://play.google.com/store/apps/details?hl=fr&id=com.lulo.scrabble.classicwordsplus) |
| NYT Crossplay | | Duels au tour par tour entre amis, bot Cross Bot et classements. [Google Play](https://play.google.com/store/apps/details?id=com.nytimes.wordgame) |

## Application à WordRivet 0.16.0

- **Prise en main.** La partie complète mène l’accueil. Le premier bouton reprend une partie ou ouvre le choix du niveau de NAB. Le jeu local permet de passer le téléphone à deux, trois ou quatre en masquant le chevalet au changement de joueur. [Classic Words Plus](https://play.google.com/store/apps/details?hl=fr&id=com.lulo.scrabble.classicwordsplus) · [WordRivet](../android/README.md#jouer)
- **Plateau.** WordRivet affiche le score avant validation, montre le motif de refus et permet de réordonner le chevalet à la main pour préparer un mot. Les nouvelles parties françaises en solo et sur le même appareil filtrent les sigles, symboles et abréviations typographiques. [Aide Scrabble GO](https://scopely.helpshift.com/hc/en/28-scrabble-go/section/1671-new-player-guide/) · [Lexique classique](../data/fr/README.md#lexique-des-nouvelles-parties-classiques-sur-android)
- **Ambiance.** Wordscapes associe une direction visuelle calme à une progression à son rythme. WordRivet transpose ce principe au jeu de plateau avec des palettes et des skins, deux compositions originales et un réglage indépendant des sons de tuiles. [Google Play](https://play.google.com/store/apps/details?hl=fr&id=com.peoplefun.wordcross)
- **Progression.** Les niveaux fixes de Classic Words et les adversaires progressifs de Zynga rendent l’apprentissage lisible. WordRivet combine quatre niveaux fixes avec un bot qui lit l’Elo local, ajuste sa difficulté pendant le match et écarte les parties assistées du calcul de la cote. [Classic Words](https://play.google.com/store/apps/details?hl=fr&id=com.lulo.scrabble.classicwords) · [Words With Friends](https://play.google.com/store/apps/details?hl=fr&id=com.zynga.words3)
- **Après la partie.** WordRivet montre une tactique qui rapporte des points, permet de rejouer la position avec son chevalet et relance le même mode depuis le résultat. L’historique conserve le bilan détaillé et l’export. [Bilan Android](../android/README.md#jouer)

## Où gagner la préférence du joueur

| Référence | Parcours WordRivet | Mesure à relever en bêta |
| --- | --- | --- |
| Classic Words et Scrabble GO, le jeu de plateau | Accès direct à la partie classique, tri manuel du chevalet, zoom et motif de refus d’un coup. Promeneur est présélectionné en solo. | Premier coup validé, première partie terminée, deuxième partie complète à J+7 |
| Wordscapes, une première grille immédiatement jouable | Défi du jour directement accessible depuis l’accueil | Temps avant le premier coup et passage du défi vers une partie classique |
| Wordfeud, Words With Friends et Crossplay, les rendez-vous entre amis | Lien vers le même défi, score partageable et revanche à son rythme | Partages qui ouvrent un défi, défis ouverts qui sont terminés, invitations qui aboutissent à un duel |
| Scrabble GO, collection et modes compétitifs | Skins gagnés en jouant, tournoi du week-end et bilan NAB utilisable hors ligne | Part des joueurs qui rejouent un coup du bilan et lancent une autre partie |

Le comparatif porte sur les modes documentés par les éditeurs. Les mesures de rythme et de difficulté disponibles pour WordRivet proviennent de ses [simulations Android](SIMULATIONS_ANDROID.md). Les téléchargements du tableau sont des cumuls par application.
