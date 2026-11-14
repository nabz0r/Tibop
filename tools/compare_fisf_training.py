#!/usr/bin/env python3
"""Compare two verified NAB–Macondo campaigns on the same seat-swapped draws."""

import argparse
import json
import math
from pathlib import Path
from statistics import mean, stdev


SHARED_FIELDS = (
    "schema", "simulation", "profile", "lexicon", "lexicon_sha256", "normalized_forms",
    "reference", "reference_binary_sha256", "nab_search", "initial_clock_ms",
    "clock_model", "reference_startup_deadline_ms", "reference_response_grace_ms",
    "challenge_policy", "first_seed", "paired_deals", "games", "elo_method",
)


def load_campaign(report, journal):
    games = {}
    with journal.open(encoding="utf-8") as source:
        for line in source:
            game = json.loads(line)
            seed, seat = game["seed"], game["nab_seat"]
            if seat not in (0, 1) or (seed, seat) in games:
                raise ValueError(f"{journal.name} — graine {seed}, siège {seat} invalide ou répété")
            margin = game["scores"][seat] - game["scores"][1 - seat]
            games[seed, seat] = (tuple(game["initial_racks"]), margin)

    expected = {
        (seed, seat)
        for seed in range(report["first_seed"], report["first_seed"] + report["paired_deals"])
        for seat in (0, 1)
    }
    if games.keys() != expected or len(games) != report["games"]:
        raise ValueError(f"{journal.name} — journal incomplet ou graines inattendues")

    margins = [margin for _, margin in games.values()]
    wins = sum(margin > 0 for margin in margins)
    draws = sum(margin == 0 for margin in margins)
    losses = len(margins) - wins - draws
    rate = (wins + 0.5 * draws) / len(margins)
    smoothed_rate = (wins + 0.5 * draws + 0.5) / (len(margins) + 1)
    elo = 400 * math.log10(smoothed_rate / (1 - smoothed_rate))
    if (
        (wins, draws, losses) != (report["nab_wins"], report["draws"], report["reference_wins"])
        or not math.isclose(rate, report["match_score_rate"])
        or not math.isclose(mean(margins), report["mean_margin"], abs_tol=1e-9)
        or not math.isclose(elo, report["relative_elo"], abs_tol=1e-9)
    ):
        raise ValueError(f"{journal.name} — les résultats ne correspondent pas au rapport")

    return games


def match_points(margin):
    return 0.5 + 0.5 * ((margin > 0) - (margin < 0))


def estimate(differences):
    average = mean(differences)
    radius = 1.96 * stdev(differences) / math.sqrt(len(differences))
    return {"estimate": average, "confidence_interval_95": [average - radius, average + radius]}


def summary(report):
    fields = (
        "nab_policy", "nab_model_sha256", "nab_model_promoted", "nab_base", "nab_wins",
        "draws", "reference_wins", "match_score_rate", "paired_confidence_interval_95",
        "mean_margin", "relative_elo", "challenged_words", "cancelled_placements",
        "timed_out_turns",
    )
    return {field: report[field] for field in fields if field in report}


def compare(base_report, base_journal, trained_report, trained_journal):
    if base_report["nab_policy"] != "base" or trained_report["nab_policy"] != "trained":
        raise ValueError("Les rapports doivent désigner explicitement NAB de base puis NAB entraîné")
    if not trained_report["nab_model_sha256"] or base_report.get("nab_model_sha256"):
        raise ValueError("L’empreinte du modèle entraîné doit figurer dans son rapport")
    mismatch = [field for field in SHARED_FIELDS if base_report[field] != trained_report[field]]
    if mismatch:
        raise ValueError("Paramètres de campagne différents : " + ", ".join(mismatch))
    pairs = base_report["paired_deals"]
    if pairs < 2:
        raise ValueError("Il faut au moins deux tirages appariés")

    base = load_campaign(base_report, base_journal)
    trained = load_campaign(trained_report, trained_journal)
    point_differences, margin_differences = [], []
    first_seed = base_report["first_seed"]
    for seed in range(first_seed, first_seed + pairs):
        racks = base[seed, 0][0]
        if any(campaign[seed, seat][0] != racks for campaign in (base, trained) for seat in (0, 1)):
            raise ValueError(f"Graine {seed} — chevalets initiaux différents")
        per_game_points, per_game_margins = [], []
        for seat in (0, 1):
            base_margin, trained_margin = base[seed, seat][1], trained[seed, seat][1]
            per_game_points.append(match_points(trained_margin) - match_points(base_margin))
            per_game_margins.append(trained_margin - base_margin)
        point_differences.append(mean(per_game_points))
        margin_differences.append(mean(per_game_margins))

    return {
        "schema": "wordrivet.fisf-training-comparison.v1",
        **{field: base_report[field] for field in SHARED_FIELDS if field not in ("schema", "games")},
        "games_per_policy": len(base),
        "comparison": "trained_minus_base",
        "base": summary(base_report),
        "trained": summary(trained_report),
        "match_score_rate_difference": estimate(point_differences),
        "mean_score_margin_difference": estimate(margin_differences),
        "descriptive_relative_elo_difference": trained_report["relative_elo"] - base_report["relative_elo"],
        "confidence_interval_method": "Normal approximation using the standard error of the within-seed differences, averaged over both seats.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base_report", type=Path)
    parser.add_argument("base_journal", type=Path)
    parser.add_argument("trained_report", type=Path)
    parser.add_argument("trained_journal", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        result = compare(
            json.loads(args.base_report.read_bytes()), args.base_journal,
            json.loads(args.trained_report.read_bytes()), args.trained_journal,
        )
        with args.output.open("x", encoding="utf-8") as output:
            json.dump(result, output, ensure_ascii=False, indent=2)
            output.write("\n")
    except (OSError, KeyError, ValueError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
