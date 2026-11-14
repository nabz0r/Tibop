#!/usr/bin/env python3
"""Join Grammalecte inflected forms to French Wiktionary glosses from Verbarium."""
import argparse
import collections
import csv
import functools
import gzip
import hashlib
import io
import pathlib
import re
import unicodedata
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from lexicon import ROOT as REPO, board_form, source_forms

REVISION = '97d24b8d0dfe415a401a36054a2170724c8ade12'
SOURCE = f'https://raw.githubusercontent.com/verbarium/french-dictionary/{REVISION}/data/'
INPUTS = {
    'lemmas': '9a84f3f0289517dff496b29bff682518d582b2b28441b91643533f095ca22bf0',
    'demonyms': '1ffe8c2f717b15e3d0c836c366826ec6b662bf631a5c82b862404648fd796722',
}
VERB_TENSES = {'ipre':'indicatif présent', 'iimp':'imparfait', 'ipsi':'passé simple', 'ifut':'futur',
               'cond':'conditionnel présent', 'spre':'subjonctif présent', 'simp':'subjonctif imparfait',
               'impe':'impératif', 'ppas':'participe passé', 'ppre':'participe présent'}
PERSONS = {'1sg':'1re pers. sing.', '2sg':'2e pers. sing.', '3sg':'3e pers. sing.',
           '1pl':'1re pers. plur.', '2pl':'2e pers. plur.', '3pl':'3e pers. plur.'}


def csv_input(cache_dir, name):
    filename = name + '.csv.gz'
    target = cache_dir / filename
    if not target.exists():
        with urllib.request.urlopen(SOURCE + filename, timeout=90) as response:
            raw = response.read()
        if hashlib.sha256(raw).hexdigest() != INPUTS[name]:
            raise ValueError('Unexpected SHA-256: ' + filename)
        target.write_bytes(raw)
    else:
        raw = target.read_bytes()
        if hashlib.sha256(raw).hexdigest() != INPUTS[name]:
            raise ValueError('Unexpected SHA-256: ' + str(target))
    return io.TextIOWrapper(gzip.GzipFile(fileobj=io.BytesIO(raw)), encoding='utf8')


def clean_gloss(value):
    return ' '.join(value.split())


def definitions(cache_dir, word_list):
    direct = collections.defaultdict(dict)
    by_verb = {}
    by_noun = {}
    with ThreadPoolExecutor() as executor:
        inputs = list(executor.map(functools.partial(csv_input, cache_dir), INPUTS))
    for source in inputs:
        with source:
            for row in csv.DictReader(source):
                lemma = unicodedata.normalize('NFC', row['lemma'])
                word = board_form(lemma)
                gloss = clean_gloss(row['definition'])
                if not gloss or not word or not lemma.islower():
                    continue
                if row['pos'] == 'verb':
                    by_verb.setdefault(lemma, gloss)
                if row['pos'] == 'noun':
                    by_noun.setdefault(lemma, gloss)
                if word not in word_list:
                    continue
                pos = row.get('pos_fr') or {'noun':'nom', 'adjective':'adjectif'}.get(row['pos'], row['pos'])
                key = (lemma, pos)
                detail = f'{lemma} ({pos}) — {gloss}'
                priority = float(row['zipf_frequency'] or 0)
                direct[word].setdefault(key, (priority, detail))
    return direct, by_verb, by_noun


def inflections(archive, word_list, by_noun):
    verbs = collections.defaultdict(dict)
    plurals = collections.defaultdict(dict)
    # Match exact spelling to an exact lemma, then expose each accent variant under the normalized board form.
    for word, row in source_forms(archive):
        if word not in word_list:
            continue
        form = unicodedata.normalize('NFC', row['Flexion'])
        lemma = unicodedata.normalize('NFC', row['Lemme'])
        if form == lemma:
            continue
        tags = row['Étiquettes'].split()
        if tags[0] == 'nom' and 'pl' in tags and lemma in by_noun:
            plurals[word][form, lemma] = float(row['Fréquence'])
            continue
        if not re.fullmatch(r'v[0-3][a-z_]*', tags[0]):
            continue
        tenses = [VERB_TENSES[tag] for tag in tags if tag in VERB_TENSES]
        if not tenses:
            continue
        people = [PERSONS[tag.rstrip('!')] for tag in tags if tag.rstrip('!') in PERSONS]
        agreement = [label for tag, label in [('mas', 'masculin'), ('fem', 'féminin'), ('sg', 'singulier'), ('pl', 'pluriel')] if tag in tags]
        detail = ', '.join(tenses + (agreement if 'ppas' in tags else people))
        key = (form, lemma)
        frequency = float(row['Fréquence'])
        current = verbs[word].setdefault(key, [frequency, []])
        current[0] = max(current[0], frequency)
        if detail not in current[1]:
            current[1].append(detail)
    return verbs, plurals


