#!/usr/bin/env python3
"""Import a selection of public French classic match records for local review."""

import argparse
import hashlib
import json
import re
import urllib.request
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ARCHIVE = "https://www.scrabblecrds.fr/index.php?PG=9"
REPLAY = "https://www.crds.fr/menus/classique/classique.php?ref="
RECORD = "https://www.crds.fr/menus/classique/include/charger.php?ref="
ODS_HISTORY = "https://fisf.net/lofficiel-du-scrabble/"
OFFICIAL_RESULTS = "https://fisf.net/competitions/scrabble-classique/championnat-de-monde/"
SAMPLE = ("150720CHMRF1", "150720CHMRF3", "160726CHPMF1", "211026CHPMF1")
OFFICIAL_SAMPLE_SCORES = {
    "150720CHMRF1": [427, 370],
    "150720CHMRF3": [434, 565],
    "160726CHPMF1": [380, 413],
    "211026CHPMF1": [506, 441],
}
REFERENCE_ID = re.compile(r"[0-9]{6}[A-Z0-9]{3,9}")
COORDINATE = re.compile(r"(?:[A-O](?:[1-9]|1[0-5])|(?:[1-9]|1[0-5])[A-O])")
LETTERS = re.compile(r"[A-Za-z]{2,15}")
RACK = re.compile(r"[A-Z?+\-]*")
EFFECTIVE_EDITIONS = ((2024, 9), (2020, 8), (2016, 7), (2012, 6), (2008, 5), (2004, 4))


def clean_rack(rack):
    if not RACK.fullmatch(rack):
        raise ValueError(f"Chevalet d’archive illisible : {rack!r}.")
    return rack.replace("+", "").replace("-", "")


def top_score(alternatives):
    scores = []
    for option in alternatives.split("$"):
        fields = option.split()
        if len(fields) == 3 and COORDINATE.fullmatch(fields[1]) and fields[2].isdigit():
            scores.append(int(fields[2]))
    return max(scores, default=None)


def parse_turn(index, rack, move, alternatives, marker):
    fields = move.split()
    if not fields or len(fields) < 2:
        raise ValueError(f"Action d’archive illisible : {move!r}.")
    played = fields[0]
    action = played.lower()
    score = int(fields[-1])
    position = fields[1] if len(fields) == 3 and COORDINATE.fullmatch(fields[1]) else None
    turn = {
        "index": index,
        "player": (index - 1) % 2,
        "rack": clean_rack(rack.strip()),
        "action": "place",
        "word": None,
        "coordinate": position,
        "score": score,
        "top_score": top_score(alternatives),
    }
    if action == "fin":
        turn["action"] = "end"
        if len(fields) == 3 and not position:
            turn["rack"] = clean_rack(fields[1])
        if marker.strip().isdigit():
            turn["penalty"] = int(marker)
    elif action.startswith("rejet-"):
        turn["action"] = "exchange"
        turn["exchanged"] = clean_rack(played.split("-", maxsplit=1)[1])
    elif action in {"pas", "passe", "pass"} and not position and not score:
        turn["action"] = "pass"
    elif LETTERS.fullmatch(played):
        turn["word"] = played.upper()
        turn["blank_offsets"] = [offset for offset, letter in enumerate(played) if letter.islower()]
        if marker.strip() == "!" or not position:
            turn["action"] = "invalid"
    else:
        raise ValueError(f"Placement d’archive illisible : {move!r}.")
    return turn


