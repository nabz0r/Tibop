#!/usr/bin/env python3
"""Simulate complete French and English local games with the Android engine."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
ENGINE = ROOT / "android/app/src/main/java/fr/lettrine/game"
CLASSES = ("Bot", "FrenchGame", "GameLanguage", "Lexicon", "MatchCodec", "MatchReview",
           "MoveExplanation", "PracticePosition")
JSON_CLASSES = ("JSON", "JSONArray", "JSONException", "JSONObject", "JSONStringer", "JSONTokener")
SDK_IMPORTS = re.compile(r"^import (?:static )?(?:android|libcore)\.[^;]+;\n", re.MULTILINE)
SDK_ANNOTATIONS = re.compile(r"@(?:NonNull|Nullable|UnsupportedAppUsage|SystemApi)(?:\([^)]*\))?\s*")


def positive_int(value):
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError("la valeur doit être positive")
    return number


def install_json_sources(source, work):
    # android.jar only provides API stubs. Compile the Android JSON implementation
    # from the SDK in temporary space, without its platform-private annotations.
    files = []
    for name in JSON_CLASSES:
        file = work / f"{name}.java"
        body = (source / file.name).read_text(encoding="utf-8")
        file.write_text(SDK_ANNOTATIONS.sub("", SDK_IMPORTS.sub("", body)), encoding="utf-8")
        files.append(file)
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("games_per_table", nargs="?", type=positive_int, default=200)
    parser.add_argument("seed", nargs="?", type=int, default=2026111403)
    parser.add_argument("workers", nargs="?", type=positive_int, default=4)
    args = parser.parse_args()

    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
               or Path.home() / "Library/Android/sdk")
    android_jar = sdk / "platforms/android-36/android.jar"
    if not android_jar.is_file():
        parser.error(f"SDK Android 36 introuvable ({android_jar}).")
    source = next((folder for folder in sorted((sdk / "sources").glob("android-*/org/json"), reverse=True)
                   if all((folder / f"{name}.java").is_file() for name in JSON_CLASSES)), None)
    if source is None:
        parser.error("Installez Sources for Android SDK depuis le gestionnaire SDK d’Android Studio.")

    java_home = os.environ.get("JAVA_HOME")
    if not java_home and Path("/opt/homebrew/opt/openjdk@17/bin/java").is_file():
        java_home = "/opt/homebrew/opt/openjdk@17"
    java = Path(java_home) / "bin/java" if java_home else "java"
    javac = Path(java_home) / "bin/javac" if java_home else "javac"
    with tempfile.TemporaryDirectory(prefix="wordrivet-multiplayer-") as directory:
        work = Path(directory)
        subprocess.run([javac, "--release", "17", "-cp", android_jar, "-d", work,
                        *install_json_sources(source, work), *(ENGINE / f"{name}.java" for name in CLASSES),
                        Path(__file__).resolve().with_name("MultiplayerValidation.java")], check=True)
        subprocess.run([java, "-Xmx1536m", "-cp", os.pathsep.join(map(str, (work, android_jar))),
                        "fr.lettrine.game.MultiplayerValidation", ROOT, str(args.games_per_table),
                        str(args.seed), str(args.workers)], check=True)


if __name__ == "__main__":
    main()
