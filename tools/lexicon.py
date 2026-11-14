#!/usr/bin/env python3
"""Build the French word list or measure its overlap with a local reference."""

import argparse
import csv
import hashlib
import io
import json
import re
import unicodedata
import urllib.request
import zipfile
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = "https://www.grammalecte.net/dic/lexique-grammalecte-fr-v7.7.zip"
ARCHIVE_SHA256 = "bdccf2065e252010253171000a38de84581407896b6bc60aca7d4d69dab7e4d2"
MEMBER = "lexique-grammalecte-fr-v7.7.txt"
LEXICON_ID = "fr-grammalecte-7.7-v1"
CLASSIC_ID = "fr-grammalecte-7.7-classic-v1"
CLASSIC_BASE_SHA256 = "8d024eba26b05909beb31a26af5a99ad501efebb895f9488ae1eb09f6487f81a"
CLASSIC_EXCLUSIONS = ROOT / "data" / "fr" / "classic-exclusions.txt"
CLASSIC_EXCLUDED_NOTES = {"symb", "sig", "abty"}
# Independent French senses missing from the source rows for these forms.
CLASSIC_LEXICAL_EXCEPTIONS = {"BA", "DAL", "IN"}
BOARD_WORD = re.compile(r"[A-Z]{2,15}\Z")
LIGATURES = str.maketrans({"œ": "oe", "Œ": "OE", "æ": "ae", "Æ": "AE"})
EXCLUDED_TAGS = {
    "npr", "prn", "patr", "sigle", "abr", "titr", "ponc", "sign", "pfx", "sfx",
    "err", "div", "1isg",
}


def board_form(text):
    """Fold French accents and ligatures without joining punctuated words."""
    decomposed = unicodedata.normalize("NFD", text.translate(LIGATURES))
    letters = "".join(c for c in decomposed if unicodedata.category(c) != "Mn")
    if not letters.isascii():
        return None
    word = letters.upper()
    return word if BOARD_WORD.fullmatch(word) else None


def eligible(row):
    word = row["Flexion"]
    if not word.islower() or word != word.lower():
        return False
    tags = set(row["Étiquettes"].split())
    if tags & EXCLUDED_TAGS or any(tag.startswith("loc.") for tag in tags):
        return False
    return not {"A", "X"}.intersection(row["Sous-dictionnaire"].split("/"))


def source_forms(archive_path):
    if archive_path:
        data = archive_path.read_bytes()
    else:
        with urllib.request.urlopen(SOURCE, timeout=60) as archive:
            data = archive.read()
    if hashlib.sha256(data).hexdigest() != ARCHIVE_SHA256:
        raise ValueError("L’archive Grammalecte ne correspond pas à la version 7.7 attendue.")

    with zipfile.ZipFile(io.BytesIO(data)) as archive, archive.open(MEMBER) as member:
        source = io.TextIOWrapper(member, encoding="utf-8")
        header = next(line for line in source if line.startswith("id\tfid\tFlexion\t"))
        rows = csv.DictReader(source, fieldnames=header.rstrip("\r\n").split("\t"), delimiter="\t", quoting=csv.QUOTE_NONE)
        for row in rows:
            if not row.get("Flexion") or not eligible(row):
                continue
            word = board_form(row["Flexion"])
            if word:
                yield word, row


def build(output, archive_path):
    frequencies = {}
    for word, row in source_forms(archive_path):
        frequency = float(row["Fréquence"])
        frequencies[word] = max(frequencies.get(word, 0), frequency)

    words = sorted(frequencies, key=lambda word: (-frequencies[word], word))
    contents = ("\n".join(words) + "\n").encode("ascii")
    manifest = {
        "id": LEXICON_ID,
        "language": "fr",
        "license": "MPL-2.0",
        "source": SOURCE,
        "source_sha256": ARCHIVE_SHA256,
        "sha256": hashlib.sha256(contents).hexdigest(),
        "word_count": len(words),
        "two_letter_count": sum(len(word) == 2 for word in words),
        "counts_by_length": dict(sorted(Counter(map(len, words)).items())),
    }
    output.mkdir(parents=True, exist_ok=True)
    (output / "words.txt").write_bytes(contents)
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def classic_exclusions(archive_path):
    marked = set()
    lexical = set(CLASSIC_LEXICAL_EXCEPTIONS)
    for word, row in source_forms(archive_path):
        if CLASSIC_EXCLUDED_NOTES.intersection(row["Notes"].split()):
            marked.add(word)
        else:
            lexical.add(word)
    return sorted(marked - lexical)


