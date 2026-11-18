"""Regenerates the placeholder sprites and the save sound. Stdlib only: python3 tools/make_assets.py"""
import math
import struct
import wave
import zlib
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/brainrot"

PALETTE = {
    ".": (0, 0, 0, 0),
    "K": (20, 20, 24, 255),
    "g": (150, 150, 160, 255),
    "W": (255, 255, 255, 255),
    "P": (255, 130, 170, 255),
}

RUNNER = [
    "..K.......K..",
    ".KgK.....KgK.",
    ".KggKKKKKggK.",
    "KgggggggggggK",
    "KggWKgggWKggK",
    "KgggggPgggggK",
    "KggggKgKggggK",
    ".KgggggggggK.",
    "..KKKKKKKKK..",
]

FONT = {
    "D": ["WW.", "W.W", "W.W", "W.W", "WW."],
    "V": ["W.W", "W.W", "W.W", "W.W", ".W."],
}


def text_rows(text):
    rows = ["." + ".".join(FONT[c][y] for c in text) + "." for y in range(5)]
    blank = "." * len(rows[0])
    return [blank, *rows, blank]


def write_png(path, rows, scale):
    width, height = len(rows[0]) * scale, len(rows) * scale
    raw = b"".join((b"\0" + b"".join(bytes(PALETTE[c]) * scale for c in row)) * scale for row in rows)

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def write_boom(path, rate=22050, seconds=0.6):
    frames = bytearray()
    phase = 0.0
    for i in range(int(rate * seconds)):
        t = i / rate
        phase += 2 * math.pi * (40 + 110 * math.exp(-t * 9)) / rate
        sample = math.tanh(3 * math.sin(phase)) * math.exp(-t * 6)
        frames += struct.pack("<h", int(sample * 30000))
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(bytes(frames))


if __name__ == "__main__":
    write_png(OUT / "runner.png", RUNNER, 4)
    write_png(OUT / "bouncer.png", text_rows("DVD"), 8)
    write_boom(OUT / "boom.wav")
    print("wrote", sorted(p.name for p in OUT.iterdir()))
