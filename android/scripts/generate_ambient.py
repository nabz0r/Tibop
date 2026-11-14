"""Render Tibop's original instrumental loops and feedback sounds."""
import argparse
from array import array
import math
from pathlib import Path
import random
import subprocess
import sys
import tempfile
import wave

RATE = 32000
TAU = math.tau
RAW = Path(__file__).resolve().parent.parent / "app/src/main/res/raw"

# Bass root and voicing, one bar each.
VERSE = [
    (48, (60, 64, 67, 71)), (47, (59, 62, 67, 74)),
    (45, (57, 60, 64, 67)), (40, (59, 62, 64, 67)),
    (41, (57, 60, 64, 65)), (36, (55, 60, 64, 67)),
    (38, (57, 60, 62, 65)), (43, (55, 59, 62, 65)),
]
CHORUS = [
    (41, (57, 60, 64, 65)), (43, (55, 59, 62, 67)),
    (40, (55, 59, 62, 64)), (45, (57, 60, 64, 67)),
    (38, (57, 60, 62, 65)), (43, (55, 59, 62, 65)),
    (48, (60, 64, 67, 71)), (43, (55, 60, 62, 65)),
]
# Entries are (beat, MIDI note, length in beats). The second refrain
# inverts the opening contour and resolves into the first bar on repeat.
HOOK = [
    ((0, 76, .68), (1, 79, .42), (1.5, 81, .40), (2, 79, .73), (3.5, 74, .42)),
    ((0, 71, .62), (1, 74, .46), (2, 79, 1.25), (3.5, 76, .38)),
    ((0, 72, .53), (.75, 76, .43), (1.5, 79, .42), (2, 84, .89), (3.5, 81, .41)),
    ((0, 79, .81), (1.5, 76, .41), (2.25, 74, 1.20)),
    ((0, 72, .48), (.75, 77, .45), (1.5, 76, .40), (2.25, 72, .91)),
    ((0, 67, .71), (1, 72, .46), (2, 76, .47), (2.75, 79, .86)),
    ((0, 77, .75), (1, 76, .58), (2, 74, .66), (3, 72, .72)),
    ((0, 71, 1.14), (1.75, 74, .45), (2.5, 77, .48), (3.5, 74, .40)),
]
REFRAIN = [
    ((0, 81, .68), (.75, 79, .45), (1.5, 77, .42), (2, 84, 1.22)),
    ((0, 83, .52), (.75, 74, .50), (1.5, 79, .43), (2.5, 86, .85)),
    ((0, 83, .80), (1.5, 79, .48), (2.25, 76, .78), (3.5, 79, .39)),
    ((0, 81, .69), (1, 84, .45), (1.75, 88, .53), (2.5, 84, 1.19)),
    ((0, 86, .67), (1, 84, .42), (1.5, 81, .43), (2.25, 77, .97)),
    ((0, 79, .60), (1, 77, .48), (1.75, 74, .55), (2.5, 71, .89)),
    ((0, 72, .81), (1.25, 76, .68), (2.25, 79, .63), (3, 84, .82)),
    ((0, 86, .47), (.75, 84, .42), (1.5, 81, .42), (2.25, 79, 1.16)),
]


class Mix:
    def __init__(self, seconds):
        self.left = array("f", [0.0]) * round(RATE * seconds)
        self.right = self.left[:]
        self.noise = random.Random(7616)

    def add(self, at, duration, gain, pan, voice, envelope):
        start = round(at * RATE)
        left_gain = gain * math.sqrt(1 - pan)
        right_gain = gain * math.sqrt(pan)
        for offset in range(round(RATE * duration)):
            time = offset / RATE
            sample = voice(time) * envelope(time)
            frame = (start + offset) % len(self.left)
            self.left[frame] += sample * left_gain
            self.right[frame] += sample * right_gain

    def note(self, at, duration, midi, gain, pan, instrument="keys"):
        cycle = TAU * 440 * 2 ** ((midi - 69) / 12)
        if instrument == "lead":
            def voice(t):
                phase = cycle * t + .085 * math.sin(TAU * 5.2 * t)
                return math.sin(phase) + .22 * math.sin(phase * 3) + .075 * math.sin(phase * 5)

            attack, decay, release = .012, 2.5, .08
        elif instrument == "bass":
            def voice(t):
                return math.sin(cycle * t) + .21 * math.sin(cycle * 2 * t)

            attack, decay, release = .016, 3.2, .11
        elif instrument == "pad":
            def voice(t):
                return math.sin(cycle * t) + .32 * math.sin(cycle * 1.003 * t) + .1 * math.sin(cycle * 2 * t)

            attack, decay, release = .35, .18, .49
        else:
            def voice(t):
                phase = cycle * t
                return (math.sin(phase) + .28 * math.exp(-8 * t) * math.sin(phase * 2.003)
                        + .16 * math.exp(-16 * t) * math.sin(phase * 3.98))

            attack, decay, release = .009, 3.7, .14

        def envelope(t):
            return min(1, t / attack, (duration - t) / release) * math.exp(-decay * t)

        self.add(at, duration, gain, pan, voice, envelope)

    def drum(self, at, instrument, gain, pan):
        if instrument == "kick":
            def voice(t):
                return math.sin(TAU * (48 * t + 1.36 * (1 - math.exp(-48 * t))))

            duration, decay = .22, 26
        else:
            def voice(t):
                noise = self.noise.uniform(-1, 1)
                return noise if instrument == "hat" else .74 * noise + .26 * math.sin(190 * TAU * t)

            duration, decay = (.076, 74) if instrument == "hat" else (.16, 37)

        self.add(at, duration, gain, pan, voice,
                 lambda t: min(1, t * 480, (duration - t) * 80) * math.exp(-t * decay))

    def write(self, name, peak):
        gain = peak / max(max(map(abs, self.left)), max(map(abs, self.right)))
        pcm = array("h", (round(sample * gain * 32767)
                          for frame in zip(self.left, self.right) for sample in frame))
        if sys.byteorder != "little":
            pcm.byteswap()
        with tempfile.TemporaryDirectory() as directory:
            wav = Path(directory) / "audio.wav"
            with wave.open(str(wav), "wb") as writer:
                writer.setparams((2, 2, RATE, 0, "NONE", "not compressed"))
                writer.writeframes(pcm.tobytes())
            subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav), "-map_metadata", "-1",
                            "-fflags", "+bitexact", "-flags:a", "+bitexact", "-c:a", "libvorbis",
                            "-q:a", "3", str(RAW / f"{name}.ogg")], check=True)


