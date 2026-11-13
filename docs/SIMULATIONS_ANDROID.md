# Simulations de parties Android

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
