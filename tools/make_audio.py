"""shoulder-check audio, synthesised offline (design `audio`: 全部自制合成，离线渲染 wav).

    python -X utf8 tools/make_audio.py            (needs numpy; matplotlib optional for the spectrogram)

Writes 16-bit 44.1 kHz mono WAVs to app/src/main/assets/audio/ and, in the factory app folder,
qa/audio-check.txt (peak / RMS / length per file + loop-seam check) and qa/audio-spectrogram.png.

Levels (taste B20): every peak <= -1 dBFS; the bed is quiet (RMS ~ -30 dBFS); one-shot cues RMS -18..-22 dBFS
(RMS over the file, files are trimmed to the sound). If a cue would peak above -1 dBFS at -20 RMS the gain is
capped by the peak and the lower RMS is reported.

| file              | what                                                                              |
| indicator.wav     | 转向灯「嗒」: relay click (noisy 2-5 kHz snap + 650 Hz body); app loops it at 1.5 Hz |
| turn_tick.wav     | 回头过阈值「嗒」: short tonal wood-block tick, 1.9 kHz, clearly unlike the relay     |
| idle_bed.wav      | engine idle (25 Hz firing + harmonics, slow wobble) + road rumble; 4.0 s seamless  |
| horn.wav          | car horn: dual 410 / 505 Hz reed tones, 0.55 s                                     |
| screech.wav       | tyre screech: wobbling 1.3-1.8 kHz squeal + hiss, 1.1 s                            |
| bike_bell.wav     | bicycle bell, two strikes (inharmonic partials with beating)                       |
| ebike_brake.wav   | e-bike brake squeal: 3.2 kHz with vibrato, rising then cut, 0.8 s                  |
| door_open.wav     | 开门「咔」: handle + latch double click, small panel thunk                          |
| door_close.wav    | 关门「嘭」: 75 Hz thud with pitch drop + latch click                               |
| success.wav       | 安全通过「叮」: soft E6 bell                                                        |
| near_miss.wav     | 险情低音: descending low buzz 165 -> 104 Hz, slightly detuned pair                  |
The bed is built in the frequency domain over exactly 4.0 s (every partial / modulation is a whole number of
cycles, noise is synthesised as a circular spectrum), so the loop is seamless by construction.
"""
import os
import wave

import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "audio")
QA = r"C:\Users\Admin\Downloads\spatial-app-factory\apps\shoulder-check\qa"
SR = 44100
TAU = 2 * np.pi
rng = np.random.default_rng(20261030)


def t_axis(sec):
    return np.arange(int(round(sec * SR))) / SR


def env_ad(n, attack, decay):
    i = np.arange(n) / SR
    a = np.clip(i / max(attack, 1e-4), 0, 1)
    d = np.exp(-np.maximum(i - attack, 0) / decay)
    return a * d


def band_noise(n, lo, hi, pad=4096):
    """White noise band-limited in the FFT domain (padded so the one-shot does not wrap)."""
    m = n + pad
    spec = np.fft.rfft(rng.standard_normal(m))
    f = np.fft.rfftfreq(m, 1 / SR)
    spec[(f < lo) | (f > hi)] = 0
    x = np.fft.irfft(spec, m)[:n]
    return x / (np.std(x) + 1e-12)


def fade(x, fin=0.002, fout=0.01):
    a, b = int(fin * SR), int(fout * SR)
    if a:
        x[:a] *= np.linspace(0, 1, a)
    if b:
        x[-b:] *= np.linspace(1, 0, b)
    return x


def db(v):
    return 20 * np.log10(max(v, 1e-12))


def level(x, rms_db=-20.0, peak_db=-1.0):
    rms = np.sqrt(np.mean(x ** 2))
    g = 10 ** (rms_db / 20) / rms
    pk = np.max(np.abs(x)) * g
    if pk > 10 ** (peak_db / 20):
        g *= 10 ** (peak_db / 20) / pk
    return x * g


# ---------------------------------------------------------------- sounds
def indicator():
    n = int(0.045 * SR)
    snap = band_noise(n, 2000, 5500) * env_ad(n, 0.0004, 0.0035)
    body = np.sin(TAU * 650 * t_axis(0.045)) * env_ad(n, 0.0008, 0.009) * 0.9
    low = np.sin(TAU * 180 * t_axis(0.045)) * env_ad(n, 0.001, 0.012) * 0.35
    return fade(snap + body + low, 0.0002, 0.006)


