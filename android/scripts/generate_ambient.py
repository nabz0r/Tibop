"""Render Lettrine's original loops and tile sounds with Python and FFmpeg."""
import argparse
import math
from pathlib import Path
import struct
import subprocess
import tempfile
import wave

RATE = 24000
SECONDS = 40
TAU = math.tau
ROOT = Path(__file__).resolve().parent.parent
JARDIN = [
    (0.0, (48, 55, 59, 64), (72, 76, 79, 83)),
    (10.0, (45, 52, 55, 60), (69, 72, 76, 79)),
    (20.0, (41, 48, 52, 57), (65, 69, 72, 76)),
    (30.0, (43, 50, 57, 62), (67, 74, 81, 86)),
]
ATELIER = [
    (0.0, (45, 52, 57, 60), (76, 72, 71, 69)),
    (10.0, (41, 48, 52, 57), (72, 69, 65, 67)),
    (20.0, (48, 55, 59, 64), (79, 76, 74, 72)),
    (30.0, (43, 50, 57, 62), (74, 71, 67, 64)),
]


def note(left, right, start, duration, midi, volume, pan, soft):
    frequency = 440 * 2 ** ((midi - 69) / 12)
    start_frame = round(RATE * start)
    for offset in range(round(RATE * duration)):
        moment = offset / RATE
        progress = moment / duration
        if soft:
            envelope = math.sin(math.pi * progress) ** 1.9
            voice = math.sin(TAU * frequency * moment + 0.20 * math.sin(1.6 * moment))
            voice += 0.17 * math.sin(TAU * frequency * 2 * moment)
        else:
            envelope = (1 - math.exp(-moment * 45)) * math.exp(-moment * 0.94)
            envelope *= min(1.0, (duration - moment) * 3)
            voice = math.sin(TAU * frequency * moment)
            voice += 0.19 * math.exp(-moment * 2.8) * math.sin(TAU * frequency * 2.99 * moment)
        value = envelope * voice * volume
        frame = (start_frame + offset) % len(left)
        left[frame] += value * (1 - pan)
        right[frame] += value * pan


def write(name, left, right):
    target = ROOT / "app/src/main/res/raw" / (name + ".ogg")
    with tempfile.TemporaryDirectory() as directory:
        wav = Path(directory) / "audio.wav"
        with wave.open(str(wav), "wb") as writer:
            writer.setparams((2, 2, RATE, 0, "NONE", "not compressed"))
            buffer = bytearray()
            for l, r in zip(left, right):
                buffer.extend(struct.pack("<hh", round(l * 32767), round(r * 32767)))
            writer.writeframes(buffer)
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav),
                        "-c:a", "libvorbis", "-q:a", "4", str(target)], check=True)


def render_loop(name, score):
    left = [0.0] * (RATE * SECONDS)
    right = left.copy()
    for time, chord, melody in score:
        for i, midi in enumerate(chord):
            note(left, right, time - 1 + i * 0.28, 11.5, midi, 0.058 - 0.006 * i, 0.36 + 0.09 * i, True)
        for i, midi in enumerate(melody):
            note(left, right, time + 0.7 + i * (1.46 + 0.13 * i), 3.4, midi, 0.035 - 0.002 * i,
                 0.61 - 0.08 * i, False)
    write(name, left, right)


def render_effect(name, duration, score):
    samples = []
    for frame in range(round(duration * RATE)):
        time = frame / RATE
        value = 0.0
        for start, frequency, decay in score:
            moment = time - start
            if moment < 0:
                continue
            envelope = min(1.0, moment * 340, (duration - time) * 85) * math.exp(-moment * decay)
            voice = math.sin(TAU * frequency * moment) + 0.28 * math.sin(TAU * frequency * 1.49 * moment)
            value += 0.18 * envelope * voice
        samples.append(value)
    write(name, samples, samples)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--include-jardin", action="store_true", help="also regenerate the existing Jardin calme loop")
    options = parser.parse_args()
    if options.include_jardin:
        render_loop("ambient", JARDIN)
    render_loop("atelier", ATELIER)
    render_effect("tile_sound", 0.13, [(0, 310, 37), (0, 670, 65)])
    render_effect("played_sound", 0.42, [(0, 523.25, 11), (0.07, 659.25, 11), (0.13, 783.99, 11)])
