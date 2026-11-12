# Campagne française de référence

L’archive Zstandard contient les 100 000 journaux JSONL générés par Lettrine, à raison d’un objet complet par ligne. Les tours incluent la position des jetons, les jokers, le chevalet du joueur au trait, le sac avant le coup et les points. Les scores finaux contiennent l’ajustement du reliquat. Les coordonnées de plateau vont de 0 à 14. Les numéros de tour commencent à 1 et les sièges à 0.

La campagne utilise Grammalecte 7.7 et quatre politiques fixes, avec 10 000 duels par combinaison de niveaux et une alternance des sièges. La graine de départ, les statistiques et les empreintes figurent dans [metadata.json](metadata.json). Les 100 000 parties ont servi à établir une référence expérimentale. Les duels d’entraînement et de validation du nouvel Expert utilisent d’autres graines et leurs résultats figurent dans [le modèle](../models/fr-bots-v1.json).

```sh
zstd -dc data/campaign/duels-fr-100000.jsonl.zst | head -n 1
python3 tools/import_campaign.py --archive data/campaign/duels-fr-100000.jsonl.zst --metadata data/campaign/metadata.json
```

Voir le [rapport de campagne](../../docs/RESULTATS.md) pour la méthode et les biais liés au lexique.
