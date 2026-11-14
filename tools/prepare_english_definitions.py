#!/usr/bin/env python3
"""Build compact offline lookup from Open English WordNet 2025's WNDB archive.

Usage: prepare_english_definitions.py ARCHIVE ENABLE2K_WORDS OUTPUT_TSV
Source: https://en-word.net/downloads (Open English WordNet, CC BY 4.0).
WordNet data format: https://wordnet.princeton.edu/documentation/wndb5wn
WordNet morphology: https://wordnet.princeton.edu/documentation/morphy7wn
"""
import argparse
from collections import Counter, defaultdict
from hashlib import sha256
from pathlib import Path
import re
from zipfile import ZipFile

PARTS = {'noun': 'noun', 'verb': 'verb', 'adj': 'adjective', 'adv': 'adverb'}
SENSE_POS = {'1': 'noun', '2': 'verb', '3': 'adj', '4': 'adv', '5': 'adj'}
SUFFIXES = {
    'noun': [('s', ''), ('ses', 's'), ('xes', 'x'), ('zes', 'z'), ('ches', 'ch'),
             ('shes', 'sh'), ('men', 'man'), ('ies', 'y')],
    'verb': [('s', ''), ('ies', 'y'), ('es', 'e'), ('es', ''), ('ed', 'e'),
             ('ed', ''), ('ing', 'e'), ('ing', '')],
    'adj': [('er', ''), ('est', ''), ('er', 'e'), ('est', 'e')],
    'adv': [],
}
MAX_LENGTH = 200
ARCHIVE_SHA256 = '38b16326159f51853626b7d24a44c453fa88ab33f06fce5ec8fc5996d1c2be93'
BOARD_WORD = re.compile(r'[a-z]{2,15}')
EXAMPLE = re.compile(r';\s*["“]')


def source_lines(source, name):
    [entry] = [entry for entry in source.namelist() if entry.endswith('/' + name)]
    return source.read(entry).decode('utf-8').splitlines()


def short_gloss(gloss):
    return ' '.join(EXAMPLE.split(gloss.strip(), 1)[0].split())


def sources(source):
    glosses = {}
    exceptions = defaultdict(set)
    for pos in PARTS:
        for line in source_lines(source, 'data.' + pos):
            if line.startswith(' ') or '|' not in line:
                continue
            body, _, gloss = line.partition('|')
            gloss = short_gloss(gloss)
            if gloss:
                glosses[pos, body.split()[0]] = gloss
        for line in source_lines(source, pos + '.exc'):
            if not line.strip():
                continue
            form, *lemmas = line.split()
            if BOARD_WORD.fullmatch(form):
                for lemma in lemmas:
                    if BOARD_WORD.fullmatch(lemma) and form != lemma:
                        exceptions[form, pos].add(lemma)
    senses = defaultdict(list)
    for line in source_lines(source, 'index.sense'):
        key, offset, number, tags = line.split()
        lemma, _, tag = key.partition('%')
        if not BOARD_WORD.fullmatch(lemma):
            continue
        pos = SENSE_POS[tag[0]]
        gloss = glosses.get((pos, offset))
        if gloss:
            senses[lemma, pos].append((-int(tags), int(number), gloss))
    for rows in senses.values():
        rows.sort()
    return senses, exceptions


def stored_senses(form, senses, exceptions, game_words):
    candidates = []
    exact_or_attested = False
    for pos, label in PARTS.items():
        lemmas = {form: 0} if (form, pos) in senses else {}
        for lemma in sorted(exceptions.get((form, pos), ())):
            if (lemma, pos) in senses:
                lemmas[lemma] = 1
        if lemmas:
            exact_or_attested = True
        if not lemmas and form in game_words:
            for suffix, ending in SUFFIXES[pos]:
                if form.endswith(suffix):
                    lemma = form[:-len(suffix)] + ending
                    if (lemma, pos) in senses:
                        lemmas[lemma] = 2
        for lemma, origin in lemmas.items():
            tags, number, gloss = senses[lemma, pos][0]
            if origin == 0:
                text = f'{label.capitalize()} — {gloss}'
            elif origin == 1:
                text = f'{lemma} ({label}, inflected form) — {gloss}'
            else:
                text = f'{lemma} (possible {label} base) — {gloss}'
            candidates.append((origin, tags, number, text))
    candidates.sort()
    if exact_or_attested:
        return [text for origin, _, _, text in candidates if origin != 2], False
    return [text for _, _, _, text in candidates], True


def fit_gloss(text, limit):
    if len(text) <= limit:
        return text
    # Keep a complete definition clause when the original has several sentences.
    for separator in ('; ', '. '):
        head = text.split(separator, 1)[0]
        if len(head) <= limit:
            return head
    return text[:limit].rsplit(' ', 1)[0].rstrip(',;:') + '…'


def combine(senses):
    first, *remaining = senses
    first = fit_gloss(first, MAX_LENGTH)
    for second in remaining:
        space = MAX_LENGTH - len(first) - 3
        if space < 64 or first == second:
            continue
        if len(second) > space:
            second = second.split('; ', 1)[0]
        return f'{first} · {fit_gloss(second, space)}'
    return first


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('game_words', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--recorded-only', action='store_true', help='omit possible bases inferred by suffix rules')
    args = parser.parse_args()
    if sha256(args.archive.read_bytes()).hexdigest() != ARCHIVE_SHA256:
        parser.error('Expected the Open English WordNet 2025 WNDB archive')
    game_words = {word.lower() for word in args.game_words.read_text(encoding='ascii').splitlines()}
    with ZipFile(args.archive) as source:
        senses, exceptions = sources(source)
    forms = game_words | {form for form, _ in senses} | {form for form, _ in exceptions}
    counts = Counter()
    with args.output.open('w', encoding='utf-8', newline='\n') as output:
        for form in sorted(forms):
            matches, guessed = stored_senses(form, senses, exceptions, game_words)
            if not matches or (args.recorded_only and guessed):
                continue
            output.write(f'{form.upper()}\t{combine(matches)}\n')
            counts['definitions'] += 1
            counts['game words'] += form in game_words
            counts['game words with exact or recorded definitions'] += form in game_words and not guessed
    print(f'Game list: {len(game_words):,} words. Output: {args.output.stat().st_size:,} bytes.')
    for key, value in counts.items():
        print(f'{key}: {value:,}')


if __name__ == '__main__':
    main()
