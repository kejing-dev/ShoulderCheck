"""Loudness evidence (factory gate `loudness`, B20 / build.md 「只有短促反馈音的应用」): renders, at the app's DEFAULT
gain (中 = 0.9, Audio.kt levels), the event sounds of two typical rounds back to back — turn-signal relay ticks (level
0.35), the 「嗒」 of the mirror and shoulder checks, the clear 「叮」 rising with the combo, the door latch, the car horn
+ tyre skid / bicycle bell + skid of a close call (v0.2.2 CC0 recordings), too late, new best — and writes it. Rendered, not recorded (the emulator has no
audio loopback); RMS is measured on the voiced 20 ms windows only, like the gate.

    python -X utf8 tools/render_loudness.py <out.wav>
"""
import math
import os
import sys
import wave

import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
AUD = os.path.join(ROOT, "app", "src", "main", "assets", "audio")
SR = 44100
GAIN = 0.9


def load(name):
    with wave.open(os.path.join(AUD, name + ".wav"), "rb") as w:
        x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64) / 32768.0
    return x


def rate(x, k):
    t = np.arange(int(len(x) / k)) * k
    return np.interp(t, np.arange(len(x)), x)


def main(out):
    mix = np.zeros(SR * 30)
    def put(t, x, level=1.0):
        i = int(t * SR)
        n = min(len(x), len(mix) - i)
        if n > 0:
            mix[i:i + n] += x[:n] * level * GAIN
    ind, tick, ding = load("indicator"), load("tick"), load("ding")
    t0 = 0.5
    for rnd in range(6):
        for k in range(int(4.0 / 0.667)):
            put(t0 + k * 0.667, ind, 0.35)
        put(t0 + 1.6, tick)
        put(t0 + 2.6, tick)
        if rnd == 1:
            put(t0 + 3.4, load("skid"), 0.8)
            put(t0 + 3.4, load("horn"))
        elif rnd == 3:
            put(t0 + 3.4, load("skid_1"), 0.8)
            put(t0 + 3.4, load("bike_bell"))
        elif rnd == 4:
            put(t0 + 3.4, load("timeout"))
        else:
            put(t0 + 3.4, rate(ding, 2 ** (2 * min(rnd, 4) / 12)))
            if rnd == 2:
                put(t0 + 3.4, load("door_latch"))
        t0 += 4.6
    put(28.0, load("record"))
    peak = np.max(np.abs(mix))
    if peak > 10 ** (-1 / 20):
        mix *= 10 ** (-1 / 20) / peak  # the app never clips: SoundPool sums under the master gain
    y = np.clip(np.round(mix * 32767), -32768, 32767).astype("<i2")
    with wave.open(out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(y.tobytes())
    d = y.astype(np.float64)
    win = 882
    e = [np.mean(d[k:k + win] ** 2) for k in range(0, len(d) - win + 1, win)]
    pk = np.max(np.abs(d))
    thr = (pk * 0.0316) ** 2
    v = [x for x in e if x >= thr]
    rms = 20 * math.log10(math.sqrt(sum(v) / len(v)) / 32768)
    print(f"{out}: voiced RMS {rms:.1f} dBFS, peak {20 * math.log10(pk / 32768):.1f} dBFS, voiced windows {len(v)}/{len(e)}")


if __name__ == "__main__":
    main(sys.argv[1])
