#!/usr/bin/env python3
"""Recompute every French reference move from the exported tiles and lexicon."""

import argparse
from collections import Counter
import json
from pathlib import Path
import subprocess

from index_moves import PREMIUM_AT


def verify(game, valid, points, tile_count):
    board = {}
    scores = [0, 0]
    next_rack = {}
    pass_streak = 0
    for i, move in enumerate(game["moves"]):
        seat = i % 2
        assert move["player"] == seat and move["turn"] == i + 1
        rack = Counter(move["rack"])
        assert len(move["rack"]) <= 7
        if seat in next_rack:
            assert not (next_rack[seat] - rack)
        assert 0 <= move["bag_before"] <= tile_count - len(board) - len(move["rack"])
        tiles = move["tiles"]
        if move["type"] != "play":
            assert not tiles and move["score"] == 0
            exchanged = Counter(move["exchanged"])
            assert not (exchanged - rack)
            if move["type"] == "exchange":
                assert exchanged and move["bag_before"] >= 7
            else:
                assert not exchanged
            next_rack[seat] = rack - exchanged
            pass_streak = pass_streak + 1 if not exchanged else 0
            continue

        pass_streak = 0
        assert 1 <= len(tiles) <= 7
        changed = {(t["row"], t["col"]): (t["letter"], t["blank"]) for t in tiles}
        assert len(changed) == len(tiles) and not (changed.keys() & board.keys())
        assert all(0 <= y < 15 and 0 <= x < 15 for y, x in changed)
        assert (7, 7) in changed if not board else any(
            (y + dy, x + dx) in board for y, x in changed
            for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        consumed = Counter("?" if blank else letter for letter, blank in changed.values())
        assert not (consumed - rack)
        next_rack[seat] = rack - consumed
        board.update(changed)

        def line(start, dy, dx):
            y, x = start
            while (y - dy, x - dx) in board:
                y, x = y - dy, x - dx
            cells = []
            while (y, x) in board:
                cells.append((y, x))
                y, x = y + dy, x + dx
            return cells

        direction = (1, 0) if move["direction"] == "V" else (0, 1)
        main = line(next(iter(changed)), *direction)
        assert changed.keys() <= set(main)
        assert "".join(board[c][0] for c in main) == move["word"]
        crosses = [line(c, direction[1], direction[0]) for c in changed]
        words = [cells for cells in [main, *crosses] if len(cells) >= 2]
        assert words
        total = 50 if len(changed) == 7 else 0
        for cells in words:
            assert "".join(board[c][0] for c in cells) in valid
            part = 0
            multiplier = 1
            for c in cells:
                letter, blank = board[c]
                base = 0 if blank else points[letter]
                premium = PREMIUM_AT.get(c) if c in changed else None
                part += base * (3 if premium == "tl" else 2 if premium == "dl" else 1)
                multiplier *= 3 if premium == "tw" else 2 if premium == "dw" else 1
            total += part * multiplier
        assert total == move["score"], (i + 1, total, move["score"])
        scores[seat] += total

    assert len(game["moves"]) == game["turns"]
    residue = [sum(points[c] for c in rack) for rack in game["final_racks"]]
    for seat in (0, 1):
        if seat in next_rack:
            assert not (next_rack[seat] - Counter(game["final_racks"][seat]))
    adjustments = [-r for r in residue]
    if game["end"] == "rack_out":
        outgoing = game["moves"][-1]["player"]
        assert not game["final_racks"][outgoing]
        assert len(board) + sum(map(len, game["final_racks"])) == tile_count
        adjustments[outgoing] += sum(residue)
    else:
        assert game["end"] == "passes" and pass_streak == 6
    assert adjustments == game["adjustments"]
    final = [a + b for a, b in zip(scores, adjustments)]
    assert final == game["scores"]
    assert game["winner"] == (None if final[0] == final[1] else int(final[1] > final[0]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, default=Path("data/campaign/duels-fr-100000.jsonl.zst"))
    parser.add_argument("--output", type=Path, default=Path("data/campaign/verification-fr.json"))
    args = parser.parse_args()
    profile = json.loads(Path("data/profiles/fr-classic-v1.json").read_text())
    points = {t["symbol"]: t["points"] for t in profile["tiles"]}
    tile_count = sum(t["count"] for t in profile["tiles"])
    valid = set(Path(profile["lexicon"]["path"]).read_text().splitlines())
    games = turns = 0
    with subprocess.Popen(["zstd", "-dc", str(args.archive)], stdout=subprocess.PIPE) as reader:
        for row in reader.stdout:
            game = json.loads(row)
            try:
                assert game["id"] == games + 1
                verify(game, valid, points, tile_count)
            except AssertionError as e:
                raise AssertionError(f"Partie {game['id']}: {e}") from e
            games += 1
            turns += game["turns"]
            if games % 10000 == 0:
                print(f"{games:,} parties vérifiées", flush=True)
        if reader.wait():
            raise RuntimeError("lecture de l’archive interrompue")
    report = {"profile": profile["id"], "games": games, "turns": turns,
              "method": "Placement, raccord, mots croisés, chevalets, primes, points et reliquats recalculés sur chaque partie."}
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
