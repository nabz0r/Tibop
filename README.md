<p align="center"><img src="web/assets/lettrine-mark.png" alt="Logo Lettrine" width="112"></p>

# Lettrine

Jeu de lettres français pour Android et laboratoire local de stratégie. Le studio permet d’explorer 100 000 duels, de rejouer des finales expertes, de comparer des placements et d’importer les parties du téléphone.

## Ouvrir sur le Mac

Avec Docker Desktop, Python 3 et `zstd` disponibles, lancer depuis ce dossier.

```sh
./tools/start.sh
```

Le premier démarrage charge l’archive fournie dans PostgreSQL. Ouvrir [Lettrine Studio](http://127.0.0.1:8787). Le tableau de bord affiche les cotes, les confrontations, les mots fréquents et les archives. **Parties** permet de filtrer Expert × Expert, de rejouer coup par coup, de regarder un duel généré ou d’examiner les alternatives. **Administration** permet d’importer du JSON ou du JSONL, de télécharger les journaux et de récupérer l’APK.

Ollama tourne sur le Mac. Les deux modèles sont requis pour le commentaire de position et la recherche sémantique.

```sh
ollama pull llama3.2:3b
ollama pull qwen3-embedding:0.6b
```

Les autres fonctions du studio restent disponibles si Ollama est arrêté. Le port Web se règle avec `LETTRINE_PORT`. Les ports PostgreSQL et Redis restent internes au projet Docker.

## Android

[Télécharger l’APK](android/artifacts/lettrine.apk) · [Télécharger depuis le studio](http://127.0.0.1:8787/download/lettrine.apk) · [Guide Android et compilation](android/README.md)

Jeu hors ligne à deux, trois ou quatre sur un appareil, duel Bluetooth et solo contre quatre niveaux fixes ou un bot adaptatif. L’app garde l’historique et la cote locale, exporte ses parties en JSON et propose zoom, palettes et ambiance musicale optionnelle.

## Laboratoire

| Mesure | Résultat |
| --- | ---: |
| Duels de référence | 100 000 |
| Expert × Expert | 10 000 |
| Elo relatifs Découverte / Initié / Stratège / Expert | 748 / 1 016 / 1 409 / 1 627 |
| Expert entraîné contre Expert de référence sur 12 000 duels réservés | 51,93 % des points de match · +13 Elo |
| Archives de compétition avec coups | 4 parties |

L’analyse de position énumère les placements légaux puis compare les principales options sur trois demi-coups, en échantillonnant le sac et le chevalet adverse. Llama intervient dans le commentaire. Le banc d’essai séparé compare Llama et Qwen quand ils choisissent parmi des coups légaux fournis par le moteur. La recherche sémantique indexe les documents et une sélection de positions dans pgvector.

```sh
cargo test --release
cargo run --release -- profiles
cargo run --release -- run
cargo run --release -- train
cargo run --release -- benchmark-llm 6
LETTRINE_ELITE=1 cargo run --release -- export jsonl > experts.jsonl
```

Le moteur français embarque Grammalecte 7.7. Le comparateur fournit un audit des écarts à partir d’un fichier ODS autorisé. Les profils anglais WESPA et russe Mattel exécutent des simulations avec un lexique fourni localement. La variante japonaise est documentée. Les cotes sont propres au profil, au lexique et aux stratégies testés.

## Documentation

- [Architecture et API](docs/ARCHITECTURE.md)
- [Règles, lexique français et comparaison ODS](data/fr/README.md)
- [Campagne et résultats](docs/RESULTATS.md) · [Archive des 100 000 duels](data/campaign/README.md)
- [Entraînement, Elo et recherche](docs/ENTRAINEMENT.md)
- [Profils de langues](docs/LANGUES.md)
- [Archives humaines et provenance](data/expert/README.md)
- [Comparatif Android](docs/COMPARATIF_ANDROID.md)

Code sous [AGPL-3.0](LICENSE). Les données Grammalecte et les polices ont leurs propres notices. Lettrine est un projet indépendant.
