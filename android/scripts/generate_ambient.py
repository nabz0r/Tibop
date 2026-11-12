"""Create the original Lettrine sound bed from a short synthesized score."""
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
SCORE = [
    (0.0, (48, 55, 59, 64), (72, 76, 79, 83)),
    (10.0, (45, 52, 55, 60), (69, 72, 76, 79)),
    (20.0, (41, 48, 52, 57), (65, 69, 72, 76)),
    (30.0, (43, 50, 57, 62), (67, 74, 81, 86)),
]

left = [0.0] * (RATE * SECONDS)
right = left.copy()


def note(start, duration, midi, volume, pan, soft):
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


for time, chord, melody in SCORE:
    for i, midi in enumerate(chord):
        note(time - 1 + i * 0.28, 11.5, midi, 0.058 - 0.006 * i, 0.36 + 0.09 * i, True)
    for i, midi in enumerate(melody):
        note(time + 0.7 + i * (1.46 + 0.13 * i), 3.4, midi, 0.035 - 0.002 * i, 0.61 - 0.08 * i, False)

target = ROOT / "app/src/main/res/raw/ambient.ogg"
with tempfile.TemporaryDirectory() as directory:
    wav = Path(directory) / "ambient.wav"
    with wave.open(str(wav), "wb") as writer:
        writer.setparams((2, 2, RATE, 0, "NONE", "not compressed"))
        buffer = bytearray()
        for l, r in zip(left, right):
            buffer.extend(struct.pack("<hh", round(l * 32767), round(r * 32767)))
        writer.writeframes(buffer)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav), "-c:a", "libvorbis", "-q:a", "4", str(target)], check=True)
