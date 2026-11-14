# Serveur de bêta Android Tibop

La bêta dispose de son propre projet Compose et de son volume `lettrine-beta_beta_social_data`. Le français conserve sa base SQLite. L’anglais utilise une seconde base, `social.sqlite.en` par défaut, dans le même volume. Le tunnel HTTPS vers Nginx dessert `/beta`, `/health`, `/api/social/` pour le français et `/api/social-en/` pour l’anglais. Nginx limite le débit global et par adresse, avec un plafond séparé pour les inscriptions.

Pour installer la bêta, ouvrez la [page de téléchargement stable](https://nabz0r.github.io/scrabnble/). L’adresse du tunnel sert à connecter **Amis** aux duels et tournois.

## Ouvrir une session de bêta

```sh
docker compose -f compose.social.yaml -f compose.social-beta.yaml up -d --build --wait
docker compose -f compose.social.yaml -f compose.social-beta.yaml logs beta_tunnel
```

Partagez l’adresse HTTPS affichée dans les journaux avec le chemin `/beta`. Le bouton ouvre Android et demande confirmation avant de changer de serveur. Une invitation française ajoute son code après `#`. Une invitation anglaise utilise `#en/` suivi du code.

Cette adresse de test reste valable tant que le connecteur tourne. Le Mac et Docker doivent rester en fonctionnement. L’adresse change si le tunnel est recréé. Les données restent dans le volume et les comptes invités Android sont associés à leur adresse de serveur. Pour cette raison, le build générique accepte l’adresse via le lien de bêta. [Documentation Cloudflare Quick Tunnels](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)

## Mettre à jour la bêta

La publication GitHub de Tibop distribue un seul APK signé depuis `main`. Le mini-site public est servi depuis `gh-pages`. Ses pages sources se trouvent dans `android/share-site/`. La page de connexion au serveur, `/beta`, est lue depuis `web/beta/`. Les deux pages téléchargent le même APK. Le studio redirige aussi vers cet APK via `/download/tibop.apk`.

Recompilez le service social seul pour conserver le proxy, le tunnel en service et les données des deux langues. Si la configuration Nginx évolue, vérifiez-la puis rechargez le proxy en place.

```sh
docker compose -f compose.social.yaml -f compose.social-beta.yaml up -d --build --no-deps --wait social
docker compose -f compose.social.yaml -f compose.social-beta.yaml exec -T beta_proxy nginx -t
docker compose -f compose.social.yaml -f compose.social-beta.yaml exec -T beta_proxy nginx -s reload
```

Le service ajoute ses tables SQLite de prise en charge du lexique filtré dans le volume existant. Les duels déjà créés et les invitations précédentes gardent leurs identifiants. Chaque nouveau duel français choisit ses règles lors de sa création. La date de bascule du tournoi est enregistrée dans la base et préserve le tournoi de la semaine en cours. Sauvegardez les bases française et anglaise avant la mise à jour et conservez le même domaine pour que les liens envoyés continuent d’ouvrir le bon serveur.

Si Cloudflare ne reconnaît plus le tunnel temporaire, recréez seulement le connecteur. Relevez ensuite la nouvelle adresse HTTPS dans le journal. Sur un téléphone qui utilisait l’ancienne adresse, ouvrez le nouveau lien `/beta` pour configurer le serveur.

```sh
docker compose -f compose.social.yaml -f compose.social-beta.yaml up -d --no-deps --force-recreate beta_tunnel
docker compose -f compose.social.yaml -f compose.social-beta.yaml logs beta_tunnel
```

`/beta` et `/health` doivent répondre HTTP 200. `/api/social/me` et `/api/social-en/me` doivent répondre HTTP 401 lorsqu’aucune session n’est fournie.

## Garder une adresse stable

Sur l’hôte permanent, démarrez uniquement `social` et `beta_proxy` avec le même ensemble Compose. Créez un tunnel nommé dans un compte Cloudflare autorisé. Configurez le domaine HTTPS de la bêta pour l’origine `http://127.0.0.1:8080`, puis lancez son connecteur dans le même espace réseau que le proxy, à la place du connecteur de test. Conservez le jeton du tunnel dans un fichier privé monté en lecture seule. [Tunnel nommé Cloudflare](https://developers.cloudflare.com/tunnel/get-started/)

```sh
docker compose -f compose.social.yaml -f compose.social-beta.yaml up -d --build --wait beta_proxy
docker run -d --name lettrine-beta-connector --restart unless-stopped \
  --network container:lettrine-beta-beta_proxy-1 \
  -v "$LETTRINE_TUNNEL_TOKEN_FILE:/run/secrets/tunnel-token:ro" \
  cloudflare/cloudflared:2026.11.0 tunnel --no-autoupdate --protocol http2 run --token-file /run/secrets/tunnel-token
```

Vérifiez `/beta`, `/health`, le refus d’accès à `/api/social/me` et `/api/social-en/me` sans session, puis les téléchargements du studio sur son adresse locale. Une fois l’origine stable vérifiée, `LETTRINE_SOCIAL_URL` permet de la préconfigurer à la compilation de l’APK et de l’AAB.
