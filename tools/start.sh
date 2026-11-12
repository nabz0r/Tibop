#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
docker compose up -d --build postgres redis api dashboard
count="$(docker compose exec -T postgres psql -X -U lettrine -d lettrine -Atc 'SELECT count(*) FROM lab_games')"
if [ "$count" = 0 ]; then
    python3 tools/import_campaign.py --archive data/campaign/duels-fr-100000.jsonl.zst --metadata data/campaign/metadata.json
fi
docker compose up -d --build worker beat
printf 'Lettrine Studio est disponible sur http://127.0.0.1:%s\n' "${LETTRINE_PORT:-8787}"
