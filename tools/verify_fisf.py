#!/usr/bin/env python3
"""Recompute the score, clocks and tile inventory of a NAB–Macondo campaign."""

import argparse
from collections import Counter
from hashlib import sha256
import json
import math
from pathlib import Path
import unicodedata

from index_moves import PREMIUM_AT, ROOT


def normalized(line):
    word = line.strip().upper().replace("Œ", "OE").replace("Æ", "AE")
    return "".join(c for c in unicodedata.normalize("NFD", word) if not unicodedata.category(c).startswith("M"))


def words_and_score(board, placed, direction, points):
    assert 1 <= len(placed) <= 7 and not (board.keys() & placed.keys())
    assert all(0 <= y < 15 and 0 <= x < 15 for y, x in placed)
    assert (7, 7) in placed if not board else any(
        (y + dy, x + dx) in board for y, x in placed
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1))
    )
    grid = board | placed

    def line(y, x, dy, dx):
        while (y - dy, x - dx) in grid:
            y, x = y - dy, x - dx
        cells = []
        while (y, x) in grid:
            cells.append((y, x))
            y, x = y + dy, x + dx
        return cells

    assert direction in ("H", "V")
    dy, dx = (0, 1) if direction == "H" else (1, 0)
    main = line(*next(iter(placed)), dy, dx)
    assert placed.keys() <= set(main)
    lines = [main, *(line(y, x, dx, dy) for y, x in placed)]
    words = []
    score = 50 if len(placed) == 7 else 0
    for cells in lines:
        if len(cells) < 2:
            continue
        words.append("".join(grid[cell][0] for cell in cells))
        base, multiplier = 0, 1
        for cell in cells:
            letter, blank = grid[cell]
            premium = PREMIUM_AT.get(cell) if cell in placed else None
            base += (0 if blank else points[letter]) * (3 if premium == "tl" else 2 if premium == "dl" else 1)
            multiplier *= 3 if premium == "tw" else 2 if premium == "dw" else 1
        score += base * multiplier
    assert words
    return "".join(grid[cell][0] for cell in main), words, score


