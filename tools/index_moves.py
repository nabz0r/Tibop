#!/usr/bin/env python3
"""Calcule les statistiques de coups d’une campagne terminée et les charge dans PostgreSQL."""

import argparse
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sqlite3
import subprocess
import sys

from import_campaign import DEFAULT_PROFILE, ROOT, archive_source, csv_bytes, expected_count, sqlite_source


PREMIUM_CELLS = {
    "tw": ((0, 0), (0, 7), (0, 14), (7, 0), (7, 14), (14, 0), (14, 7), (14, 14)),
    "dw": ((1, 1), (2, 2), (3, 3), (4, 4), (7, 7), (10, 10), (11, 11), (12, 12),
           (13, 13), (1, 13), (2, 12), (3, 11), (4, 10), (10, 4), (11, 3), (12, 2), (13, 1)),
    "tl": ((1, 5), (1, 9), (5, 1), (5, 5), (5, 9), (5, 13), (9, 1), (9, 5), (9, 9),
           (9, 13), (13, 5), (13, 9)),
    "dl": ((0, 3), (0, 11), (2, 6), (2, 8), (3, 0), (3, 7), (3, 14), (6, 2), (6, 6),
           (6, 8), (6, 12), (7, 3), (7, 11), (8, 2), (8, 6), (8, 8), (8, 12), (11, 0),
           (11, 7), (11, 14), (12, 6), (12, 8), (14, 3), (14, 11)),
}
PREMIUM_AT = {position: premium for premium, positions in PREMIUM_CELLS.items() for position in positions}
ACTION_TYPES = ("play", "pass", "exchange")
SCHEMA = ROOT / "infra/move_analytics.sql"
DEFAULT_ARCHIVE = ROOT / "data/campaign/duels-fr-100000.jsonl.zst"
DEFAULT_METADATA = ROOT / "data/campaign/metadata.json"


@dataclass
class ScoreCount:
    count: int = 0
    score: int = 0

    def add(self, score):
        self.count += 1
        self.score += score

    @property
    def mean(self):
        return self.score / self.count if self.count else None


@dataclass
class WordCount(ScoreCount):
    bingos: int = 0
    openers: int = 0
    premium_turns: int = 0
    opening_score: int = 0
    opening_bingos: int = 0


@dataclass
class PremiumCount(ScoreCount):
    tiles: int = 0


