#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
root=$(cd .. && pwd)
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/bin/java ]]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
words="${LETTRINE_WORDS:-$root/data/fr/words.txt}"
words_label="${LETTRINE_WORDS_LABEL:-Grammalecte 7.7}"
words_license="${LETTRINE_WORDS_LICENSE:-MPL-2.0}"
if [[ ! -s "$words" ]]; then
    printf 'Dictionnaire absent. Définir LETTRINE_WORDS avec un fichier UTF-8, un mot par ligne.\n' >&2
    exit 1
fi
if [[ "$words" != "$root/data/fr/words.txt" && -z "${LETTRINE_WORDS_LABEL:-}" ]]; then
    words_label="Liste importée"
    words_license="Droits à vérifier avant redistribution"
fi
gradle_bin="${GRADLE_BIN:-}"
if [[ -z "$gradle_bin" ]]; then
    gradle_bin=./gradlew
fi
generated=app/build/generated/lettrineAssets
mkdir -p "$generated" artifacts
if [[ "$words" == "$root/data/fr/words.txt" && -f "$root/data/fr/README.md" ]]; then
    cp "$root/data/fr/README.md" "$generated/lexicon-notice.md"
    cp "$root/data/fr/MPL-2.0.txt" "$generated/MPL-2.0.txt"
else
    rm -f "$generated/lexicon-notice.md"
    rm -f "$generated/MPL-2.0.txt"
fi
if [[ -f "$root/data/models/fr-bots-v1.json" ]]; then
    cp "$root/data/models/fr-bots-v1.json" "$generated/bot-policy.json"
fi
python3 scripts/prepare_words.py "$words" "$words_label" "$words_license" "$generated"
./scripts/prepare_model.sh
if [[ "${LETTRINE_GRADLE_OFFLINE:-0}" == 1 ]]; then
    "$gradle_bin" --offline :app:assembleDebug
else
    "$gradle_bin" :app:assembleDebug
fi
cp app/build/outputs/apk/debug/app-debug.apk artifacts/lettrine.apk.part
mv artifacts/lettrine.apk.part artifacts/lettrine.apk
