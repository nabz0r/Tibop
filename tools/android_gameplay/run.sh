#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
android_sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/Library/Android/sdk"}}
android_jar="$android_sdk/platforms/android-36/android.jar"
if [[ ! -r "$android_jar" ]]; then
    printf 'SDK Android 36 introuvable (%s).\n' "$android_jar" >&2
    exit 1
fi
java_bin=${JAVA_HOME:+$JAVA_HOME/bin/}java
javac_bin=${JAVA_HOME:+$JAVA_HOME/bin/}javac
mkdir -p "$work/classes"
python3 android/scripts/prepare_words.py data/fr/words.txt 'Grammalecte 7.7' MPL-2.0 "$work"
python3 - "$work" <<'PY'
import json
from pathlib import Path
import sys

output = Path(sys.argv[1])
lexicon = json.loads((output / "lexicon.json").read_text())
policy = json.loads(Path("data/models/fr-bots-v1.json").read_text())
weights = policy["levels"][3]["weights"]
active = policy["promoted"] and policy["lexicon_sha256"] == lexicon["sha256"] and 1 <= weights["width"] <= 36
keys = ("leave_scale", "blank_weight", "s_weight", "turnover", "end_penalty", "width")
values = [str(weights[key]) for key in keys] if active else []
(output / "inputs.txt").write_text("\n".join([lexicon["sha256"], str(lexicon["count"]), *values]) + "\n")
PY
engine=android/app/src/main/java/fr/lettrine/game
"$javac_bin" --release 17 -cp "$android_jar" -d "$work/classes" \
    "$engine/"{Bot,FrenchGame,Lexicon,DailyChallenge,PracticePosition,MatchReview,MoveExplanation}.java \
    tools/android_gameplay/GameplaySimulation.java
"$java_bin" -Xmx512m -cp "$work/classes:$android_jar" fr.lettrine.game.GameplaySimulation \
    "$work" "${1:-40}" "${2:-20261113}" "${3:-4}"