def verify_game(game, inventory, points, valid, clock_ms):
    board = {}
    scores, clocks = [0, 0], [clock_ms, clock_ms]
    racks = list(map(Counter, game["initial_racks"]))
    assert all(sum(rack.values()) == 7 for rack in racks)
    initial = racks[0] + racks[1]
    assert not (initial - inventory)
    bag = inventory - initial
    passes, blocked_passes = 0, 0
    challenged_words, cancelled, timed_out_turns = 0, 0, 0
    end = None
    for index, turn in enumerate(game["turns"]):
        seat = index % 2
        assert end is None and turn["turn"] == index + 1 and turn["seat"] == seat
        assert Counter(turn["rack_before"]) == racks[seat] and turn["bag_before"] == sum(bag.values())
        can_exchange = all(clocks) and sum(bag.values()) >= 7
        assert turn["exchange_allowed"] == can_exchange
        elapsed = turn["elapsed_ms"]
        assert isinstance(elapsed, int) and (elapsed > 0 if clocks[seat] else elapsed == 0)
        clocks[seat] = max(0, clocks[seat] - elapsed)
        assert turn["clocks_ms"] == clocks and turn["timed_out"] == (clocks[seat] == 0)
        timed_out_turns += int(turn["timed_out"])
        tiles = turn["tiles"]
        place = {(t["row"], t["col"]): (t["letter"], t["blank"]) for t in tiles}
        returned = Counter(turn["exchanged"])
        spent = Counter()
        challenge = Counter(turn["challenged_words"])
        challenged_words += sum(challenge.values())
        expected_penalty = sum(n for word, n in challenge.items() if word in valid) * 5
        assert expected_penalty == turn["challenger_penalty"]
        withdraw = any(word not in valid for word in challenge)
        assert withdraw == turn["placement_cancelled"]
        scores[1 - seat] -= expected_penalty
        if place:
            assert not turn["timed_out"] and not returned and len(place) == len(tiles)
            word, words, score = words_and_score(board, place, turn["direction"], points)
            assert word == turn["word"] and Counter(words) == Counter(turn["words"])
            assert not (challenge - Counter(words))
            proposed = Counter("?" if blank else letter for letter, blank in place.values())
            assert not (proposed - racks[seat])
            can_challenge = bool(clocks[1 - seat] or bag or proposed != racks[seat])
            assert not challenge or can_challenge
            if withdraw:
                assert turn["type"] == "pass" and turn["score"] == 0
                cancelled += 1
            else:
                assert all(word in valid for word in words) or not can_challenge
                assert turn["type"] == "play" and turn["score"] == score
                scores[seat] += score
                spent = proposed
                board.update(place)
        else:
            assert not challenge and not tiles and not turn["words"] and turn["score"] == 0
            if turn["type"] == "exchange":
                assert can_exchange and not turn["timed_out"] and returned
                spent = returned
            else:
                assert turn["type"] == "pass" and not returned
        assert not (spent - racks[seat])
        kept = racks[seat] - spent
        after = Counter(turn["rack_after"])
        assert not (kept - after)
        drawn = after - kept
        assert not (drawn - bag) and sum(drawn.values()) == min(sum(spent.values()), sum(bag.values()))
        bag = (bag - drawn) + returned
        racks[seat] = after
        assert turn["bag_after"] == sum(bag.values())
        passes = passes + 1 if turn["type"] == "pass" else 0
        if turn.get("no_legal_placement"):
            assert turn["type"] == "pass" and not can_exchange and not turn["timed_out"]
            blocked_passes += 1
        else:
            blocked_passes = 0
        if not any(clocks):
            end = "both_clocks_expired"
        elif not bag and not after:
            end = {"rack_out": {"seat": seat}}
        elif passes == 6:
            end = "six_passes"

    assert list(map(Counter, game["final_racks"])) == racks
    if game["end"] == "blocked":
        assert end is None and (blocked_passes >= 2 or blocked_passes == 1 and not all(clocks))
    else:
        assert game["end"] == end and end is not None
    residues = [sum(points[tile] * n for tile, n in rack.items()) for rack in racks]
    adjustments = [-value for value in residues]
    if isinstance(end, dict):
        adjustments[end["rack_out"]["seat"]] += sum(residues)
    assert adjustments == game["adjustments"]
    assert [a + b for a, b in zip(scores, adjustments)] == game["scores"]
    return challenged_words, cancelled, timed_out_turns


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("words", type=Path)
    parser.add_argument("report", type=Path)
    parser.add_argument("games", type=Path)
    args = parser.parse_args()
    if not __debug__:
        parser.error("Désactivez le mode Python optimisé pour vérifier le journal.")
    report = json.loads(args.report.read_bytes())
    profile = json.loads((ROOT / "data/profiles/fr-classic-v1.json").read_bytes())
    assert report["profile"] == profile["id"] and report["initial_clock_ms"] == 1_200_000
    lexicon = args.words.read_bytes()
    assert sha256(lexicon).hexdigest() == report["lexicon_sha256"]
    valid = {normalized(line) for line in lexicon.decode("utf-8").lstrip("\ufeff").splitlines()} - {""}
    assert len(valid) == report["normalized_forms"]
    inventory = Counter({t["symbol"]: t["count"] for t in profile["tiles"]})
    points = {t["symbol"]: t["points"] for t in profile["tiles"]}
    games = turns = 0
    challenged = cancelled = timeouts = 0
    margins = []
    with args.games.open(encoding="utf-8") as journal:
        for row in journal:
            game = json.loads(row)
            seat = games % 2
            try:
                assert (game["seed"], game["nab_seat"]) == (report["first_seed"] + games // 2, seat)
                if seat == 0:
                    pair_racks = game["initial_racks"]
                else:
                    assert pair_racks == game["initial_racks"]
                words, withdrawals, expired = verify_game(game, inventory, points, valid, report["initial_clock_ms"])
                challenged += words
                cancelled += withdrawals
                timeouts += expired
            except AssertionError as error:
                raise AssertionError(f"Graine {game['seed']}, siège NAB {seat}: journal incohérent") from error
            margins.append(game["scores"][seat] - game["scores"][1 - seat])
            games += 1
            turns += len(game["turns"])
    assert games == report["games"] == 2 * report["paired_deals"]
    assert sum(m > 0 for m in margins) == report["nab_wins"]
    assert sum(m == 0 for m in margins) == report["draws"]
    assert sum(m < 0 for m in margins) == report["reference_wins"]
    assert timeouts == report["timed_out_turns"]
    assert challenged == report["challenged_words"] and cancelled == report["cancelled_placements"]
    assert math.isclose((report["nab_wins"] + report["draws"] / 2) / games, report["match_score_rate"])
    assert math.isclose(sum(margins) / games, report["mean_margin"], abs_tol=1e-9)
    print(json.dumps({"games_verified": games, "turns_verified": turns}))


if __name__ == "__main__":
    main()