class Analytics:
    def __init__(self, profile, bingo_tiles):
        self.profile = profile
        self.bingo_tiles = bingo_tiles
        self.game_ids = set()
        self.actions = Counter()
        self.exchange_sizes = Counter()
        self.exchange_letters = Counter()
        self.words = {}
        self.cells = {(row, col): ScoreCount() for row in range(15) for col in range(15)}
        self.premiums = {premium: PremiumCount() for premium in PREMIUM_CELLS}
        self.total = ScoreCount()
        self.premium_turns = ScoreCount()
        self.bingos = 0
        self.openings = 0

    def add_game(self, game):
        game_id = game["id"]
        if game_id in self.game_ids:
            raise ValueError(f"Partie dupliquée dans la source : {game_id}.")
        if game.get("profile") not in (None, "", self.profile):
            raise ValueError(f"Profil incohérent dans la partie {game_id}.")
        moves = game["moves"]
        if len(moves) != game["turns"]:
            raise ValueError(f"Nombre de tours incohérent dans la partie {game_id}.")
        self.game_ids.add(game_id)
        opening = True
        for move in moves:
            kind = move["type"]
            if kind not in ACTION_TYPES:
                raise ValueError(f"Action inconnue dans la partie {game_id} : {kind}.")
            self.actions[kind] += 1
            if kind == "play":
                self.add_play(move, opening)
                opening = False
            if kind == "exchange":
                exchanged = move["exchanged"]
                self.exchange_sizes[len(exchanged)] += 1
                self.exchange_letters.update(exchanged)

    def add_play(self, move, opening):
        score, tiles = move["score"], move["tiles"]
        word = move["word"]
        if not word or not tiles:
            raise ValueError("Un placement doit comporter un mot principal et au moins un jeton.")
        bingo = len(tiles) == self.bingo_tiles
        self.total.add(score)
        self.bingos += bingo
        self.openings += opening
        stats = self.words.get(word)
        if stats is None:
            stats = self.words[word] = WordCount()
        stats.add(score)
        stats.bingos += bingo
        if opening:
            stats.openers += 1
            stats.opening_score += score
            stats.opening_bingos += bingo

        activated = set()
        for tile in tiles:
            position = tile["row"], tile["col"]
            self.cells[position].add(score)
            premium = PREMIUM_AT.get(position)
            if premium:
                self.premiums[premium].tiles += 1
                activated.add(premium)
        for premium in activated:
            self.premiums[premium].add(score)
        if activated:
            stats.premium_turns += 1
            self.premium_turns.add(score)

    def word_rows(self):
        for word, counts in self.words.items():
            yield (self.profile, word, counts.count, counts.mean, counts.bingos,
                   counts.openers, counts.premium_turns)

    def metrics(self, source):
        turns = sum(self.actions.values())
        openings = sorted(
            ((word, stat) for word, stat in self.words.items() if stat.openers),
            key=lambda item: (-item[1].openers, item[0]),
        )[:50]
        return {
            "source": source,
            "summary": {
                "games": len(self.game_ids), "turns": turns, "plays": self.total.count,
                "words": len(self.words), "openings": self.openings, "bingos": self.bingos,
                "bingo_rate": self.bingos / self.total.count if self.total.count else None,
                "mean_score": self.total.mean,
            },
            "actions": {
                kind: {"count": self.actions[kind], "rate": self.actions[kind] / turns if turns else 0}
                for kind in ACTION_TYPES
            },
            "openings": [
                {"word": word, "count": stat.openers, "mean_score": stat.opening_score / stat.openers,
                 "bingos": stat.opening_bingos}
                for word, stat in openings
            ],
            "heatmap": [
                {"row": row, "col": col, "count": stat.count, "mean_score": stat.mean,
                 "premium": PREMIUM_AT.get((row, col))}
                for (row, col), stat in self.cells.items()
            ],
            "premiums": {
                "classes": {
                    key: {"tiles": stat.tiles, "turns": stat.count, "mean_score": stat.mean}
                    for key, stat in self.premiums.items()
                },
                "with_premium": {"turns": self.premium_turns.count, "mean_score": self.premium_turns.mean},
                "without_premium": {
                    "turns": self.total.count - self.premium_turns.count,
                    "mean_score": (
                        (self.total.score - self.premium_turns.score) / (self.total.count - self.premium_turns.count)
                        if self.total.count > self.premium_turns.count else None
                    ),
                },
            },
            "exchanges": {
                "count": self.actions["exchange"],
                "tiles": sum(self.exchange_letters.values()),
                "by_size": dict(sorted(self.exchange_sizes.items())),
                "by_letter": dict(sorted(self.exchange_letters.items())),
            },
        }


