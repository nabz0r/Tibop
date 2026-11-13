# Serveur de bêta Android

La bêta dispose de son propre projet Compose, de sa base SQLite et de son volume `lettrine-beta_beta_social_data`. Le studio et les comptes de développement utilisent leurs services locaux habituels. Le seul accès public est le tunnel HTTPS vers Nginx. Il dessert la page `/beta`, la vérification `/health` et l’API `/api/social/`. Nginx limite le débit global et par adresse, avec un plafond séparé pour les inscriptions.

## Ouvrir une session de bêta

```sh
docker compose -f compose.social.yaml -f compose.social-beta.yaml up -d --build --wait
docker compose -f compose.social.yaml -f compose.social-beta.yaml logs beta_tunnel
```

Partagez l’adresse HTTPS affichée dans les journaux avec le chemin `/beta`. Sur Android 0.11, le bouton ouvre Lettrine et demande confirmation avant de changer de serveur. Une invitation peut ajouter son code à la fin du lien après `#`.

Cette adresse de test reste valable tant que le connecteur tourne. Le Mac et Docker doivent rester en fonctionnement. L’adresse change si le tunnel est recréé. Les données restent dans le volume et les comptes invités Android sont associés à leur adresse de serveur. Pour cette raison, le build générique accepte l’adresse via le lien de bêta. [Documentation Cloudflare Quick Tunnels](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)

## Garder une adresse stable

Sur l’hôte permanent, démarrez uniquement `social` et `beta_proxy` avec le même ensemble Compose. Créez un tunnel nommé dans un compte Cloudflare autorisé. Configurez le domaine HTTPS de la bêta pour l’origine `http://127.0.0.1:8080`, puis lancez son connecteur dans le même espace réseau que le proxy, à la place du connecteur de test. Conservez le jeton du tunnel dans un fichier privé monté en lecture seule. [Tunnel nommé Cloudflare](https://developers.cloudflare.com/tunnel/get-started/)

```sh
docker compose -f compose.social.yaml -f compose.social-beta.yaml up -d --build --wait beta_proxy
docker run -d --name lettrine-beta-connector --restart unless-stopped \
  --network container:lettrine-beta-beta_proxy-1 \
  -v "$LETTRINE_TUNNEL_TOKEN_FILE:/run/secrets/tunnel-token:ro" \
  cloudflare/cloudflared:2026.11.0 tunnel --no-autoupdate --protocol http2 run --token-file /run/secrets/tunnel-token
```

Vérifiez `/beta`, `/health`, le refus d’accès à `/api/social/me` sans session, et les téléchargements du studio sur son adresse locale. Une fois l’origine stable vérifiée, `LETTRINE_SOCIAL_URL` permet de la préconfigurer à la compilation de l’APK et de l’AAB.
