# Simulations de parties Android

Les nouvelles parties classiques françaises utilisent le lexique filtré et les poids Maître de référence. Les campagnes historiques indiquent le lexique utilisé. [Empreintes et filtres](../data/fr/README.md#lexique-des-nouvelles-parties-classiques-sur-android)

## Vérification de Tibop 0.17.0

Les 94 tests unitaires Android passent. Lint se termine avec 0 erreur et 41 avertissements. Les tests du parcours jouent les douze niveaux et les trois finales de chaque langue avec les lexiques embarqués. Ils vérifient les objectifs, l’ouverture du niveau suivant et les trois agrandissements de l’atelier. Un coup valide qui manque l’objectif ne débloque pas le niveau suivant. La validation de la sauvegarde couvre les clés de progression et de brouillon des douze niveaux en français et en anglais.

Sur la première version candidate signée, les douze niveaux ont été terminés dans l’interface Android en français et en anglais. Le parcours a aussi vérifié une égalité avec NAB suivie d’un nouvel essai, la reprise après arrêt forcé et l’apparition des trois aménagements à mesure des victoires.

Sur l’émulateur Android 8, la mise à jour depuis l’APK public 0.16.7 a conservé une partie classique et son brouillon. Les quatre premiers niveaux ont été terminés et la table de jeu éclairée est apparue dans l’atelier. Sur l’émulateur Android 16, les niveaux français 5 et 8 ont été rejoués et le sélecteur compact du joker a permis de jouer NESTS au niveau anglais 6. Après effacement des données locales de test, l’import ZIP a restauré les douze niveaux anglais terminés et l’atelier aménagé.

Un essai automatisé a tiré des coups parmi les placements proposés par le moteur, avec 60 tentatives par niveau et par langue. En français, 719 des 720 tentatives sont allées au bout et 526 ont atteint leur objectif. L’autre tentative s’est arrêtée faute de second placement. En anglais, les 720 tentatives sont allées au bout et 565 ont atteint leur objectif. Le contrôle a comparé les scores de 1 919 placements à un calcul séparé par tour et vérifié que 1 800 placements invalides laissaient la position intacte. Ces résultats décrivent le comportement du moteur avec des coups tirés au hasard. La difficulté pour de nouveaux joueurs reste à mesurer sur téléphone.

Le contrôle de la partie classique a terminé 180 parties locales à deux, trois et quatre joueurs en français et en anglais. Les scores et la reprise des 6 057 tours ont été vérifiés. Le moteur a rejeté 6 284 actions volontairement invalides.

```sh
./tools/android_gameplay/run-multiplayer.py 30 2026111417 6
```

## Vérification de Tibop 0.16.7

Les 89 tests unitaires Android passent. Sur une position construite pour le test, le moteur refuse `OK` avec le lexique de base, accepte la pose avec `OK` ajouté à la liste d’entraînement, et NAB peut choisir ce coup. Les tests couvrent aussi la normalisation, l’empreinte des listes d’entraînement, les conditions de déblocage des apparences et la prise en charge du chevalet dans la sauvegarde de la Collection. Lint se termine avec 0 erreur et 40 avertissements.

```sh
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug
```

Sur émulateur Android 16, l’installation de la 0.16.7 par-dessus la 0.16.6 a conservé les 225 cases du plateau, le score de 5 à 12, le sac de 80 tuiles au tour 4, l’ordre du chevalet, le dernier coup `LE` et le brouillon `A`, `E`, `U` en E4–E6. En anglais à 240 dp de largeur et 145 % de taille de texte, la Collection a gardé les apparences sélectionnées.

En mode avion, `OK` a été ajouté avant le démarrage d’une partie locale à deux. Après retrait du mot et arrêt forcé, **Parties avec les anciennes listes** a permis de reprendre cette partie.

## Parties locales à deux, trois et quatre joueurs

Le moteur Java d’Android a terminé **1 200 parties** avec Grammalecte 7.7 · Classique en français et ENABLE2K en anglais. Chaque groupe compte 200 tirages. Un calcul indépendant a contrôlé les mots, les cases bonus, les points et la conservation des tuiles. Le test a sauvegardé et rechargé chacun des 37 935 tours et vérifié les ajustements de fin et les ex æquo. Les 39 708 actions volontairement invalides ont été refusées sans modification de l’état.

| Langue | Joueurs | Tours | Poses | Échanges | Passes | Chevalet vidé | Fin par passes ou tours sans points |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Français | 2 | 6 826 | 4 798 | 1 031 | 997 | 145 | 55 |
| Français | 3 | 6 260 | 4 343 | 906 | 1 011 | 153 | 47 |
| Français | 4 | 5 698 | 3 849 | 803 | 1 046 | 156 | 44 |
| Anglais | 2 | 6 845 | 4 803 | 1 054 | 988 | 141 | 59 |
| Anglais | 3 | 6 417 | 4 474 | 934 | 1 009 | 156 | 44 |
| Anglais | 4 | 5 889 | 4 029 | 802 | 1 058 | 156 | 44 |

```sh
./tools/android_gameplay/run-multiplayer.py 200 2026111403 7
```

En complément, six scénarios sur l’émulateur Android 8 ont vérifié la reprise avant le dernier tour, le chevalet vidé, les gagnants ex æquo et l’archivage unique. Un test de stockage concurrent a conservé 48 archives et les parties en cours.

### Vérification de Tibop 0.16.6

À partir de 100 graines rejouées dans chaque groupe, le moteur Android a terminé 600 parties à deux, trois et quatre joueurs en français et en anglais. Les 19 710 tours ont tous été sauvegardés et rechargés. Le contrôle a vérifié 1 073 jokers, 1 117 poses à sept lettres, 2 627 échanges et 8 parties à égalité. Il a refusé 20 564 actions invalides sans modifier l’état.

```sh
./tools/android_gameplay/run-multiplayer.py 100 2026111409 6
```

Quatre essais sur un serveur local isolé ont terminé des salons web à deux, trois et quatre humains et à deux humains avec deux bots. Chaque participant a retrouvé le même plateau et les mêmes scores, y compris après reconnexion. Les envois répétés d’un même tour ont été refusés, et le journal de rejeu est devenu accessible à la fin.

Sur Android 16, le passage de l’appareil au joueur suivant, la reprise du brouillon après arrêt forcé, le résultat et le bilan ont été contrôlés à deux, trois et quatre. Le chevalet à tour de rôle se masque aussi au passage en arrière-plan. Sur un écran de 298 dp de large, quatre joueurs tiennent sur deux lignes et le plateau et le chevalet défilent ensemble. Les noms restent visibles à 160 %.

Deux émulateurs Android 16 ont terminé un duel en ligne à 4–4 puis une revanche miroir à 8–8 au cumul, avec échange des chevalets. Une invitation a survécu à une panne temporaire et un coup déjà enregistré a été récupéré après perte de sa réponse. Une annulation adverse pendant l’attente ou le brouillon ramène désormais à une liste de duels à jour. En Bluetooth, deux coupures radio ont préservé le brouillon privé et une passe déjà enregistrée. Les deux émulateurs affichent le même résultat final. La reconnexion sur deux téléphones physiques reste à vérifier.

Sur Android 8 en mode avion, le défi quotidien a repris un brouillon, partagé son score et ouvert les invitations française et anglaise. Le circuit anglais a terminé ses trois défis à 66 points avec un seul ticket débité, malgré un arrêt forcé entre les deux coups. Dans le catalogue français, une première pose de SURMENE laissant un chevalet bloqué peut être reprise. À 320 dp et 145 % de taille de texte, le plateau du défi s’ouvre à 100 % et la page défile jusqu’à ses actions.

La boutique a conservé le solde après annulation d’un échange et redémarrage. Elle a rechargé un ticket en restant ouverte et crédité un seul ticket partagé entre les deux langues après une annonce AdMob de démonstration. La Collection a été contrôlée à 284 dp et 200 % de taille de texte. Les aperçus anglais utilisent les repères et les valeurs du lexique anglais.

## Duels en ligne et Bluetooth de la bêta 0.16.4

La campagne HTTP a terminé **656 duels**, dont 328 revanches miroir, sur les 164 plateaux français et les 164 plateaux anglais. Elle a recalculé les 1 312 poses, effectué 1 330 contrôles de renvoi ou de concurrence et provoqué dix pertes de réponse après enregistrement et dix redémarrages du serveur sur des bases isolées. Les invitations, les scores et les classements ont été vérifiés après la reprise. Les deux placements restent réservés aux participants et apparaissent après leurs deux coups. Une demande de revanche renvoyée après la fin retrouve aussi les placements du rejeu.

Sur Android 16, l’invitation a été ouverte pendant une panne et acceptée après relance. Un brouillon valide a été retrouvé après retour depuis le menu et après arrêt forcé. Lorsque la requête échoue avant l’enregistrement, le joueur peut renvoyer son brouillon. Lorsque la réponse se perd après l’enregistrement, l’application relit le duel et affiche le score enregistré. Le test vérifie aussi les invitations expirées, le classement, les menus de gestion d’amis, le partage et l’affichage des deux coups terminés.

Deux émulateurs Android 16 associés dans les réglages ont joué via Bluetooth classique. Une coupure radio a été provoquée pendant un brouillon puis après un passage enregistré. L’hôte a rouvert la connexion et l’invité a retrouvé le même plateau, son tour et son chevalet. Le brouillon du tour en cours est revenu et le passage enregistré n’a été compté qu’une fois. La reprise sur deux téléphones physiques reste à vérifier.

## Mesures du modèle embarqué

Les APK 0.15.1 et 0.15.2 ont rejoué les huit mêmes tours d’une partie française avec Grammalecte 7.7 sur un émulateur Android 16 arm64, en mode avion et sans réseau actif. La partie archivée fournit le plateau et le score attendus. Le contrôle recalcule les points des placements et compare le score affiché, les détails affichés et les temps d’inférence consignés par l’application.

| Tour rejoué | Score | Inférence automatique 0.15.1 | Inférence sur demande 0.15.2 |
| --- | ---: | ---: | ---: |
| BU, lettres déjà en place | 4 | 988 ms | 945 ms |
| Passe | 0 | 962 ms | 934 ms |
| AVE, mots croisés et mot double | 27 | 1 524 ms | 1 473 ms |
| TARGE, mots croisés | 16 | 1 541 ms | 1 626 ms |
| POLY, joker et mot double | 24 | 1 416 ms | 1 560 ms |
| SAURAIS, sept lettres, joker et mot croisé | 67 | 1 921 ms | 1 749 ms |
| ECURE, mots croisés | 23 | 1 394 ms | 1 268 ms |
| SAUTONS, sept lettres et étoile | 64 | 1 506 ms | 1 403 ms |

En 0.15.1, le modèle a conservé les huit scores. Il a changé le détail principal cinq fois. Pour SAURAIS et SAUTONS, il a substitué les cases bonus à la prime de 50 points. Pour AVE, TARGE et ECURE, il a substitué une case bonus ou un décompte général aux mots croisés.

En 0.15.2, les huit scores et le premier détail restent visibles après la suggestion. Le bouton **Voir les autres détails** affiche les faits vérifiés sans lancer le modèle. Les huit demandes explicites ont chacune fait ressortir un de ces mêmes détails. Le résultat d’une demande fermée pendant l’inférence est resté hors du dialogue rouvert. La médiane des inférences est de 1 438 ms, contre 1 461 ms en 0.15.1. Ces délais décrivent cet émulateur. L’essai ne mesure ni la compréhension des joueurs ni les performances sur téléphone physique.

Les essais de mise à jour depuis la 0.15.1 ont conservé les sauvegardes et historiques contrôlés sur Android 8 et 16. Le test couvre les versions de l’APK direct et les paquets tirés du bundle avec des pages de 16 Kio. Sur ces paquets, le premier défi a accepté la sélection de S puis de la case H12. Android expose le libellé de la case et le jeu confirme CHATS pour 11 points. Sur Android 8, **Amis** s’ouvre et affiche un état explicite quand son serveur est indisponible. Les 65 tests unitaires Android et Lint passent.

La régression du moteur Android sur 120 nouveaux tirages, deux sièges et six duels a terminé **1 440 parties** avec le lexique Grammalecte 7.7 et les poids Maître embarqués. Chaque coup a été validé avec le score annoncé. Cette simulation tourne sur le code Java de jeu. Le test de rejeu ci-dessus tourne dans l’APK installé.

```sh
./tools/android_gameplay/run.sh 120 20261115 6
```

## Premières mesures sur 480 parties

Le 13 novembre 2026, le moteur embarqué a joué **480 parties complètes** avec le lexique Grammalecte 7.7 livré dans l’application (406 312 entrées, poids Maître promus actifs). Le scénario utilise 40 tirages déterministes, chacun joué deux fois en échangeant le chevalet et le premier tour, pour chacun des six duels. Le siège joueur est piloté par un niveau NAB fixe. Dans les deux scénarios adaptatifs, le moteur évalue la partie en cours avec un Elo de départ fixé à 1100.

| Duel | Victoires · nuls · défaites du premier niveau | Scores moyens | Tours P10 · P50 · P90 | Écart moyen après 8 tours |
| --- | --- | --- | --- | ---: |
| Promeneur / Habitué | 5 · 0 · 75 | 302,4 – 425,6 | 27 · 30 · 33 | −33,8 |
| Habitué / Stratège | 16 · 0 · 64 | 388,7 – 483,3 | 23 · 26 · 30 | −27,9 |
| Stratège / Maître | 28 · 0 · 52 | 439,6 – 486,5 | 21 · 23 · 26 | −8,7 |
| Maître / Maître | 39 · 2 · 39 | 459,7 – 459,7 | 20 · 22 · 24 | 0 |
| Promeneur / Adaptatif | 1 · 0 · 79 | 305,9 – 399,9 | 27 · 31 · 34 | −33,1 |
| Stratège / Adaptatif | 57 · 0 · 23 | 468,5 – 419,4 | 22 · 25 · 28 | +22,3 |

Un tour est compté à chaque action d’un des deux sièges. Sur les 480 parties, 476 se terminent sur un chevalet vidé. Le moteur a enregistré 47 passages, aucun échange et 1 457 coups à sept lettres. Tous les coups proposés ont passé la validation avec le score annoncé.

L’écart est le plus marqué au début de la progression. Pour une première partie, le niveau Promeneur offre un point de départ plus accessible que le niveau Habitué ou l’Adaptatif à 1100. Sur ce même Elo, l’Adaptatif a choisi le niveau Promeneur pour 37 % de ses tours face à Promeneur, et seulement 5 % face à Stratège. Un test avec des joueurs débutants reste nécessaire pour régler le niveau initial et juger du temps de partie sur téléphone.

## Réplication sur 1 440 parties complètes

Le 14 novembre 2026, chaque confrontation a été rejouée sur 120 tirages inédits, dans les deux ordres de départ. Le moteur Android utilise Grammalecte 7.7 et active les poids entraînés de Maître. Les 1 440 parties sont allées à leur terme et tous les coups joués ont été validés avec le score annoncé.

| Duel | Victoires · nuls · défaites du premier niveau | Points de match du premier niveau |
| --- | --- | ---: |
| Promeneur / Habitué | 17 · 1 · 222 | 7,29 % |
| Habitué / Stratège | 42 · 2 · 196 | 17,92 % |
| Stratège / Maître | 73 · 1 · 166 | 30,63 % |
| Maître / Maître | 119 · 2 · 119 | 50,00 % |
| Promeneur / Adaptatif | 11 · 1 · 228 | 4,79 % |
| Stratège / Adaptatif | 186 · 2 · 52 | 77,92 % |

Maître obtient 69,38 % des points contre Stratège, soit un écart Elo indicatif de +142 sur cette confrontation (400 × log₁₀ du rapport des points). Les cotes Android et studio reposent sur des moteurs et des adversaires différents.

```sh
./tools/android_gameplay/run.sh 120 2026111401 4
```

## Tutoriel et défi du jour

Le premier exercice valide bien la pose du S à la suite de CHAT (CHATS, 11 points). Le coup présenté par NAB forme CHATONS (13 points).

Du 13 novembre au 12 décembre 2026 inclus, les 30 défis ont tous accepté une pose d’une seule lettre. Le nombre de poses distinctes possibles varie de 9 à 32, avec une médiane de 21. Le meilleur coup à une lettre vaut entre 6 et 28 points, avec une médiane de 9. Sur 10 dates, un de ces coups dépasse la proposition du catalogue, avec jusqu’à 16 points d’avance. Présenter cette proposition comme un coup que le joueur peut battre correspond au comportement du jeu.

## Campagne de vérification de la bêta 0.11

Avec la graine `20261114`, les six confrontations ont été rejouées sur **3 000 parties complètes**. Le moteur a validé chaque placement proposé par NAB et retrouvé son score annoncé. Les médianes vont de 22 à 31 tours selon les niveaux. Promeneur remporte 21 parties sur 500 contre Habitué et 11 sur 500 contre Adaptatif. Ces écarts restent des mesures entre bots.

Les **1 200 parties de stress** ont mélangé placements, passes et échanges sur des tables de deux à quatre joueurs. Chaque tour vérifie l’ordre des sièges, la conservation des 102 jetons, le chevalet inscrit dans l’historique et les ajustements de fin de partie. Les poses volontairement invalides sont vérifiées avant l’action de chaque tour concerné.

| Joueurs | Parties | Placements | Échanges | Passes | Poses invalides refusées | Jokers posés |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 2 | 400 | 9 727 | 2 145 | 1 914 | 2 885 | 677 |
| 3 | 400 | 8 917 | 1 866 | 2 019 | 2 729 | 685 |
| 4 | 400 | 7 875 | 1 621 | 2 108 | 2 520 | 677 |

Les 366 dates à partir du 13 novembre 2026 ont toutes accepté une pose d’une lettre. Le nombre médian de possibilités est de 21. Le tutoriel valide toujours CHATS pour 11 points.

Le test de stockage sur Android 8 a reproduit une perte d’historique lorsque deux instances de l’application terminaient des parties avec la même ancienne copie des archives. Après correction, le même test est passé sur Android 8 et 16. Quatre instances concurrentes ont archivé 48 parties distinctes à chaque essai. Les 48 archives sont présentes une seule fois, chaque calcul d’Elo repart du résultat précédent et les autres parties en cours sont intactes.

### Duels en ligne et tournoi

Le serveur de test a joué **328 duels** sur HTTP, dont 164 classiques et 164 miroirs. Les 656 coups ont été contrôlés par un calcul de score indépendant, avec 492 vérifications de renvois et de demandes concurrentes. Le compteur de l’ancienne version avançait aussi après les miroirs et ne visitait que 82 des 164 plateaux quand chaque classique était suivi d’un miroir. Après correction, le parcours visite les 164 plateaux.

Sur Docker, **55 week-ends simulés** avec trois joueurs ont produit 165 tournois complets et 495 placements. Ils couvrent les 164 positions du catalogue et vérifient le classement et le déblocage de la carte après trois manches. Après la clôture, le serveur accepte le renvoi d’un coup enregistré et refuse tout nouveau coup. La récompense reste acquise le week-end suivant. Les 24 tests Rust passent.

Sur le réseau local, **21 parties web** ont produit 545 tours. Les 1 203 aperçus de score correspondent au calcul de référence. Dans chacun des 15 essais où le même coup est envoyé simultanément, le serveur n’a accepté qu’une seule pose.

## Reproduire

Installer Java 17, Python 3 et le SDK Android 36, puis lancer depuis le dépôt. Le SDK est lu depuis `ANDROID_HOME`, `ANDROID_SDK_ROOT` ou l’emplacement Android Studio par défaut sur macOS.

```sh
./tools/android_gameplay/run.sh 40 20261113 4
./tools/android_gameplay/run.sh 250 20261114 8 400
./tools/android_gameplay/run-storage.sh emulator-5586
cargo test --release --locked social::tests::mirror_swaps_racks_once_without_changing_the_board_or_leaking_play
cargo test --release --locked social::tournament::tests::weekend_ranks_only_complete_friends_and_rejects_new_closed_scores
```

Les trois premiers arguments sont le nombre de tirages, la graine et le nombre de workers. Le quatrième, facultatif, indique le nombre de parties de stress par taille de table et active la vérification de 366 défis. Le programme compile les classes de jeu Android et prépare le lexique avec le même script que le build. Il imprime aussi son empreinte SHA-256 et l’activation des poids Maître. Le script de stockage utilise `adb` et `d8` du SDK Android et crée des fichiers temporaires sur l’appareil indiqué. Les essais sur écran tactile et la rétention se mesurent séparément.
