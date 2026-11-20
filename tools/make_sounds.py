"""v0.2.0 sounds: copies the CC0 recordings this app uses from the factory apps on this machine (already cleaned there),
resamples to 44.1 kHz mono 16-bit, trims, fades, peaks at -1 dBFS, and writes app/src/main/assets/audio/.

    python -X utf8 tools/make_sounds.py

Sources (also in the factory's apps/shoulder-check/build/sound-sources.md):
  indicator   <- keycap-studio sw_linear_1  (freesound 499773 by handygaber, CC0)  turn-signal relay tick
  door_latch  <- keycap-studio sw_clicky_1  (freesound 400699 by jameslovescode, CC0)  door latch 「咔」
  tick        <- luban-lock click.wav       (Kenney impactWood_light_000 + freesound 254343 snap, CC0)  wooden 「嗒」
  ding        <- luban-lock hint.wav        (Kenney interface-sounds glass_002, CC0)  clear 「叮」
  bike_bell   <- the same glass_002 recording, two strikes 120 ms apart, x1.25 pitch  bicycle 「叮铃」
  record      <- four-drop win.wav          (Kenney interface-sounds confirmation_002, CC0)  new best
  timeout     <- four-drop lose.wav         (Kenney interface-sounds minimize_008, CC0)  too late
  near_miss   <- kept from v0.1.0 (tools/make_audio.py, synthesised UI warning two-tone)
"""
import os
import wave

import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
DL = os.path.dirname(ROOT)
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "audio")
SR = 44100


def read(path):
    with wave.open(path, "rb") as w:
        n, ch, sr, sw = w.getnframes(), w.getnchannels(), w.getframerate(), w.getsampwidth()
        raw = w.readframes(n)
    assert sw == 2, path
    x = np.frombuffer(raw, dtype="<i2").astype(np.float64) / 32768.0
    if ch > 1:
        x = x.reshape(-1, ch).mean(axis=1)
    return resample(x, sr, SR)


def resample(x, sr, to):
    if sr == to:
        return x
    t = np.arange(int(len(x) * to / sr)) * sr / to
    return np.interp(t, np.arange(len(x)), x)


def pitch(x, k):
    """Plays the recording k times faster (resampling, like SoundPool's rate)."""
    t = np.arange(int(len(x) / k)) * k
    return np.interp(t, np.arange(len(x)), x)


def finish(x, peak_db=-1.0, fade_ms=12):
    f = int(SR * fade_ms / 1000)
    if len(x) > 2 * f:
        x = x.copy()
        x[-f:] *= np.linspace(1.0, 0.0, f)
        x[:32] *= np.linspace(0.0, 1.0, 32)
    m = np.max(np.abs(x)) or 1.0
    return x / m * 10 ** (peak_db / 20)


def write(name, x):
    y = np.clip(np.round(x * 32767), -32768, 32767).astype("<i2")
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(y.tobytes())
    rms = 20 * np.log10(np.sqrt(np.mean(x ** 2)) + 1e-12)
    print(f"{name}.wav  {len(x) / SR:.3f} s  peak {20 * np.log10(np.max(np.abs(x)) + 1e-12):.1f} dBFS  rms {rms:.1f} dBFS")


def main():
    keycap = os.path.join(DL, "keycap-studio", "app", "src", "main", "res", "raw")
    luban = os.path.join(DL, "luban-lock", "app", "src", "main", "assets", "sounds")
    four = os.path.join(DL, "four-drop", "app", "src", "main", "assets", "sounds")
    keep = {"near_miss.wav"}
    for f in os.listdir(OUT):
        if f.endswith(".wav") and f not in keep:
            os.remove(os.path.join(OUT, f))
    write("indicator", finish(read(os.path.join(keycap, "sw_linear_1.wav"))))
    write("door_latch", finish(read(os.path.join(keycap, "sw_clicky_1.wav"))))
    write("tick", finish(read(os.path.join(luban, "click.wav"))))
    glass = read(os.path.join(luban, "hint.wav"))
    write("ding", finish(glass))
    g = pitch(glass, 1.25)
    gap = int(SR * 0.12)
    bell = np.zeros(gap + len(g) + int(SR * 0.02))
    bell[: len(g)] += g
    bell[gap: gap + len(g)] += g * 0.85
    write("bike_bell", finish(bell))
    write("record", finish(read(os.path.join(four, "win.wav"))))
    write("timeout", finish(read(os.path.join(four, "lose.wav"))))


if __name__ == "__main__":
    main()