def arcade():
    beat = 60 / 126
    chords = VERSE + CHORUS
    mix = Mix(len(chords) * 4 * beat)
    for bar, ((root, chord), melody) in enumerate(zip(chords, HOOK + REFRAIN)):
        start = bar * 4 * beat
        for time, length, midi, gain in ((0, .86, root, .124), (1.5, .44, root + 12, .072),
                                         (2, .63, root, .119), (3.25, .57, chords[(bar + 1) % len(chords)][0], .077)):
            mix.note(start + time * beat, length * beat, midi, gain, .5, "bass")
        for i, midi in enumerate(chord):
            mix.note(start + i * .018, beat * 3.5, midi, .024, .37 + i * .08, "pad")
        for i, step in enumerate((.5, 1.25, 2.5, 3.5)):
            for midi in chord:
                mix.note(start + step * beat, .31, midi + 12 * (i % 2), .026, .43 + i * .05)
        for index, (time, midi, length) in enumerate(melody):
            on = start + time * beat
            mix.note(on, length * beat + .10, midi, .134, .51, "lead")
            mix.note(on + .75 * beat, .29, midi, .032, .78 if index % 2 else .24)
            if bar >= len(VERSE):
                mix.note(on + .028, .36, midi + 12, .036, .34)
        for time in (0, 2, 3.5) if bar % 4 == 3 else (0, 2):
            mix.drum(start + time * beat, "kick", .18, .5)
        for time in (1, 3):
            mix.drum(start + time * beat, "snare", .089, .50)
        for tick in range(8):
            mix.drum(start + tick * .5 * beat, "hat", .051 if tick % 2 else .028, .59 if tick % 2 else .43)
    mix.write("ambient", .77)


def evening():
    beat = 60 / 82
    chords = VERSE[:4] + CHORUS[4:7] + VERSE[-1:]
    mix = Mix(len(chords) * 4 * beat)
    for bar, (root, chord) in enumerate(chords):
        start = bar * 4 * beat
        mix.note(start, 2.35, root, .084, .5, "bass")
        for i, midi in enumerate(chord):
            mix.note(start + (i * .21 - .3) * beat, 3.0, midi, .037, .24 + .16 * i, "pad")
        for i, time in enumerate((0, 1.4, 2.7)):
            midi = chord[(i + bar) % 4] + (24 if i == 1 else 12)
            on = start + time * beat
            mix.note(on, 1.75, midi, .095 - .013 * i, .33 + .18 * i)
            mix.note(on + .41, 1.10, midi, .023, .64 - .13 * i)
    mix.write("atelier", .53)


def effects():
    clips = [
        ("tile_sound", .14, .65, ((0, 57, .111, .44, .5), (.012, 81, .102, .24, .52))),
        ("played_sound", .36, .65, ((0, 72, .21, .68, .39), (.073, 76, .22, .52, .59),
                                   (.155, 79, .19, .48, .48))),
        ("celebration_sound", .61, .76, ((0, 76, .28, .55, .40), (.075, 79, .23, .54, .54),
                                       (.15, 84, .30, .59, .61), (.24, 88, .34, .42, .43),
                                       (.26, 67, .27, .34, .49))),
        ("blocked_sound", .235, .46, ((0, 72, .131, .39, .42), (.066, 69, .159, .34, .57))),
    ]
    for name, duration, peak, notes in clips:
        mix = Mix(duration)
        for at, midi, length, gain, pan in notes:
            mix.note(at, length, midi, gain, pan)
        mix.write(name, peak)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--only", choices=("arcade", "evening", "effects"), help="render just the selected audio")
    chosen = parser.parse_args().only
    for command, render in (("arcade", arcade), ("evening", evening), ("effects", effects)):
        if chosen in (None, command):
            render()