def summarize(profile, metadata, command, environment, source_name, archive_sha256):
    expected = expected_count(metadata, profile)
    settings = json.loads((ROOT / "data/profiles" / f"{profile}.json").read_text(encoding="utf-8"))
    if settings["board"]["layout"] != "classic-15":
        raise ValueError("Cette analyse attend le plateau classique 15 × 15.")
    counts = Analytics(profile, settings["rules"]["bingo_tiles"])
    with subprocess.Popen(command, env=environment, cwd=ROOT, stdout=subprocess.PIPE) as reader:
        try:
            for line in reader.stdout:
                counts.add_game(json.loads(line))
        except BaseException:
            reader.terminate()
            raise
        status = reader.wait()
    if status:
        raise RuntimeError(f"La lecture de la campagne a échoué avec le code {status}.")
    if len(counts.game_ids) != expected:
        raise ValueError(f"La source contient {len(counts.game_ids)} parties, contre {expected} attendues.")
    campaign = json.loads(metadata["campaign"])
    source = {
        "scope": "simulation", "profile": profile, "games": expected,
        "archive": source_name, "archive_sha256": archive_sha256,
        "seed": campaign["seed"], "lexicon": campaign.get("lexicon"),
        "lexicon_sha256": campaign["lexicon_sha256"],
        "indexed_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }
    return counts, counts.metrics(source)


SQL_BEFORE = b"""BEGIN;
SET LOCAL client_min_messages TO warning;
DO $$ BEGIN PERFORM pg_advisory_xact_lock(24876, 40752); END $$;
"""
SQL_COPY_WORDS = b"""
CREATE TEMP TABLE incoming_words (LIKE lab_word_stats) ON COMMIT DROP;
CREATE TEMP TABLE incoming_metrics (LIKE lab_move_metrics) ON COMMIT DROP;
COPY incoming_words FROM STDIN WITH (FORMAT csv);
"""
SQL_COPY_METRICS = b"""\\.
COPY incoming_metrics FROM STDIN WITH (FORMAT csv);
"""
SQL_AFTER = b"""\\.
DELETE FROM lab_word_stats target USING incoming_metrics source
WHERE source.key = 'source' AND source.profile = target.profile;
DELETE FROM lab_move_metrics target USING incoming_metrics source
WHERE source.key = 'source' AND source.profile = target.profile;
INSERT INTO lab_word_stats SELECT * FROM incoming_words;
INSERT INTO lab_move_metrics SELECT * FROM incoming_metrics;
ANALYZE lab_word_stats;
COMMIT;
"""


def write_database(counts, metrics, compose_file):
    command = ["docker", "compose"]
    if compose_file:
        command += ["-f", str(compose_file.resolve())]
    command += ["exec", "-T", "postgres", "psql", "-X", "-q", "-v", "ON_ERROR_STOP=1",
                "-U", "lettrine", "-d", "lettrine"]
    metric_rows = [(counts.profile, key, json.dumps(value, ensure_ascii=False)) for key, value in metrics.items()]
    script = b"".join((
        SQL_BEFORE, SCHEMA.read_bytes(), SQL_COPY_WORDS, csv_bytes(counts.word_rows()),
        SQL_COPY_METRICS, csv_bytes(metric_rows), SQL_AFTER,
    ))
    subprocess.run(command, input=script, cwd=ROOT, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", default=DEFAULT_PROFILE, help="Identifiant du profil de la campagne")
    source_choice = parser.add_mutually_exclusive_group()
    source_choice.add_argument("--archive", type=Path, help="Archive JSONL compressée avec Zstandard")
    source_choice.add_argument("--db", type=Path, help="Base SQLite terminée, lue via l’export JSONL")
    parser.add_argument("--metadata", type=Path, help="Manifeste JSON de l’archive")
    parser.add_argument("--binary", type=Path, default=ROOT / "target/release/lettrine")
    parser.add_argument("--compose-file", type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9-]{1,64}", args.profile):
        parser.error("Identifiant de profil invalide.")
    if bool(args.archive) != bool(args.metadata):
        parser.error("--archive et --metadata sont à fournir ensemble.")
    if not args.archive and not args.db and args.profile != DEFAULT_PROFILE:
        parser.error("Indiquer la source de la campagne pour ce profil.")
    try:
        if args.db:
            metadata, command, environment = sqlite_source(args.binary, args.db)
            source_name = args.db.name
            archive_sha256 = None
        else:
            archive = args.archive or DEFAULT_ARCHIVE
            manifest = args.metadata or DEFAULT_METADATA
            metadata, command, environment = archive_source(archive, manifest)
            source_name = archive.name
            archive_sha256 = json.loads(manifest.read_text(encoding="utf-8")).get("archive_sha256")
        counts, metrics = summarize(args.profile, metadata, command, environment, source_name, archive_sha256)
        write_database(counts, metrics, args.compose_file)
    except (OSError, sqlite3.Error, ValueError, RuntimeError, KeyError, subprocess.CalledProcessError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    print(f"{args.profile} · {metrics['summary']['games']} parties · "
          f"{metrics['summary']['turns']} tours · {metrics['summary']['words']} mots distincts.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
