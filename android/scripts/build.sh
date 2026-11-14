#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
root=$(cd .. && pwd)
case "${1:-}" in
    '') gradle_task=:app:assembleDebug ;;
    --bundle) gradle_task=:app:bundleRelease ;;
    *) printf 'Usage : %s [--bundle]\n' "$0" >&2; exit 2 ;;
esac
if [[ $# -gt 1 ]]; then
    printf 'Usage : %s [--bundle]\n' "$0" >&2
    exit 2
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/bin/java ]]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [[ "$gradle_task" == :app:bundleRelease ]]; then
    upload_key="${LETTRINE_PLAY_KEYSTORE:-$HOME/.android/lettrine/play-upload.p12}"
    upload_password="${LETTRINE_PLAY_PASSWORD_FILE:-$HOME/.android/lettrine/play-upload.password}"
    upload_alias="${LETTRINE_PLAY_KEY_ALIAS:-lettrine-play-upload}"
    if [[ ! -r "$upload_key" || ! -r "$upload_password" ]]; then
        printf 'Signature Google Play indisponible. Fournir la clé et le fichier de mot de passe du compte de publication.\n' >&2
        exit 1
    fi
    jarsigner="${JAVA_HOME:+$JAVA_HOME/bin/}jarsigner"
fi
words="${LETTRINE_WORDS:-$root/data/fr/words.txt}"
words_label="${LETTRINE_WORDS_LABEL:-Grammalecte 7.7}"
words_license="${LETTRINE_WORDS_LICENSE:-MPL-2.0}"
if [[ ! -s "$words" ]]; then
    printf 'Dictionnaire absent. Définir LETTRINE_WORDS avec un fichier UTF-8, un mot par ligne.\n' >&2
    exit 1
fi
if [[ "$words" != "$root/data/fr/words.txt" ]]; then
    words_label="${LETTRINE_WORDS_LABEL:-Liste importée}"
    words_license="${LETTRINE_WORDS_LICENSE:-Usage selon autorisation du fournisseur}"
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
if [[ "$words" == "$root/data/fr/words.txt" ]]; then
    python3 "$root/tools/lexicon.py" classic --output "$generated"
    rm "$generated/words-fr-classic.txt"
else
    rm -f "$generated/words-fr-classic.txt" "$generated/lexicon-fr-classic.json"
fi
python3 scripts/prepare_words.py "$root/data/en/words.txt" ENABLE2K 'Public domain' "$generated" en
cp "$root/data/en/README.md" "$generated/lexicon-notice-en.md"
cp "$root/data/en/ENABLE2K-NOTICE.txt" "$generated/ENABLE2K-NOTICE.txt"
./scripts/prepare_model.sh
if [[ "${LETTRINE_GRADLE_OFFLINE:-0}" == 1 ]]; then
    "$gradle_bin" --offline "$gradle_task"
else
    "$gradle_bin" "$gradle_task"
fi
if [[ "$gradle_task" == :app:bundleRelease ]]; then
    bundle=artifacts/lettrine.aab
    trap 'rm -f "$bundle.part"' EXIT
    "$jarsigner" -keystore "$upload_key" -storepass:file "$upload_password" \
        -signedjar "$bundle.part" app/build/outputs/bundle/release/app-release.aab "$upload_alias"
    "$jarsigner" -verify -strict -keystore "$upload_key" -storepass:file "$upload_password" "$bundle.part" "$upload_alias"
    mv "$bundle.part" "$bundle"
else
    printf 'APK : %s\n' "$root/android/app/build/outputs/apk/debug/app-debug.apk"
fi
