# Choisir un modèle Ollama pour Tibop Studio

Dans le studio, le modèle choisit l’angle du commentaire dans une réponse JSON. Le moteur valide ce choix et produit le texte à partir de la revue de position. En cas d’erreur, il utilise la marge simulée et le studio indique le repli. Dans le banc de jeu, Ollama choisit un placement par son indice parmi les coups que le moteur a déclarés légaux. La validation, les points, la gestion du sac et les échanges relèvent du moteur de jeu. La recherche documentaire utilise `qwen3-embedding:0.6b`.

## Candidats pour le Mac M2 Max

Les tailles indiquent les poids distribués par Ollama ou déjà présents sur le Mac. La mémoire d’inférence comprend aussi les caches, le contexte, les requêtes simultanées et les autres modèles en mémoire. Ollama sur macOS prend en charge le CPU et le GPU des puces Apple Silicon. [Ollama sur macOS](https://docs.ollama.com/macos) · [Gestion de la mémoire et de la concurrence](https://docs.ollama.com/faq#how-does-ollama-handle-concurrent-requests)

| Modèle | Taille des poids | Intérêt à tester |
|---|---:|---|
| `llama3.2:3b` | 2,0 Go | Point de comparaison compact, déjà utilisé dans le studio. Meta prend officiellement en charge le français. [Ollama Llama 3.2](https://ollama.com/library/llama3.2) |
| `qwen3:8b` | 5,2 Go, Q4_K_M | Modèle textuel intermédiaire. La famille Qwen3 prend en charge le français et permet de désactiver le mode de réflexion. [Fiche Qwen](https://huggingface.co/Qwen/Qwen3-8B) · [Langues Qwen3](https://qwenlm.github.io/blog/qwen3/) · [Poids Ollama](https://ollama.com/library/qwen3:8b) |
| `ministral-3:8b` ou `ministral-3:14b` | 6,0 ou 9,1 Go | Alternatives multilingues de Mistral pour comparer le respect d’une consigne en français et le JSON. [Fiche Mistral](https://mistral.ai/news/mistral-3/) · [Poids Ollama](https://ollama.com/library/ministral-3) |
| `gemma4:latest` | 9,61 Go, Q4_K_M sur ce Mac | Modèle multilingue installé, avec un mode de réflexion désactivable. Le tag local observé a pour empreinte `c6eb396dbd59…`. L’alias `latest` peut changer. [Fiche Google DeepMind](https://ai.google.dev/gemma/docs/core/model_card_4) · [Tags Ollama](https://ollama.com/library/gemma4/tags) |
| `mistral-small3.2:24b` | 15 Go, Q4_K_M | Variante de plus grande taille. Mistral documente les améliorations du suivi de consigne et de l’appel de fonctions par rapport à Small 3.1. [Fiche Mistral](https://huggingface.co/mistralai/Mistral-Small-3.2-24B-Instruct-2506) · [Tags Ollama](https://ollama.com/library/mistral-small3.2/tags) |
| `qwen3.6:27b-nvfp4` | 18,79 Go sur ce Mac | Variante MLX déjà installée, pour tester si un modèle plus grand améliore le choix des coups ou le commentaire. Empreinte locale `7abb838ab90f…`. [Poids Ollama](https://ollama.com/library/qwen3.6:27b-nvfp4) |

La taille du modèle et les benchmarks de son éditeur donnent des points de départ. Le laboratoire compare les décisions de jeu avec le même moteur, le même lexique et les mêmes tirages. Le commentaire se juge séparément sur la fidélité aux faits.

## Banc de jeu

Le banc français joue chaque tirage dans les deux sièges face à l’Expert entraîné. Aux tours du modèle, le moteur ordonne les placements selon sa propre évaluation et fournit jusqu’à six choix, chacun avec le mot, le score immédiat, une coordonnée et les lettres conservées. Le modèle voit le plateau, les scores et le nombre de jetons dans le sac. Le chevalet adverse reste caché. Si la sortie est inutilisable, le moteur choisit lui-même et le rapport compte un repli. [Banc d’essai](../src/llm_benchmark.rs)

L’API locale `/api/chat` reçoit un schéma dans `format`, un objet à un champ `pick` dont la valeur doit être un entier entre zéro et le dernier indice proposé. Le moteur vérifie cet indice avant de jouer. Les paramètres du banc fixent la température à zéro, un contexte de 2 048 tokens et un délai HTTP de 12 secondes par appel. Les contrôles de réflexion varient selon le modèle. Vérifier `/api/show` avant de comparer un modèle qui attend un niveau `low`, `medium` ou `high`, comme GPT-OSS. [Sorties structurées](https://docs.ollama.com/capabilities/structured-outputs) · [Contrôle de la réflexion](https://docs.ollama.com/capabilities/thinking)

```sh
ollama list
LETTRINE_OLLAMA_MODEL=qwen3:8b \
  cargo run --release --locked -- benchmark-llm 6 results/comparatif-llm.json
```

Le dernier argument fixe le rapport JSON. Les résultats de modèles différents s’ajoutent dans le même fichier pour le même lexique. Garder le même nombre de tirages pour comparer les modèles et conserver le tag, son empreinte et la version d’Ollama. Le compteur de victoires, nulles et défaites, la marge, les choix valides, les replis et le temps total figurent dans le rapport. L’intervalle de Wilson emploie une observation effective par tirage pour tenir compte des deux sièges partageant la graine. Avec peu de tirages, l’incertitude reste importante. L’Elo estimé est un écart relatif à l’Expert entraîné dans ce banc.

## Critères de décision

- **Jeu.** Comparer le taux de points de match, la marge et les replis à tirages appariés. Un modèle qui gagne sur ce corpus choisit parmi les placements proposés par l’Expert. Pour isoler la qualité de décision, rejouer aussi des positions fixes avec plusieurs ordres d’options et comparer les pertes de points immédiates ou de marge simulée.
- **Commentaire.** Contrôler le mot, la coordonnée et chaque chiffre repris. Inclure des cas de joker, de reliquat, de blocage et de fin de sac. Relever les explications en français qui introduisent une affirmation absente de la revue, ainsi que celles qui présentent comme certain un faible écart de marge simulée.
- **Coût local.** Comparer le temps d’appel à froid et à chaud, les pannes et le coût de mémoire, avec les conteneurs habituels actifs. Le rapport actuel donne la durée globale. `load_duration`, `prompt_eval_duration` et `eval_duration` de l’API Ollama permettent une mesure détaillée si l’écart de temps change le choix. [Champs de chronométrage Ollama](https://docs.ollama.com/api/chat)
- **Déploiement.** Retenir la plus petite configuration qui maintient le niveau de fidélité attendu. Le commentaire du studio se règle via `LETTRINE_OLLAMA_MODEL`. Le moteur autonome continue à choisir et à valider les coups sur Android, où les résultats du banc Ollama ne modifient pas les cotes locales. [Architecture du studio](ARCHITECTURE.md)

## Résultats observés sur le Mac

Dans les duels de sélection, les modèles ne choisissent que parmi des placements légaux. Le tableau de bord affiche le nombre de parties, le score, l’intervalle et l’écart Elo régularisé par langue. Les pilotes couvrent français, anglais, allemand et russe. Gemma 4 marque 4/12 en français, 5/12 en anglais, 2/6 en allemand et 3/6 en russe face à l’Expert de chaque profil. Llama 3.2 3B marque 0/12 en français et en anglais. Les échantillons sont petits et ne démontrent pas un niveau humain. [Données des duels](../data/models/llm-benchmarks.json)

Le banc historique de huit commentaires français mesurait la capacité à recopier les scores et à choisir une variante de marge maximale. Gemma 4 réussit dans six cas et Llama 3.2 dans un cas. Qwen 0.6B ne produit aucune réponse valide dans ce protocole. Les latences médianes des réponses valides sont de 2,07 s pour Gemma 4 et de 1,20 s pour Llama 3.2. Le commentaire du studio confie désormais les chiffres au moteur. [Données du test de commentaire](../data/models/commentary-benchmarks.json)

## Modèle embarqué sur Android

L’APK Android inclut SmolLM2 135M Instruct quantifié en 4 bits et exécute l’inférence avec llama.cpp sur le processeur. Le modèle sélectionne un sujet parmi les faits établis du coup. Le moteur rédige en français et fournit les mots, les scores et les cases bonus. Le choix du modèle permet de distribuer le jeu et l’explication dans le même APK. Les mesures de temps, mémoire et température sur téléphones physiques restent à faire. [Modèle et licences](../android/app/src/main/assets/SmolLM2-NOTICE.md) · [Guide Android](../android/README.md)
