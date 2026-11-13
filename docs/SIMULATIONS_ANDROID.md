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

## Tutoriel et défi du jour

Le premier exercice valide bien la pose du S à la suite de CHAT (CHATS, 11 points). Le coup présenté par NAB forme CHATONS (13 points).

Du 13 novembre au 12 décembre 2026 inclus, les 30 défis ont tous accepté une pose d’une seule lettre. Le nombre de poses distinctes possibles varie de 9 à 32, avec une médiane de 21. Le meilleur coup à une lettre vaut entre 6 et 28 points, avec une médiane de 9. Sur 10 dates, un de ces coups dépasse la proposition du catalogue, avec jusqu’à 16 points d’avance. Présenter cette proposition comme un coup que le joueur peut battre correspond au comportement du jeu.

## Reproduire

Installer Java 17, Python 3 et le SDK Android 36, puis lancer depuis le dépôt. Le SDK est lu depuis `ANDROID_HOME`, `ANDROID_SDK_ROOT` ou l’emplacement Android Studio par défaut sur macOS.

```sh
./tools/android_gameplay/run.sh 40 20261113 4
```

Les arguments sont le nombre de tirages, la graine et le nombre de workers. Le programme compile les classes de jeu Android et prépare le lexique avec le même script que le build. Il imprime aussi son empreinte SHA-256 et l’activation des poids Maître. Cette mesure couvre le moteur local et les solutions des défis. L’usage sur écran tactile, la rétention et le niveau des autres jeux demandent des essais distincts.