def turn_tick():
    t = t_axis(0.05)
    n = len(t)
    x = (np.sin(TAU * 1900 * t) + 0.35 * np.sin(TAU * 3800 * t + 0.4) + 0.2 * np.sin(TAU * 5300 * t)) * env_ad(n, 0.0006, 0.007)
    return fade(x, 0.0002, 0.008)


def idle_bed(sec=4.0):
    n = int(sec * SR)
    t = np.arange(n) / SR
    df = 1 / sec  # every frequency below is a multiple of 0.25 Hz -> whole cycles in 4 s
    # engine: 25 Hz firing (750 rpm, 4-cyl) and harmonics, slow 0.5 Hz wobble; half-order 12.5 Hz roughness
    eng = np.zeros(n)
    for h, amp in [(0.5, 0.25), (1, 1.0), (2, 0.6), (3, 0.35), (4, 0.22), (6, 0.12), (8, 0.06)]:
        f = round(25.0 * h / df) * df
        eng += amp * np.sin(TAU * f * t + rng.uniform(0, TAU))
    eng *= 1 + 0.12 * np.sin(TAU * 0.5 * t) + 0.05 * np.sin(TAU * 1.75 * t + 1.0)
    # road / tyre rumble: circular pink-ish noise, 30-700 Hz, a little hiss 1-4 kHz
    spec = np.zeros(n // 2 + 1, complex)
    f = np.fft.rfftfreq(n, 1 / SR)
    mag = np.where((f > 30) & (f < 700), 1 / np.sqrt(np.maximum(f, 1)), 0) + np.where((f > 1000) & (f < 4000), 0.012, 0)
    spec[:] = mag * np.exp(1j * rng.uniform(0, TAU, len(f)))
    road = np.fft.irfft(spec, n)
    road /= np.std(road)
    x = 0.55 * eng / np.std(eng) + 0.8 * road
    return x  # no fades: periodic


def horn():
    t = t_axis(0.55)
    x = np.zeros(len(t))
    for f0, amp in [(410, 1.0), (505, 0.85)]:
        for h in range(1, 9):
            x += amp * (0.8 ** h) / h ** 0.3 * np.sin(TAU * f0 * h * t + rng.uniform(0, TAU))
    x = np.tanh(1.6 * x / np.max(np.abs(x)) * 1.8)
    env = np.clip(t / 0.012, 0, 1) * np.clip((t[-1] - t) / 0.04, 0, 1)
    return x * env


def screech():
    sec = 1.1
    t = t_axis(sec)
    n = len(t)
    drift = np.cumsum(rng.standard_normal(n)) / np.sqrt(SR) * 40
    drift -= np.linspace(drift[0], drift[-1], n)
    f = 1550 + 180 * np.sin(TAU * 6.5 * t) + 90 * np.sin(TAU * 13 * t + 1) + drift - 200 * t / sec
    ph = TAU * np.cumsum(f) / SR
    tone = np.sin(ph) + 0.4 * np.sin(2 * ph + 0.3) + 0.15 * np.sin(3 * ph)
    hiss = band_noise(n, 2500, 7000) * 0.35
    am = 1 + 0.25 * np.sin(TAU * 23 * t)
    env = np.clip(t / 0.08, 0, 1) ** 1.5 * np.clip((sec - t) / 0.35, 0, 1)
    return fade((tone * am + hiss) * env, 0.002, 0.02)


def bike_bell():
    sec = 1.1
    t = t_axis(sec)
    x = np.zeros(len(t))
    partials = [(2380, 1.0, 0.45), (2386, 0.6, 0.45), (5510, 0.45, 0.22), (8120, 0.2, 0.12), (3970, 0.25, 0.3)]
    for strike in (0.0, 0.17):
        k = int(strike * SR)
        tt = t[: len(t) - k]
        s = np.zeros(len(tt))
        for f, a, d in partials:
            s += a * np.sin(TAU * f * tt + rng.uniform(0, TAU)) * np.exp(-tt / d)
        s *= np.clip(tt / 0.0015, 0, 1)
        x[k:] += s * (1.0 if strike == 0 else 0.85)
    return fade(x, 0.0005, 0.05)


def ebike_brake():
    sec = 0.8
    t = t_axis(sec)
    f = 3200 + 35 * np.sin(TAU * 9 * t) + 120 * t
    ph = TAU * np.cumsum(f) / SR
    x = np.sin(ph) + 0.25 * np.sin(2 * ph) + 0.1 * band_noise(len(t), 2500, 6000)
    env = np.clip(t / 0.15, 0, 1) ** 2 * np.clip((sec - t) / 0.06, 0, 1)
    return fade(x * env, 0.002, 0.01)


def click(sec, lo, hi, dec, body_f=None, body_amp=0.0, body_dec=0.02):
    n = int(sec * SR)
    x = band_noise(n, lo, hi) * env_ad(n, 0.0003, dec)
    if body_f:
        x += body_amp * np.sin(TAU * body_f * np.arange(n) / SR) * env_ad(n, 0.001, body_dec)
    return x


def door_open():
    sec = 0.35
    x = np.zeros(int(sec * SR))
    for at, amp, (lo, hi) in [(0.0, 0.6, (900, 3500)), (0.055, 1.0, (1200, 4500))]:
        c = click(0.06, lo, hi, 0.008, body_f=420, body_amp=0.9, body_dec=0.02) * amp
        c = fade(c, 0.0002, 0.03)
        k = int(at * SR)
        x[k:k + len(c)] += c[: len(x) - k]
    # panel thunk + a little seal release hiss
    n = len(x)
    k = int(0.06 * SR)
    th = np.sin(TAU * 140 * t_axis(0.12)) * env_ad(int(0.12 * SR), 0.002, 0.045) * 1.1
    th = fade(th, 0.0005, 0.06)
    x[k:k + len(th)] += th
    hs = band_noise(n, 600, 3000) * env_ad(n, 0.08, 0.06) * 0.06
    x += np.roll(hs, int(0.04 * SR))
    return fade(x, 0.0003, 0.03)


def door_close():
    sec = 0.55
    t = t_axis(sec)
    n = len(t)
    f = 75 + 45 * np.exp(-t / 0.02)
    ph = TAU * np.cumsum(f) / SR
    thud = (np.sin(ph) + 0.3 * np.sin(2 * ph)) * env_ad(n, 0.002, 0.09)
    knock = band_noise(n, 80, 900) * env_ad(n, 0.001, 0.03) * 0.7
    latch = np.zeros(n)
    c = click(0.05, 1500, 5000, 0.004, body_f=900, body_amp=0.5, body_dec=0.008) * 0.45
    k = int(0.012 * SR)
    latch[k:k + len(c)] = c
    rattle = band_noise(n, 300, 2000) * env_ad(n, 0.004, 0.05) * 0.12
    return fade(thud + knock + latch + rattle, 0.0005, 0.05)


def success():
    sec = 1.0
    t = t_axis(sec)
    f0 = 1318.5
    x = (np.sin(TAU * f0 * t) * np.exp(-t / 0.35) + 0.28 * np.sin(TAU * 2 * f0 * t + 0.5) * np.exp(-t / 0.15)
         + 0.1 * np.sin(TAU * 3.01 * f0 * t) * np.exp(-t / 0.08))
    x *= np.clip(t / 0.003, 0, 1)
    return fade(x, 0.0005, 0.08)


def near_miss():
    sec = 0.7
    t = t_axis(sec)
    f = 104 + 61 * np.exp(-t / 0.18)
    x = np.zeros(len(t))
    for det, amp in [(1.0, 1.0), (1.018, 0.7)]:
        ph = TAU * np.cumsum(f * det) / SR
        for h in range(1, 7):
            x += amp / h * np.sin(h * ph)
    x = np.tanh(1.3 * x / np.max(np.abs(x)))
    env = np.clip(t / 0.02, 0, 1) * np.exp(-np.maximum(t - 0.25, 0) / 0.15)
    return fade(x * env, 0.001, 0.04)


SOUNDS = [  # name, generator, target RMS dBFS, loop
    ("indicator", indicator, -20, False),
    ("turn_tick", turn_tick, -21, False),
    ("idle_bed", idle_bed, -30, True),
    ("horn", horn, -19, False),
    ("screech", screech, -20, False),
    ("bike_bell", bike_bell, -20, False),
    ("ebike_brake", ebike_brake, -21, False),
    ("door_open", door_open, -21, False),
    ("door_close", door_close, -19, False),
    ("success", success, -20, False),
    ("near_miss", near_miss, -19, False),
]


def write_wav(path, x):
    pcm = np.clip(np.round(x * 32767), -32768, 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    return pcm.astype(np.float64) / 32768


def main():
    os.makedirs(OUT, exist_ok=True)
    os.makedirs(QA, exist_ok=True)
    lines = ["shoulder-check audio check (tools/make_audio.py) - 44.1 kHz 16-bit mono",
             "limits: peak <= -1.0 dBFS; bed RMS ~ -30 dBFS; cues RMS -18..-22 dBFS; total <= 4 MB", "",
             f"{'file':18s} {'sec':>6s} {'peak dBFS':>10s} {'RMS dBFS':>9s} {'KB':>7s}  check"]
    rendered = []
    total = 0
    ok_all = True
    for name, gen, rms_db, loop in SOUNDS:
        x = level(gen(), rms_db)
        y = write_wav(os.path.join(OUT, name + ".wav"), x)
        size = os.path.getsize(os.path.join(OUT, name + ".wav"))
        total += size
        pk, rms = db(np.max(np.abs(y))), db(np.sqrt(np.mean(y ** 2)))
        ok = pk <= -1.0 + 1e-6 and (abs(rms - (-30)) <= 3 if loop else -22.5 <= rms <= -17.5)
        note = "ok" if ok else "CHECK"
        if not ok and not loop and pk >= -1.05:
            note = "ok (peak-limited, RMS below target)"
            ok = True
        ok_all &= ok
        lines.append(f"{name + '.wav':18s} {len(y) / SR:6.3f} {pk:10.2f} {rms:9.2f} {size / 1024:7.1f}  {note}")
        rendered.append((name, y, loop))
    lines.append(f"{'total':18s} {'':6s} {'':10s} {'':9s} {total / 1024:7.1f}  {'ok' if total <= 4e6 else 'TOO BIG'}")
    lines.append("")
    for name, y, loop in rendered:
        if not loop:
            continue
        d = np.abs(np.diff(y))
        seam = abs(y[0] - y[-1])
        p99 = np.percentile(d, 99)
        w = int(0.05 * SR)
        r_head, r_tail = db(np.sqrt(np.mean(y[:w] ** 2))), db(np.sqrt(np.mean(y[-w:] ** 2)))
        # spectral seam check: energy of the 2nd difference at the seam vs typical
        dd = np.abs(np.diff(np.concatenate([y[-3:], y[:3]]), 2)).max()
        dd_typ = np.percentile(np.abs(np.diff(y, 2)), 99)
        lines.append(f"loop seam {name}.wav: |x[0]-x[-1]| = {seam:.5f} (99th pct of sample steps {p99:.5f}); "
                     f"2nd diff at seam {dd:.5f} (99th pct {dd_typ:.5f}); RMS first/last 50 ms {r_head:.1f}/{r_tail:.1f} dBFS"
                     f" -> {'seamless' if seam <= p99 and dd <= dd_typ else 'CHECK'}")
    lines.append("")
    lines.append("ALL OK" if ok_all else "SOME CHECKS FAILED")
    with open(os.path.join(QA, "audio-check.txt"), "w", encoding="utf8") as fh:
        fh.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    spectrogram(rendered)


def spectrogram(rendered):
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print("matplotlib missing - no spectrogram")
        return
    fig, axes = plt.subplots(len(rendered), 1, figsize=(11, 1.55 * len(rendered)), constrained_layout=True)
    for ax, (name, y, loop) in zip(axes, rendered):
        yy = np.concatenate([y, y]) if loop else y
        nfft = 256 if len(y) < 0.2 * SR else 1024
        ax.specgram(yy, NFFT=nfft, Fs=SR, noverlap=nfft * 7 // 8, cmap="magma", vmin=-130, vmax=-30)
        ax.set_ylim(0, 9000 if name not in ("idle_bed", "near_miss", "door_close") else 2000)
        ax.set_ylabel("Hz", fontsize=7)
        ax.tick_params(labelsize=7)
        ax.set_title(f"{name}.wav" + ("  (looped twice: seam at the middle)" if loop else ""), fontsize=8, loc="left")
        if loop:
            ax.axvline(len(y) / SR, color="cyan", lw=0.8, ls="--")
    axes[-1].set_xlabel("s")
    fig.savefig(os.path.join(QA, "audio-spectrogram.png"), dpi=110)
    print("spectrogram ->", os.path.join(QA, "audio-spectrogram.png"))


if __name__ == "__main__":
    main()
