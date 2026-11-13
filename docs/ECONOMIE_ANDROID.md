# Économie de Lettrine Android

La bêta peut commencer avec le défi du jour, le solo, le bilan NAB, les skins gagnés en jouant et les modes entre amis. L’APK et l’AAB livrés compilent sans le SDK publicitaire. Le rythme de jeu, la rétention et le partage se mesurent en premier auprès des bêta-testeurs.

## Publicité choisie par le joueur

Un emplacement AdMob est disponible dans les réglages des versions configurées. Le joueur choisit **Regarder la vidéo** pour débloquer un plateau d’entraînement supplémentaire. Le déblocage est enregistré sur l’appareil après la confirmation de la récompense par le SDK, et le plateau devient jouable hors connexion jusqu’au changement de jour. Le défi bonus ne modifie ni l’Elo, ni la série du jour, ni le classement du tournoi. Une interruption, un refus de consentement ou l’absence d’annonce laissent les autres parcours accessibles.

L’intégration utilise UMP avant de demander une annonce. Le lien vers les choix de confidentialité apparaît lorsque UMP le demande. La demande publicitaire ne prend aucun champ du profil Lettrine. [Documentation UMP](https://developers.google.com/admob/android/privacy) · [Règles des annonces récompensées](https://support.google.com/admob/answer/7313578)

- Pour vérifier la variante de test, lancer `LETTRINE_ADMOB_TEST=1 ./gradlew :app:assembleDebug` depuis `android/` après une compilation normale avec le script Android. La variante debug utilise les identifiants de démonstration Google. Le fichier est produit dans `app/build/outputs/apk/debug/`. [Annonces de test](https://developers.google.com/admob/android/test-ads)
- Pour préparer une compilation publicitaire avec un compte réel, fournir `LETTRINE_ADMOB_APP_ID` et `LETTRINE_ADMOB_REWARDED_ID` lors de la compilation. Configurer les messages de confidentialité pour l’application dans AdMob, valider les obligations de déclaration et les choix d’audience du compte Play, puis vérifier le parcours sur le canal de test avant activation. [Démarrage AdMob Android](https://developers.google.com/admob/android/quick-start)

## Récompenses observées dans les jeux

| Jeu | Récompenses et conditions |
| --- | --- |
| Wordscapes | Pièces et indices, portraits et progression à travers les objectifs du jour. Une vidéo facultative peut ouvrir un cadeau supplémentaire ou remplacer un objectif. [Objectifs du jour](https://peoplefun.helpshift.com/hc/en/6-wordscapes/faq/814-what-are-daily-goals/) · [Cadeau quotidien](https://peoplefun.helpshift.com/hc/en/6-wordscapes/faq/271-what-is-the-daily-gift-and-how-does-it-work/) |
| Scrabble GO | Jeux de tuiles à collectionner, trophées de ligue, gemmes, coffres et tickets d’arène. [Aide Scopely](https://scopely.helpshift.com/hc/en/50-scrabble-web/faq/6080-scrabble-go-1666187235/) |
| Words With Friends Classic | Objectifs adaptés au joueur, coffres, clés et paliers de récompenses. [Aide Zynga](https://zyngasupport.helpshift.com/hc/en/26-words-with-friends-classic/faq/20962-what-are-daily-goals/) |
| Brawl Stars | Les victoires quotidiennes peuvent donner des récompenses aléatoires. Le classé donne accès à des skins, sprays ou icônes de saison. Certains skins se prévisualisent à l’entraînement. [Récompenses](https://support.supercell.com/brawl-stars/en/articles/starr-drops-5.html) · [Skins](https://support.supercell.com/brawl-stars/en/articles/skins-8.html) |

Lettrine débloque déjà Porcelaine après un jour de défi quotidien, Marée après trois jours joués, Ambre après trois parties solo terminées et Ardoise après cinq. Le profil affiche notamment la première victoire, le premier coup à sept lettres et la série de trois jours.

À partir de la 0.10, terminer les trois manches d’un même week-end débloque **Nocturne dorée**, un fond à choisir pour les cartes **Bats mon score**. Le tournoi montre l’aperçu et confirme le déblocage. Le défi bonus reste la récompense garantie de la vidéo facultative dans la variante AdMob.

## Première expérience commerciale

Commencer par la vidéo facultative et la comparer à une version sans publicité. Relever la proportion de joueurs qui demandent une vidéo, la proportion de vidéos terminées, le retour à J+1 et J+7 et le taux de fin des défis. Conserver le test si le bonus est utilisé et si la complétion et la rétention restent stables. Mesurer les coûts du serveur social séparément.

À 10 000 joueurs actifs par jour, 0,20 impression par joueur et un eCPM hypothétique de 5 €, le revenu publicitaire brut illustratif est de 10 € par jour. Le volume, le taux d’impression et l’eCPM sont des hypothèses de calcul. Le revenu réel dépendra du pays, du remplissage et des usages observés.

Après validation de la rétention, tester un petit lot de skins à prix fixe, sans avantage en jeu. Les tickets d’événement, les monnaies payantes et l’abonnement restent des pistes de produit à évaluer si les joueurs reviennent régulièrement. Les achats et la télémétrie produit ne font pas partie de cette version.