def parse_game(reference, payload):
    parts = payload.decode("utf-8-sig").strip().split("*")
    if len(parts) < 10 or not parts[0] or not parts[2] or not parts[3]:
        raise ValueError(f"Réponse d’archive incomplète pour {reference}.")
    title, _, first, second, raw_date, _ = parts[:6]
    date = datetime.strptime(raw_date.strip(), "%d/%m/%y").date()
    dictionary = next((f"ODS {edition}" for year, edition in EFFECTIVE_EDITIONS if date.year >= year), None)
    turns = []
    adjustments = []
    scores = [0, 0]
    for index, part in enumerate(parts[6:], 1):
        if not part.strip():
            continue
        fields = part.split("#", maxsplit=4)
        if len(fields) < 4:
            raise ValueError(f"Format inattendu au tour {index} de {reference}.")
        turn = parse_turn(index, *fields[:4])
        scores[turn["player"]] += turn["score"] - turn.get("penalty", 0)
        if turn["action"] == "end":
            adjustments.append(turn)
        else:
            turns.append(turn)
    archive_totals = scores if len(adjustments) == 2 else None
    official = OFFICIAL_SAMPLE_SCORES.get(reference)
    return {
        "id": reference,
        "title": title,
        "date": date.isoformat(),
        "players": [" ".join(first.split()), " ".join(second.split())],
        "source": REPLAY + reference,
        "source_sha256": hashlib.sha256(payload).hexdigest(),
        "ruleset": "Classique de compétition",
        "dictionary": dictionary,
        "dictionary_source": ODS_HISTORY,
        "final_scores": official or archive_totals,
        "final_scores_source": OFFICIAL_RESULTS if official else REPLAY + reference,
        "archive_score_totals": archive_totals,
        "score_discrepancy": bool(official and archive_totals and official != archive_totals),
        "turns": turns,
        "end_adjustments": adjustments,
    }


def fetch_game(reference):
    if not REFERENCE_ID.fullmatch(reference):
        raise ValueError(f"Identifiant d’archive non reconnu : {reference!r}.")
    with urllib.request.urlopen(RECORD + reference, timeout=20) as response:
        payload = response.read(256_001)
    if len(payload) > 256_000:
        raise ValueError(f"Réponse d’archive trop volumineuse pour {reference}.")
    return parse_game(reference, payload)


def references():
    return [
        {
            "id": "mondial-classique-2025",
            "title": "Finale mondiale 2025 à Trois-Rivières",
            "players": ["Quentin Mallégol", "King Josaphat Nkouete Chewa"],
            "dictionary": "ODS 9",
            "source": "https://ffsc.fr/endirect/endirect.php?tournoi_id=25341",
            "scores_source": OFFICIAL_RESULTS,
            "match_scores": [[342, 455], [448, 519], [534, 510], [512, 366], [503, 451]],
            "highlight": {
                "player": 0,
                "match": 5,
                "word": "HO",
                "coordinate": "H1",
                "score": 17,
                "source": "https://www.ffsc.fr/mafiche.exporter.scrabblerama.php?numero=513",
                "analysis": "Mallégol ferme les appuis du C et du S en laissant une lettre dans le sac. Le placement neutralise deux scrabbles que son adversaire pouvait former sur le C.",
            },
        },
        {
            "id": "mondial-classique-2026",
            "title": "Finale mondiale 2026 à Tunis",
            "players": ["Ouraga Guy Roland Boli", "Gauthier Houillon"],
            "dictionary": "ODS 9",
            "source": "https://ffsc.fr/endirect/endirect.php?tournoi_id=25971",
            "scores_source": OFFICIAL_RESULTS,
            "match_scores": [[574, 463], [472, 486], [473, 409], [408, 523], [544, 345]],
        },
    ]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("references", nargs="*", help="Références publiques du Comité Dauphiné-Savoie.")
    parser.add_argument("--output", type=Path, default=ROOT / "data" / "expert" / "sample.json")
    args = parser.parse_args()
    try:
        games = [fetch_game(reference) for reference in dict.fromkeys(args.references or SAMPLE)]
        document = {
            "schema_version": 1,
            "archive": ARCHIVE,
            "games": games,
            "references": references(),
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    except (OSError, ValueError) as exc:
        parser.exit(1, f"{exc}\n")
    print(f"{len(games)} parties, {sum(len(game['turns']) for game in games)} tours. {args.output}")


if __name__ == "__main__":
    main()