def build_classic(output):
    source = ROOT / "data" / "fr" / "words.txt"
    contents = source.read_bytes()
    base_sha256 = hashlib.sha256(contents).hexdigest()
    base_manifest = json.loads(source.with_name("manifest.json").read_text(encoding="utf-8"))
    if base_manifest["id"] != LEXICON_ID or base_sha256 != base_manifest["sha256"]:
        raise ValueError("La liste française ne correspond pas au manifeste.")
    if base_sha256 != CLASSIC_BASE_SHA256:
        raise ValueError("Le lexique classique exige la liste Grammalecte utilisée pour calculer ses exclusions.")
    excluded = read_words(CLASSIC_EXCLUSIONS)
    words = contents.decode("ascii").splitlines()
    # Keep the original frequency order used by the move generator.
    curated = [word for word in words if word not in excluded]
    if len(words) - len(curated) != len(excluded):
        raise ValueError("Une exclusion classique est absente de la liste française.")
    data = ("\n".join(curated) + "\n").encode("ascii")
    metadata = {
        "id": CLASSIC_ID,
        "label": "Grammalecte 7.7 · Classique",
        "license": "MPL-2.0",
        "count": len(curated),
        "sha256": hashlib.sha256(data).hexdigest(),
        "base_sha256": base_sha256,
        "excluded_count": len(excluded),
        "excluded": sorted(excluded),
        "two_letter_count": sum(len(word) == 2 for word in curated),
    }
    output.mkdir(parents=True, exist_ok=True)
    (output / "words-fr-classic.txt").write_bytes(data)
    (output / "lexicon-fr-classic.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return metadata


def read_words(path):
    words = set()
    with path.open(encoding="utf-8-sig") as source:
        for number, line in enumerate(source, 1):
            text = line.strip()
            if not text or text.startswith("#"):
                continue
            word = board_form(text)
            if not word:
                raise ValueError(f"{path}:{number} doit contenir un seul mot de 2 à 15 lettres.")
            words.add(word)
    if not words:
        raise ValueError(f"{path} ne contient aucun mot.")
    return words


def compare(reference_path, base_path):
    reference = read_words(reference_path)
    base = read_words(base_path)
    all_lengths = range(2, 16)
    reference_counts = Counter(map(len, reference))
    base_counts = Counter(map(len, base))
    overlap_counts = Counter(map(len, reference & base))

    def metrics(reference_count, base_count, common):
        return {
            "reference": reference_count,
            "base": base_count,
            "common": common,
            "reference_only": reference_count - common,
            "base_only": base_count - common,
            "reference_coverage": round(common / reference_count, 6) if reference_count else None,
        }

    return {
        "total": metrics(len(reference), len(base), sum(overlap_counts.values())),
        "by_length": {
            length: metrics(reference_counts[length], base_counts[length], overlap_counts[length])
            for length in all_lengths
        },
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    build_parser = commands.add_parser("build", help="Générer le lexique français sous MPL-2.0.")
    build_parser.add_argument("--output", type=Path, default=ROOT / "data" / "fr")
    build_parser.add_argument("--archive", type=Path, help="Lire une copie locale de l’archive Grammalecte 7.7.")
    classic_parser = commands.add_parser("classic", help="Générer le lexique Android classique hors ligne.")
    classic_parser.add_argument("--output", type=Path, required=True)
    exclusions_parser = commands.add_parser("classic-exclusions", help="Régénérer les exclusions depuis Grammalecte.")
    exclusions_parser.add_argument("--archive", type=Path, help="Lire une copie locale de l’archive Grammalecte 7.7.")
    compare_parser = commands.add_parser("compare", help="Comparer deux listes locales par comptages agrégés.")
    compare_parser.add_argument("reference", type=Path)
    compare_parser.add_argument("base", type=Path, nargs="?", default=ROOT / "data" / "fr" / "words.txt")
    args = parser.parse_args()
    try:
        if args.command == "classic-exclusions":
            print("\n".join(classic_exclusions(args.archive)))
            return
        if args.command == "build":
            result = build(args.output, args.archive)
        elif args.command == "classic":
            result = build_classic(args.output)
        else:
            result = compare(args.reference, args.base)
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        parser.exit(1, f"{exc}\n")
    print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