def shorten(text, limit):
    if len(text) <= limit:
        return text
    return text[:limit - 1].rsplit(' ', 1)[0].rstrip('.,;:') + '…'


def compile_entries(direct, by_verb, by_noun, conjugated, plurals, limit, gloss_limit):
    for word in sorted(direct.keys() | conjugated.keys() | plurals.keys()):
        direct_entries = sorted(direct.get(word, {}).items(), key=lambda item: -item[1][0])
        verb_entries = sorted(conjugated.get(word, {}).items(), key=lambda item: -item[1][0])
        meanings = []
        visible_spellings = set()
        # Give conjugation one of the visible slots even for homographs such as SAIS or SUIS.
        # Keep distinct spellings/lemmas whenever there is room.
        for index in range(max(len(direct_entries), len(verb_entries))):
            if index < len(verb_entries):
                (form, lemma), (frequency, labels) = verb_entries[index]
                morphology = ' / '.join(labels)
                gloss = by_verb.get(lemma)
                text = f'{form} → {lemma} ({morphology})' + (f' — {gloss}' if gloss else '')
                meanings.append(text)
                visible_spellings.add(form)
            if direct_entries:
                source_index = next((i for i, (key, _) in enumerate(direct_entries) if key[0] not in visible_spellings), 0)
                (spelling, _), (_, entry) = direct_entries.pop(source_index)
                meanings.append(entry)
                visible_spellings.add(spelling)
            if len(meanings) >= limit:
                break
        if len(meanings) < limit:
            noun_entries = sorted(plurals.get(word, {}).items(), key=lambda item: -item[1])
            for (form, lemma), _ in noun_entries:
                meanings.append(f'{form} → {lemma} (nom, pluriel) — {by_noun[lemma]}')
                if len(meanings) == limit:
                    break
        for text in meanings[:limit]:
            yield word, shorten(text, gloss_limit)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=pathlib.Path, required=True)
    parser.add_argument('--cache', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--only-classic', action='store_true')
    parser.add_argument('--max-entries', type=int, default=4)
    parser.add_argument('--max-length', type=int, default=200)
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    allowed = set((REPO / 'data/fr/words.txt').read_text().splitlines())
    if args.only_classic:
        allowed -= set((REPO / 'data/fr/classic-exclusions.txt').read_text().splitlines())
    direct, by_verb, by_noun = definitions(args.cache, allowed)
    verbs, plurals = inflections(args.archive, allowed, by_noun)
    entries = compile_entries(direct, by_verb, by_noun, verbs, plurals, args.max_entries, args.max_length)
    shortlisted = {'CHAT','CHATS','CHATONS','CHEVAUX','NAIF','ECOLE','COEUR','SAIS','SUIS','REGARDES','MANGEAIENT','ELEVE','FUT','PASSAMES','MANGIONS','AURIONS','AIES','TENASSIONS','ASSERVISSAIENT'}
    senses = 0
    with args.output.open('w', encoding='utf-8', newline='\n') as target:
        for word, definition in entries:
            target.write(f'{word}\t{definition}\n')
            senses += 1
            if word in shortlisted:
                print(word, definition)
    print('Forms covered:', len(direct.keys() | verbs.keys() | plurals.keys()), 'of', len(allowed),
          'Direct definitions:', len(direct), 'Conjugated keys:', len(verbs), 'Noun plurals:', len(plurals),
          'Senses:', senses, flush=True)

if __name__ == '__main__':
    main()
