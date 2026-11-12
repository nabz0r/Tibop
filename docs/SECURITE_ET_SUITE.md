# Sécurité du studio et suite produit

## Périmètre vérifié

Le studio est une application locale mono-utilisateur. Nginx écoute sur la boucle locale du Mac et n’accepte que les hôtes locaux attendus. L’API, PostgreSQL, Redis et le worker sont joints par les réseaux Docker privés. Le code vérifie l’origine des requêtes navigateur et limite les requêtes, connexions, analyses, diffusions et exports. Le navigateur reçoit une politique de sécurité du contenu et des permissions fermées au micro, à la caméra et à la localisation. Le tableau géographique affiche les lieux d’événements documentés. Le collecteur système lit les mesures du Mac sans donner aux conteneurs accès au socket Docker. [OWASP ASVS](https://asvs.owasp.org/) · [Sécurité Docker](https://docs.docker.com/engine/security/)

Les imports valident les coordonnées, le nombre de sièges et la structure JSON, et s’enregistrent dans des transactions. Les lectures SQL prennent leurs paramètres séparément. La recherche vectorielle indexe des ressources du laboratoire. Les parties personnelles et les autorisations du téléphone restent hors du corpus. Les commentaires sont traités par Ollama sur le Mac.

Les vérifications locales couvrent la configuration, les en-têtes, les restrictions d’origine, les chemins normaux de l’API et l’intégrité des artefacts. L’application locale ne constitue pas un service public authentifié.

## Étapes de recherche

1. Conserver les résultats et les tirages de chaque profil, avec une échelle Elo relative propre à sa langue et à son lexique.
2. Mesurer les politiques rapides par autojeu et réserver la recherche approfondie aux positions où le gain attendu justifie son coût. Tester toute promotion sur des graines distinctes.
3. Comparer les coups aux archives humaines avec leur édition de dictionnaire. Jouer des rencontres consenties avec des adversaires de référence pour étalonner l’Elo du bot face au jeu humain.
4. Étendre les salons à deux, trois ou quatre sièges, avec humains et bots configurables par siège. Séparer le serveur de jeu, la file des calculs et les statistiques. Enregistrer les coups avec version du profil, ordre des sièges, types de participants et résultats.
5. Vérifier le mode local et la reprise de partie avant de publier les clients web et mobiles. L’APK actuellement conservé est un prototype antérieur au chantier de recherche.

## Ouverture éventuelle au public

Une version en ligne doit distinguer joueurs, modérateurs et administrateurs, avec OIDC, sessions sécurisées et journal des actions. Le serveur de jeu détient le sac et valide les tours. Il envoie les informations privées uniquement à leur siège. Les ligues restent distinctes par langue et lexique. Le profil d’un joueur peut afficher un pays choisi et une cote publiée volontairement. La géolocalisation précise et le microphone demanderaient un usage défini, une autorisation dédiée et une politique de conservation. Le studio actuel ne collecte pas ces données.

Le niveau gratuit Google Cloud dépend des produits, régions et quotas. Un service public avec stockage PostgreSQL, diffusion, Redis et inférence ne peut pas être budgété à zéro sans chiffrer la charge et fixer des limites d’exploitation. Le serveur local est le déploiement vérifié. Pour une version mobile monétisée, les demandes AdMob suivent les choix de confidentialité via UMP, avec consentement là où il est requis et des identifiants propres à l’application. [Google Cloud Free Tier](https://docs.cloud.google.com/free/docs/free-cloud-features) · [Google AdMob UMP](https://developers.google.com/admob/android/privacy)
