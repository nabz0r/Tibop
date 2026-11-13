#!/bin/sh
set -eu
cd "$(dirname "$0")/.."

with_social=0
if [ "$#" -eq 1 ] && [ "$1" = --with-social ]; then
    with_social=1
elif [ "$#" -ne 0 ]; then
    printf 'Usage : %s [--with-social]\n' "$0" >&2
    exit 2
fi

# Publish only the rooms entry point on the Mac's network address.
if [ -z "${LETTRINE_LAN_IP:-}" ] && command -v ipconfig >/dev/null 2>&1; then
    interface=$(route -n get default 2>/dev/null | awk '/interface:/ { print $2; exit }')
    case "$interface" in
        en[0-9]*) LETTRINE_LAN_IP=$(ipconfig getifaddr "$interface" 2>/dev/null || true) ;;
    esac
    export LETTRINE_LAN_IP
fi

docker compose up --build --wait --wait-timeout 180 postgres redis api dashboard
sql() { docker compose exec -T postgres psql -X -U lettrine -d lettrine -Atc "$1"; }
games=$(sql 'SELECT count(*) FROM lab_games')
if [ "$games" = 0 ]; then
    python3 tools/import_campaign.py --archive data/campaign/duels-fr-100000.jsonl.zst --metadata data/campaign/metadata.json
fi
for profile in en-wespa-v1 de-sdev-v1 ru-mattel-v1; do
    dir="data/campaign/$profile"
    games=$(sql "SELECT count(*) FROM lab_profile_games WHERE profile='$profile'")
    if [ "$games" = 0 ]; then
        python3 tools/import_campaign.py --profile "$profile" --archive "$dir/games.jsonl.zst" --metadata "$dir/metadata.json"
    fi
done
metrics=$(sql "SELECT count(*) FROM lab_move_metrics WHERE profile='fr-classic-v1'")
if [ "$metrics" = 0 ]; then
    python3 tools/index_moves.py --archive data/campaign/duels-fr-100000.jsonl.zst --metadata data/campaign/metadata.json
fi
for profile in en-wespa-v1 de-sdev-v1 ru-mattel-v1; do
    dir="data/campaign/$profile"
    metrics=$(sql "SELECT count(*) FROM lab_move_metrics WHERE profile='$profile'")
    if [ "$metrics" = 0 ]; then
        python3 tools/index_moves.py --profile "$profile" --archive "$dir/games.jsonl.zst" --metadata "$dir/metadata.json"
    fi
done
docker compose up --build --wait --wait-timeout 180 worker beat
if [ "$with_social" -eq 1 ]; then
    docker compose -f compose.social.yaml up --build --wait --wait-timeout 180
    printf 'API Android locale disponible sur http://127.0.0.1:%s/\n' "${LETTRINE_SOCIAL_PORT:-8790}"
fi
printf 'Lettrine est disponible sur http://127.0.0.1:%s/\n' "${LETTRINE_PORT:-8787}"
if [ "${LETTRINE_LAN_IP:-127.0.0.1}" != 127.0.0.1 ]; then
    printf 'Jouer entre amis sur le même réseau : http://%s:%s/salons.html\n' "$LETTRINE_LAN_IP" "${LETTRINE_LAN_PORT:-8788}"
fi
