#!/usr/bin/env python3
"""Charge une campagne Lettrine terminée dans PostgreSQL, en une transaction."""

import argparse
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
SQL_BEFORE = b"""BEGIN;
SET LOCAL client_min_messages TO warning;
DO $$ BEGIN PERFORM pg_advisory_xact_lock(24876, 40751); END $$;
"""
SQL_COPY_META = b"""
CREATE TEMP TABLE incoming_meta (key text PRIMARY KEY, value jsonb NOT NULL) ON COMMIT DROP;
COPY incoming_meta FROM STDIN WITH (FORMAT csv);
"""
SQL_COPY_GAMES = b"""\.
CREATE TEMP TABLE incoming_games (payload jsonb NOT NULL) ON COMMIT DROP;
COPY incoming_games FROM STDIN WITH (FORMAT csv);
"""
SQL_AFTER = b"""\.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM lab_meta old JOIN incoming_meta new USING (key)
        WHERE old.key = 'campaign' AND old.value <> new.value
    ) THEN
        RAISE EXCEPTION 'Une autre campagne est deja presente dans PostgreSQL';
    END IF;
END
$$;
INSERT INTO lab_games (id, seed, a, b, score_a, score_b, turns, winner, payload)
SELECT (payload->>'id')::bigint, (payload->>'seed')::bigint,
       (payload->'levels'->>0)::smallint, (payload->'levels'->>1)::smallint,
       (payload->'scores'->>0)::integer, (payload->'scores'->>1)::integer,
       (payload->>'turns')::integer, (payload->>'winner')::smallint, payload
FROM incoming_games
ON CONFLICT (id) DO UPDATE
    SET seed = excluded.seed, a = excluded.a, b = excluded.b,
        score_a = excluded.score_a, score_b = excluded.score_b,
        turns = excluded.turns, winner = excluded.winner, payload = excluded.payload
    WHERE lab_games.payload IS DISTINCT FROM excluded.payload;
DO $$
BEGIN
    IF (SELECT count(*) FROM lab_games) <>
       (SELECT (value->>'completed')::bigint FROM incoming_meta WHERE key = 'public') THEN
        RAISE EXCEPTION 'Le nombre de parties ne correspond pas au manifeste';
    END IF;
END
$$;
INSERT INTO lab_meta (key, value)
SELECT key, value FROM incoming_meta
ON CONFLICT (key) DO UPDATE SET value = excluded.value
WHERE lab_meta.value IS DISTINCT FROM excluded.value;
ANALYZE lab_games;
COMMIT;
"""


def archive_source(archive, manifest):
    document = json.loads(manifest.read_text(encoding="utf-8"))
    expected_digest = document.get("archive_sha256")
    if expected_digest:
        digest = hashlib.sha256()
        with archive.open("rb") as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(block)
        if digest.hexdigest() != expected_digest:
            raise ValueError("L’archive ne correspond pas à l’empreinte du manifeste.")
    values = {key: json.dumps(document[key], ensure_ascii=False) for key in ("campaign", "public")}
    return values, ["zstd", "-dc", "--", str(archive.resolve())], None


def sqlite_source(binary, database):
    con = sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True)
    try:
        values = dict(con.execute("SELECT key, value FROM meta WHERE key IN ('campaign','public')"))
    finally:
        con.close()
    if len(values) != 2:
        raise ValueError("La campagne et ses statistiques doivent être présentes dans SQLite.")
    export = [str(binary.resolve()), "export", "jsonl"]
    filters = {"LETTRINE_LEVEL", "LETTRINE_ELITE", "LETTRINE_QUERY"}
    env = {key: value for key, value in os.environ.items() if key not in filters}
    env["LETTRINE_DB"] = str(database.resolve())
    return values, export, env


def expected_count(values):
    campaign = json.loads(values["campaign"])
    public = json.loads(values["public"])
    count = public["completed"]
    if public["running"] or public.get("error") or count == 0 or count != campaign["target"]:
        raise ValueError("Cette commande importe une campagne terminée.")
    if not all(public[field] == campaign[field] for field in ("target", "seed", "engine", "lexicon_sha256")):
        raise ValueError("Les métadonnées de la campagne ne concordent pas.")
    return count


def csv_bytes(rows):
    buffer = io.StringIO()
    csv.writer(buffer, lineterminator="\n").writerows(rows)
    return buffer.getvalue().encode("utf-8")


def migrate(metadata, export, env, compose_file):
    expected = expected_count(metadata)
    schema = (ROOT / "infra/schema.sql").read_bytes()
    psql = ["docker", "compose"]
    if compose_file:
        psql += ["-f", str(compose_file.resolve())]
    psql += ["exec", "-T", "postgres", "psql", "-X", "-q", "-v", "ON_ERROR_STOP=1",
             "-U", "lettrine", "-d", "lettrine"]
    with subprocess.Popen(psql, cwd=ROOT, stdin=subprocess.PIPE) as loader:
        try:
            loader.stdin.write(SQL_BEFORE + schema + SQL_COPY_META)
            loader.stdin.write(csv_bytes(metadata.items()))
            loader.stdin.write(SQL_COPY_GAMES)
            count = 0
            with subprocess.Popen(export, env=env, cwd=ROOT, stdout=subprocess.PIPE) as source:
                try:
                    for row in source.stdout:
                        # The exporter emits one JSON object per line. Escape CSV's only
                        # special byte within a quoted field and let PostgreSQL parse JSONB.
                        loader.stdin.write(b'"' + row.rstrip(b"\r\n").replace(b'"', b'""') + b'"\n')
                        count += 1
                except BaseException:
                    source.terminate()
                    raise
                status = source.wait()
                if status:
                    raise RuntimeError(f"L’exporteur Lettrine s’est arrêté avec le code {status}.")
            if count != expected:
                raise ValueError(f"La source contient {count} parties, contre {expected} attendues.")
            loader.stdin.write(SQL_AFTER)
        finally:
            loader.stdin.close()
        if loader.wait():
            raise RuntimeError("PostgreSQL a rejeté la transaction.")
    print(f"{count:,}".replace(",", " ") + " parties et leurs statistiques sont dans PostgreSQL.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, default=ROOT / "results/campaign.sqlite")
    parser.add_argument("--binary", type=Path, default=ROOT / "target/release/lettrine")
    parser.add_argument("--archive", type=Path, help="Archive JSONL compressée avec Zstandard")
    parser.add_argument("--metadata", type=Path, help="Manifeste JSON contenant campaign et public")
    parser.add_argument("--compose-file", type=Path)
    args = parser.parse_args()
    if bool(args.archive) != bool(args.metadata):
        parser.error("--archive et --metadata sont à fournir ensemble.")
    try:
        source = archive_source(args.archive, args.metadata) if args.archive else sqlite_source(args.binary, args.db)
        migrate(*source, args.compose_file)
    except (OSError, sqlite3.Error, ValueError, RuntimeError, KeyError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
