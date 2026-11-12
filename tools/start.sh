#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
docker compose up -d --build postgres redis api dashboard
sql() { docker compose exec -T postgres psql -X -U lettrine -d lettrine -Atc "$1"; }
if [ "$(sql 'SELECT count(*) FROM lab_games')" = 0 ]; then
    python3 tools/import_campaign.py --archive data/campaign/duels-fr-100000.jsonl.zst --metadata data/campaign/metadata.json
fi
for profile in en-wespa-v1 de-sdev-v1 ru-mattel-v1; do
    dir="data/campaign/$profile"
    if [ "$(sql "SELECT count(*) FROM lab_profile_games WHERE profile='$profile'")" = 0 ]; then
        python3 tools/import_campaign.py --profile "$profile" --archive "$dir/games.jsonl.zst" --metadata "$dir/metadata.json"
    fi
done
if [ "$(sql "SELECT count(*) FROM lab_move_metrics WHERE profile='fr-classic-v1'")" = 0 ]; then
    python3 tools/index_moves.py --archive data/campaign/duels-fr-100000.jsonl.zst --metadata data/campaign/metadata.json
fi
for profile in en-wespa-v1 de-sdev-v1 ru-mattel-v1; do
    dir="data/campaign/$profile"
    if [ "$(sql "SELECT count(*) FROM lab_move_metrics WHERE profile='$profile'")" = 0 ]; then
        python3 tools/index_moves.py --profile "$profile" --archive "$dir/games.jsonl.zst" --metadata "$dir/metadata.json"
    fi
done
docker compose up -d --build worker beat
printf 'Lettrine Studio est disponible sur http://127.0.0.1:%s\n' "${LETTRINE_PORT:-8787}"
