import hashlib
import json
from pathlib import Path
import re
import sys
import unicodedata

source, label, license_name, output = sys.argv[1:]
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
(output / "words-fr.txt").write_bytes(content)
(output / "lexicon.json").write_text(json.dumps({
    "label": label,
    "license": license_name,
    "count": len(words),
    "sha256": hashlib.sha256(content).hexdigest(),
}, ensure_ascii=False), encoding="utf-8")
