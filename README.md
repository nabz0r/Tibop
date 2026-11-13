<p align="center"><img src="web/assets/lettrine-mark.png" alt="Logo Lettrine" width="112"></p>

# Lettrine

Laboratoire local de stratégie pour jeux de lettres. Le studio explore 128 000 duels en quatre langues, les archives de maîtres, les variantes de placement et la progression du bot adaptatif N A B. L’application Android permet de jouer et d’expliquer les coups hors ligne.

## Ouvrir sur le Mac

Avec Docker Desktop, Python 3 et `zstd` disponibles, lancer depuis ce dossier.

```sh
./tools/start.sh
```

Le premier démarrage charge les campagnes fournies dans PostgreSQL. L’[accueil Lettrine](http://127.0.0.1:8787/) ouvre les salons de jeu. [Lettrine Studio](http://127.0.0.1:8787/index.html) affiche les cotes, les confrontations, les mots fréquents et les archives. **Parties** permet de filtrer Expert × Expert, de rejouer coup par coup, de regarder un duel généré ou d’examiner les alternatives. **Outils** permet d’importer du JSON ou du JSONL, de télécharger les journaux et de récupérer l’APK.

Ollama tourne sur le Mac. Les deux modèles sont requis pour le commentaire de position et la recherche sémantique.

```sh
ollama pull llama3.2:3b
ollama pull qwen3-embedding:0.6b
```

Gemma, Llama et Qwen ont été comparés sur des duels appariés. Les autres fonctions du studio restent disponibles si Ollama est arrêté. Le port Web se règle avec `LETTRINE_PORT`. Les ports PostgreSQL et Redis restent internes au projet Docker.

## Jouer sur le web

Ouvrir [Lettrine](http://127.0.0.1:8787/) depuis le Mac. Pour jouer en solo, choisir un bot et lancer la partie. Pour jouer entre amis, choisir une table de 2 à 4 places et ajouter des bots si souhaité. Partager le lien affiché avec les autres joueurs sur le même réseau Wi-Fi ou filaire. L’hôte peut lancer la partie quand toutes les places humaines sont occupées.

Pour jouer, glisser une lettre sur le plateau ou sélectionner une lettre puis sa case. Le premier mot doit passer par l’étoile centrale. Avant de valider, l’aperçu affiche les mots formés et les points du coup, ou explique pourquoi le coup est refusé. **Indice** propose un mot, son score et les cases où poser les lettres. **Autre indice** fait défiler les autres mots trouvés. Le serveur vérifie les mots avec Grammalecte 7.7. Le bouton **Retrouver mon salon** ramène chaque joueur à sa place depuis le navigateur utilisé pour rejoindre la partie.

`./tools/start.sh` détecte l’adresse réseau du Mac. Si le lien est uniquement accessible sur le Mac, relancer avec `LETTRINE_LAN_IP` défini sur son adresse IPv4 locale. Le port des salons est réglable avec `LETTRINE_LAN_PORT` (8788 par défaut). Le Mac doit rester allumé et Lettrine doit tourner pendant la partie.

À la fin d’une partie jouée avec cette version, les participants peuvent télécharger le journal des coups en JSON. Dans le studio, ouvrez **Outils**, importez ce fichier, puis ouvrez **Parties → Parties importées** pour le rejeu. Les salons qui avaient déjà enregistré des coups avant cette mise à jour restent jouables et leur journal antérieur est indisponible.

## Application Android

[Télécharger l’APK](android/artifacts/lettrine.apk) · [App Bundle pour la bêta Google Play](android/artifacts/lettrine.aab) · [Guide Android et compilation](android/README.md)

Lettrine Android propose un défi par jour hors ligne et une série de jours joués. **Bats mon score** crée une carte et un lien pour inviter un ami à jouer le même défi. L’application permet de jouer en solo, à deux, trois ou quatre sur un appareil ou en duel Bluetooth. Le bilan de NAB permet de retenter un coup manqué avec son chevalet d’origine. SmolLM2 explique les coups et les comparaisons hors ligne. L’historique affiche la progression de l’Elo et exporte les parties en JSON.

## Laboratoire

| Mesure | Résultat |
| --- | ---: |
| Duels de référence dans PostgreSQL | 128 000, dont 100 000 en français |
| Expert × Expert | 10 000 |
| Elo relatifs Découverte / Initié / Stratège / Expert | 748 / 1 016 / 1 409 / 1 627 |
| Expert entraîné contre Expert de référence sur 12 000 duels réservés | 51,93 % des points de match · +13 Elo |
| Réplication sur un second lot de 100 000 duels réservés | 52,08 % des points de match |
| Recherche avancée face à l’Expert entraîné | 55,58 % des points sur 2 000 duels réservés |
| Calibration adaptative FR et EN | 24 000 duels par langue |
| Cotes humaines historiques sourcées | 44 relevés, échelles fédérales distinctes |

L’analyse de position énumère les placements légaux puis compare les principales options sur trois demi-coups, en échantillonnant le sac et le chevalet adverse. Ollama choisit l’angle du commentaire et le moteur en fournit les chiffres. Le banc d’essai compare des modèles qui choisissent parmi des coups légaux fournis par le moteur. La recherche sémantique indexe les documents et une sélection de positions dans pgvector.

```sh
cargo test --release
cargo run --release -- profiles
cargo run --release -- run
cargo run --release -- train
cargo run --release -- benchmark-llm 6
LETTRINE_ELITE=1 cargo run --release -- export jsonl > experts.jsonl
```

Le moteur français embarque Grammalecte 7.7. Le comparateur fournit un audit des écarts à partir d’un fichier ODS autorisé. Les profils anglais WESPA, allemand SDeV et russe Mattel chargent leurs distributions et lexiques ouverts, avec des campagnes séparées. La variante japonaise est documentée. Les cotes sont propres au profil, au lexique et aux stratégies testés.

## Documentation

- [Architecture et API](docs/ARCHITECTURE.md)
- [Règles, lexique français et comparaison ODS](data/fr/README.md)
- [Campagne et résultats](docs/RESULTATS.md) · [Archive des 100 000 duels](data/campaign/README.md)
- [Entraînement, Elo et recherche](docs/ENTRAINEMENT.md)
- [Profils de langues](docs/LANGUES.md)
- [Archives humaines et provenance](data/expert/README.md)
- [Comparatif Android](docs/COMPARATIF_ANDROID.md)

- [Modèles Ollama et projet mobile](docs/CHOIX_MODELES.md)
- [Histoire, maîtres et archives](docs/HISTOIRE_ET_MAITRES.md)
- [Sécurité locale et suite produit](docs/SECURITE_ET_SUITE.md)

Code sous [AGPL-3.0](LICENSE). Les données Grammalecte et les polices ont leurs propres notices. Lettrine est un projet indépendant.
