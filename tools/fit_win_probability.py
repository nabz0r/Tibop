#!/usr/bin/env python3
"""Calibrate score lead and game phase from labelled same-level self-play."""

import argparse
import json
from pathlib import Path
import subprocess


def phase(remaining):
    return 0 if remaining <= 20 else 1 if remaining <= 45 else 2 if remaining <= 74 else 3


def positions(game, total):
    a = b = placed = 0
    for move in game["moves"][:-1]:
        if move["player"] == 0:
            a += move["score"]
        else:
            b += move["score"]
        placed += len(move["tiles"])
        lead = a - b
        winner = game["winner"]
        won = 0.5 if winner is None else float(winner == int(lead < 0))
        yield phase(total - placed), min(20, (abs(lead) + 10) // 20), won


def model(path, total):
    train = [[[0, 0.0] for _ in range(21)] for _ in range(4)]
    test = [[[0, 0.0] for _ in range(21)] for _ in range(4)]
    games = 0
    with subprocess.Popen(["zstd", "-dc", str(path)], stdout=subprocess.PIPE) as process:
        for line in process.stdout:
            game = json.loads(line)
            if game["levels"][0] != game["levels"][1]:
                continue
            games += 1
            groups = test if (game["id"] // 20) % 5 == 0 else train
            for p, lead, won in positions(game, total):
                groups[p][lead][0] += 1
                groups[p][lead][1] += won
        if process.wait():
            raise RuntimeError(f"Impossible de lire {path}")
    curves = []
    squared = count = 0
    for training, testing in zip(train, test):
        # Pool adjacent bins that violate monotonicity, with a symmetric prior.
        blocks = []
        for index, (n, w) in enumerate(training):
            blocks.append([index, index + 1, n + 8, w + 4])
            while len(blocks) > 1 and blocks[-2][3] / blocks[-2][2] > blocks[-1][3] / blocks[-1][2]:
                right = blocks.pop()
                left = blocks.pop()
                blocks.append([left[0], right[1], left[2] + right[2], left[3] + right[3]])
        values = [0.5]
        for lo, hi, n, w in blocks:
            values.extend([round(max(0.5, min(0.995, w / n)), 5)] * (hi - lo - int(lo == 0)))
        for predicted, (n, w) in zip(values, testing):
            squared += predicted * predicted * n + w * (1 - 2 * predicted)
            count += n
        curves.append(values)
    return {"phases": curves, "same_level_games": games, "heldout_positions": count,
            "brier": round(squared / count, 5) if count else None}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+", help="profile,total_tiles,archive.zst")
    parser.add_argument("--output", type=Path, default=Path("data/models/win-model.json"))
    args = parser.parse_args()
    profiles = {}
    for spec in args.inputs:
        profile, total, path = spec.split(",", 2)
        profiles[profile] = model(Path(path), int(total))
        print(profile, profiles[profile]["heldout_positions"], profiles[profile]["brier"], flush=True)
    args.output.write_text(json.dumps({"profiles": profiles}, indent=2) + "\n")


if __name__ == "__main__":
    main()
