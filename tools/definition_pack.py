#!/usr/bin/env python3
"""Pack a sorted two-column UTF-8 definitions TSV for Android's indexed reader."""

import argparse
import gzip
import re
import struct
from collections import defaultdict
from itertools import product
from pathlib import Path
from string import ascii_uppercase


WORD = re.compile(r"[A-Z]{2,15}\Z")
PREFIXES = tuple("".join(prefix) for prefix in product(ascii_uppercase, repeat=2))
HEADER_BYTES = 4 + 4 * (len(PREFIXES) + 1)
MAX_BLOCK_BYTES = 5 * 1024 * 1024
MAX_COMPRESSED_BYTES = 1024 * 1024


def build(source: Path, destination: Path) -> None:
    blocks = defaultdict(bytearray)
    previous_word = ""
    count = 0
    with source.open(encoding="utf-8") as entries:
        for line_number, line in enumerate(entries, start=1):
            word, separator, gloss = line.rstrip("\n").partition("\t")
            if not separator or not WORD.fullmatch(word) or not gloss or "\t" in gloss or word < previous_word:
                raise ValueError(f"Invalid or out-of-order TSV entry on line {line_number}")
            if len(gloss.encode("utf-8")) > 2048:
                raise ValueError(f"Definition too long on line {line_number}")
            if word != previous_word:
                count += 1
            previous_word = word
            blocks[word[:2]].extend(f"{word}\t{gloss}\n".encode("utf-8"))
    if not count:
        raise ValueError("No definitions found")

    offset = HEADER_BYTES
    offsets = [offset]
    compressed = []
    for prefix in PREFIXES:
        block = blocks.get(prefix)
        if block:
            packet = gzip.compress(block, compresslevel=9, mtime=0)
            if len(block) > MAX_BLOCK_BYTES or len(packet) > MAX_COMPRESSED_BYTES:
                raise ValueError(f"Definition block {prefix} exceeds the Android reader limits")
            compressed.append(packet)
            offset += len(packet)
        offsets.append(offset)
    destination.write_bytes(b"WDF1" + struct.pack(f">{len(offsets)}I", *offsets) + b"".join(compressed))
    print(f"{destination}: {count:,} entries, {offset:,} bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    options = parser.parse_args()
    build(options.source, options.destination)
