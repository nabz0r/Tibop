import hashlib
import json
from pathlib import Path
import re
import sys
import unicodedata

source, label, license_name, output, *language = sys.argv[1:]
language = language[0] if language else "fr"
words = {}
for line in Path(source).read_text(encoding="utf-8-sig").splitlines():
    line = line.strip().upper().replace("Œ", "OE").replace("Æ", "AE")
    word = "".join(
        char for char in unicodedata.normalize("NFKD", line)
        if not unicodedata.combining(char)
    )
    if re.fullmatch(r"[A-Z]{2,15}", word):
        words[word] = None
if not words:
    raise SystemExit("Le fichier ne contient aucun mot A–Z de 2 à 15 lettres.")
output = Path(output)
content = ("\n".join(words) + "\n").encode("ascii")
(output / f"words-{language}.txt").write_bytes(content)
metadata = "lexicon.json" if language == "fr" else f"lexicon-{language}.json"
(output / metadata).write_text(json.dumps({
    "label": label,
    "license": license_name,
    "count": len(words),
    "sha256": hashlib.sha256(content).hexdigest(),
}, ensure_ascii=False), encoding="utf-8")
