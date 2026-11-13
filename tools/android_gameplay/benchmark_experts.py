#!/usr/bin/env python3
"""Rejoue les positions des archives avec le moteur Android NAB Maître."""

import argparse
import csv
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, default=ROOT / "data/expert/sample.json")
    args = parser.parse_args()
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
               or Path.home() / "Library/Android/sdk")
    android_jar = sdk / "platforms/android-36/android.jar"
    if not android_jar.is_file():
        parser.error(f"SDK Android 36 introuvable : {android_jar}")
    java_home = os.environ.get("JAVA_HOME")
    if not java_home and Path("/opt/homebrew/opt/openjdk@17/bin/javac").is_file():
        java_home = "/opt/homebrew/opt/openjdk@17"
    java = Path(java_home) / "bin/java" if java_home else "java"
    javac = Path(java_home) / "bin/javac" if java_home else "javac"
    engine = ROOT / "android/app/src/main/java/fr/lettrine/game"
    classes = ("Bot", "FrenchGame", "GameLanguage", "Lexicon", "PracticePosition", "MatchReview", "MoveExplanation")
    with tempfile.TemporaryDirectory(prefix="wordrivet-experts-") as temporary:
        work = Path(temporary)
        subprocess.run([sys.executable, ROOT / "android/scripts/prepare_words.py", ROOT / "data/fr/words.txt",
                        "Grammalecte 7.7", "MPL-2.0", work], check=True)
        lexicon = json.loads((work / "lexicon.json").read_text())
        policy = json.loads((ROOT / "data/models/fr-bots-v1.json").read_text())
        weights = policy["levels"][3]["weights"]
        keys = ("leave_scale", "blank_weight", "s_weight", "turnover", "end_penalty", "width")
        trained = policy["promoted"] and policy["lexicon_sha256"] == lexicon["sha256"] and 1 <= weights["width"] <= 36
        inputs = [lexicon["sha256"], str(lexicon["count"])]
        if trained:
            inputs.extend(str(weights[key]) for key in keys)
        (work / "inputs.txt").write_text("\n".join(inputs) + "\n")

        archive = json.loads(args.archive.read_text(encoding="utf-8"))
        if archive["schema_version"] != 1:
            parser.error("Schéma d’archive inconnu")
        positions = work / "positions.tsv"
        with positions.open("w", encoding="utf-8", newline="") as output:
            rows = csv.writer(output, delimiter="\t", quoting=csv.QUOTE_NONE, lineterminator="\n")
            for game in archive["games"]:
                turns = game["turns"]
                final_racks = {entry["player"]: entry["rack"] for entry in game.get("end_adjustments", [])}
                for index, turn in enumerate(turns):
                    other_player = 1 - turn["player"]
                    other_rack = next((later["rack"] for later in turns[index + 1:] if later["player"] == other_player),
                                      final_racks.get(other_player))
                    rows.writerow((game["id"], game["dictionary"], turn["index"], turn["player"], turn["rack"],
                                   turn["action"], turn.get("word"), turn.get("coordinate"), turn["score"],
                                   turn.get("top_score"), ",".join(map(str, turn.get("blank_offsets", []))),
                                   "-" if other_rack is None else other_rack))
        compiled = work / "classes"
        compiled.mkdir()
        subprocess.run([javac, "--release", "17", "-cp", android_jar, "-d", compiled,
                        *(engine / f"{name}.java" for name in classes), Path(__file__).with_name("ExpertBenchmark.java")],
                       check=True)
        subprocess.run([java, "-Xmx512m", "-cp", os.pathsep.join(map(str, (compiled, android_jar))),
                        "fr.lettrine.game.ExpertBenchmark", work], check=True)


if __name__ == "__main__":
    main()
