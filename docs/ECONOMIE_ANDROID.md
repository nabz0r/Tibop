# Économie de Tibop sur Android

La bêta 0.16.5 propose un circuit solo avec trois parcours par langue. La Boutique affiche les pièces et les tickets de l’appareil, permet d’échanger 100 pièces contre un ticket et propose des thèmes à équiper ou à débloquer. La partie classique, le défi du jour, le bilan NAB, les duels et le tournoi restent en accès libre. Les achats en argent réel sont désactivés.

## Publicité choisie par le joueur

Le joueur choisit **Regarder une vidéo** dans le circuit ou la Boutique pour gagner un ticket bonus. Le SDK doit confirmer la récompense. La limite est d’un ticket vidéo par date locale et par appareil, toutes langues confondues. Le ticket gagné reste disponible hors ligne. Une interruption, un refus de consentement ou l’absence d’annonce laissent les autres parcours accessibles.

L’intégration utilise UMP avant de demander une annonce. Le lien vers les choix de confidentialité apparaît lorsque UMP le demande. La demande publicitaire ne prend aucun champ du profil Lettrine. [Documentation UMP](https://developers.google.com/admob/android/privacy) · [Règles des annonces récompensées](https://support.google.com/admob/answer/7313578)

- Pour reproduire les livrables de bêta, lancer `LETTRINE_ADMOB_TEST=1 ./android/scripts/build.sh` puis `LETTRINE_ADMOB_TEST=1 ./android/scripts/build.sh --bundle`. Les deux utilisent les identifiants de démonstration Google. [Annonces de test](https://developers.google.com/admob/android/test-ads)
- Pour préparer une compilation publicitaire avec un compte réel, fournir `LETTRINE_ADMOB_APP_ID` et `LETTRINE_ADMOB_REWARDED_ID` lors de la compilation. Configurer les messages de confidentialité pour l’application dans AdMob, valider les obligations de déclaration et les choix d’audience du compte Play, puis vérifier le parcours sur le canal de test avant activation. [Démarrage AdMob Android](https://developers.google.com/admob/android/quick-start)

## Récompenses observées dans les jeux

| Jeu | Récompenses et conditions |
| --- | --- |
| Wordscapes | Pièces et indices, portraits et progression à travers les objectifs du jour. Une vidéo facultative peut ouvrir un cadeau supplémentaire ou remplacer un objectif. [Objectifs du jour](https://peoplefun.helpshift.com/hc/en/6-wordscapes/faq/814-what-are-daily-goals/) · [Cadeau quotidien](https://peoplefun.helpshift.com/hc/en/6-wordscapes/faq/271-what-is-the-daily-gift-and-how-does-it-work/) |
| Scrabble GO | Jeux de tuiles à collectionner, trophées de ligue, gemmes, coffres et tickets d’arène. [Aide Scopely](https://scopely.helpshift.com/hc/en/50-scrabble-web/faq/6080-scrabble-go-1666187235/) |
| Words With Friends Classic | Objectifs adaptés au joueur, coffres, clés et paliers de récompenses. [Aide Zynga](https://zyngasupport.helpshift.com/hc/en/26-words-with-friends-classic/faq/20962-what-are-daily-goals/) |
| Brawl Stars | Les victoires quotidiennes peuvent donner des récompenses aléatoires. Le classé donne accès à des skins, sprays ou icônes de saison. Certains skins se prévisualisent à l’entraînement. [Récompenses](https://support.supercell.com/brawl-stars/en/articles/starr-drops-5.html) · [Skins](https://support.supercell.com/brawl-stars/en/articles/skins-8.html) |

Tibop débloque Porcelaine et Ramune après un jour de défi quotidien, Marée et Sakura après trois jours joués, Ambre après trois parties solo terminées et Ardoise après cinq. Le profil affiche notamment la première victoire, le premier coup à sept lettres et la série de trois jours.

Terminer les trois manches d’un même week-end débloque **Nocturne dorée**, un fond à choisir pour les cartes **Bats mon score**. Le tournoi montre l’aperçu et confirme le déblocage.

## Règles du circuit

### Tickets pour un circuit solo

Le circuit propose trois parcours de trois positions par langue. Chaque nouvelle entrée après une réussite ouvre le parcours suivant. Le score courant et le meilleur score de la langue restent visibles, et NAB peut rejouer chaque position. La première entrée est offerte sur l’appareil. Les suivantes utilisent un ticket qui couvre les trois positions et la reprise du parcours. Le catalogue de 12 défis par langue et les trois manches classées du week-end restent en accès libre.

La réserve de trois tickets gratuits récupère un ticket toutes les deux heures. Le circuit affiche le solde et le temps avant la prochaine recharge. Les tickets bonus restent en réserve quand les tickets gratuits se rechargent. Les tentatives supplémentaires servent uniquement au circuit solo.

Words With Friends 2 régénère les tickets de ses mini-ligues solo toutes les 12 minutes et les vend aussi en pièces. Scrabble GO offre une première entrée sur certaines épreuves, puis accepte gemmes ou tickets. Candy Crush recharge une vie toutes les 30 minutes et propose les achats et l’aide des amis. Ces rythmes illustrent des mécaniques différentes. La durée du circuit de Tibop et la fréquence de retour doivent déterminer la recharge. [Zynga](https://zyngasupport.helpshift.com/hc/en/63-words-with-friends-2/faq/18333-what-is-solo-challenge/) · [Scopely](https://scopely.helpshift.com/hc/en/28-scrabble-go/faq/4206-tournament-entry-1697368709/) · [King](https://candycrush.zendesk.com/hc/en-us/articles/360000750878-How-do-lives-work)

### Pièces, parrainage et achats

- **Pièces gagnées en jouant.** Le premier défi quotidien terminé donne 25 pièces, la première réussite de chaque position du catalogue 50 et une coupe terminée 75. Dans les réglages, Nuit indigo coûte 250 pièces et Coquelicot 500. Dans le circuit, 100 pièces donnent un ticket bonus. Le solde et les thèmes débloqués sont communs aux deux langues sur l’appareil. [Pièces de Wordscapes](https://peoplefun.helpshift.com/hc/en/6-wordscapes/faq/269-what-are-coins-and-how-do-i-use-them/)
- **Parrainage.** Un ticket à chacun après leur premier duel commun terminé. L’invité doit accepter le lien dans les 48 heures suivant la création de son nouveau compte social. Le serveur conserve le premier parrain même après expiration du lien et ne crédite le duel qu’une fois. Le parrain reçoit jusqu’à trois tickets par mois UTC et par compte social. Le portefeuille local synchronise le total du serveur quand on ouvre Amis ou le duel. [Parrainage Pokémon GO](https://niantic.helpshift.com/hc/en/6-pokemon-go/faq/2875-referring-friends-and-referral-bonuses/)

### Pistes commerciales après la bêta

- **Premier achat.** Tester un chapitre permanent de 20 positions inédites, des exercices NAB et un thème, à 5,99 € ou 8,99 € selon le groupe. Afficher le prix local retourné par Google Play. Ce test mesure l’envie de payer pour le jeu. [Tests de prix Play Console](https://support.google.com/googleplay/android-developer/answer/16643292)
- **Gold.** À évaluer une fois que plusieurs offres ont prouvé leur intérêt. Il sert à acquérir des articles fixes. Afficher le prix en monnaie réelle et proposer l’achat direct de l’article, avec un compte récupérable et le même droit d’accès en français et en anglais. Les principes européens portent sur le prix lisible et les achats de monnaie forcés ou excédentaires. [Principes CPC](https://commission.europa.eu/document/download/8af13e88-6540-436c-b137-9853e7fe866a_en) · [Actions européennes](https://commission.europa.eu/topics/consumers/consumer-rights-and-complaints/enforcement-consumer-protection/coordinated-actions/social-media-online-games-and-search-engines_en)

Les achats numériques passent par Google Play Billing. Vérifier les achats côté serveur et créditer une seule fois chaque jeton d’achat confirmé. Traiter les achats en attente et les remboursements. Les espaces sociaux FR et EN sont distincts. Les achats restaurables doivent utiliser une identité commerciale commune aux deux langues. Une récompense AdMob portée au solde du serveur utilise un rappel signé et unique. Le ticket issu d’une vidéo appartient au joueur qui l’a regardée. [Règles Play](https://support.google.com/googleplay/android-developer/answer/9858738) · [Vérification des achats](https://developer.android.com/google/play/billing/security) · [Règles AdMob](https://support.google.com/admob/answer/7313578) · [Vérification AdMob](https://developers.google.com/admob/android/ssv)

### Mesure et objectif de recettes

Comparer deux groupes qui jouent les mêmes circuits, avec accès libre ou tickets. Mener ce premier test sans publicité pour isoler l’effet des tickets. Mesurer les circuits commencés et terminés, la part des joueurs qui quittent l’écran à zéro ticket, les retours à J1 et J7, et la participation au quotidien et aux duels. Analyser les résultats par langue. Si les tickets réduisent les parcours terminés ou le retour à J7 au-delà du seuil défini avant le test, retenir l’accès libre. Tester séparément la vidéo, puis le prix du pack. Les recettes s’évaluent avec les impressions effectivement diffusées et le montant réellement encaissé par achat.

Sur un mois de 30 jours, à 20 000 joueurs quotidiens et 6 € pour 1 000 impressions, les scénarios suivants supposent 80 000 joueurs mensuels et 6,50 € encaissés par vente après taxes sur la vente, frais de plateforme et remboursements.

| Impressions par joueur actif et par jour | Recettes pub | Ventes mensuelles pour atteindre 10 000 € | Ventes pour 100 joueurs mensuels |
| ---: | ---: | ---: | ---: |
| 0,03 | 108 € | 1 522 | 1,9 |
| 0,10 | 360 € | 1 484 | 1,9 |
| 0,30 | 1 080 € | 1 373 | 1,7 |

Ces chiffres illustrent le modèle à partir d’hypothèses. L’objectif correspond à des recettes avant hébergement, production du contenu et acquisition. Avec un chapitre vendu une seule fois par joueur, il faut renouveler le public ou le catalogue pour maintenir les ventes d’un mois à l’autre.
