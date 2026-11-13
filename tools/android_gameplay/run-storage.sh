#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/Library/Android/sdk"}}
platform="$sdk/platforms/android-36/android.jar"
dex="$sdk/build-tools/36.0.0/d8"
adb="$sdk/platform-tools/adb"
javac_bin=${JAVA_HOME:+$JAVA_HOME/bin/}javac
work=$(mktemp -d)
remote=''
device=( "$adb" -s "${1:?Indiquez l’identifiant de l’appareil Android 8 ou plus récent.}" )
cleanup() {
    if [[ -n "$remote" ]]; then "${device[@]}" shell rm -r "$remote" || true; fi
    rm -r "$work"
}
trap cleanup EXIT
mkdir "$work/classes"
"$javac_bin" --release 17 -cp "$platform" -d "$work/classes" \
    android/app/src/main/java/fr/lettrine/game/{GameStore,GameLanguage}.java tools/android_gameplay/GameStoreSimulation.java
"$dex" --min-api 26 --lib "$platform" --output "$work/storage.jar" "$work/classes/fr/lettrine/game/"*.class
remote=$("${device[@]}" shell mktemp -d /data/local/tmp/lettrine-store.XXXXXX)
"${device[@]}" push "$work/storage.jar" "$remote/storage.jar"
"${device[@]}" shell "CLASSPATH=$remote/storage.jar app_process / fr.lettrine.game.GameStoreSimulation $remote"
