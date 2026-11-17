"""Offline loudness evidence (B20 / ui_gates loudness): renders 30 s of play from the app's own WAVs at the default
volume (High, 100 %) with the app's gains and timing (flight 220 + 40 ms, flip rings 60 + 120 ms apart with +2 % pitch
per ring, AI >= 400 ms, one pass cue) and the +-5 % place variation, then prints whole-file RMS, voiced RMS (ui_gates
method: 20 ms windows above peak - 30 dB) and peak. kj-alienware has no audio loopback, so this is a render, not a
recording of the emulator output.

  python tools/loudness.py OUT.wav
"""
import math, os, random, struct, sys, wave

SR = 44100
SND = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets", "sounds")
GAIN = {"place": 1.0, "retract": 0.6, "lose": 0.55, "illegal": 0.55, "win": 0.55}


def load(name):
    with wave.open(os.path.join(SND, name + ".wav")) as w:
        assert w.getframerate() == SR and w.getsampwidth() == 2
        data = struct.unpack("<%dh" % (w.getnframes() * w.getnchannels()), w.readframes(w.getnframes()))
        if w.getnchannels() == 2:
            data = data[::2]
        return [v / 32768 for v in data]


def rate(x, r):
    n = int(len(x) / r)
    return [x[min(len(x) - 1, int(i * r))] for i in range(n)]


def main(out):
    rnd = random.Random(5)
    places = [load("place"), load("place_2"), load("place_3")]
    flips = [load("retract"), load("retract_2")]
    buf = [0.0] * (30 * SR)
    def put(kind, x, t):
        s = int(t * SR)
        for i, v in enumerate(x):
            if s + i < len(buf):
                buf[s + i] += v * GAIN[kind if kind in GAIN else "retract"]

    t = 0.5
    move = 0
    while t < 29:
        land = t + 0.26
        put("place", rate(rnd.choice(places), 0.95 + rnd.random() * 0.1), land)
        rings = rnd.choice([1, 1, 2, 2, 3, 4])
        for d in range(1, rings + 1):
            put("flip%d" % d, rate(rnd.choice(flips), 1 + 0.02 * (d - 1)), land + 0.06 + 0.12 * (d - 1))
        t = land + 0.06 + 0.12 * (rings - 1) + 0.26 + (0.45 if move % 2 else 1.4)
        move += 1
        if move == 11:
            put("lose", load("lose"), t); t += 1.3
    peak = max(abs(v) for v in buf)
    if peak > 0.891:  # keep the render's peak <= -1 dBFS (overlaps can stack)
        k = 0.891 / peak
        buf = [v * k for v in buf]
        peak = 0.891
    with wave.open(out, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(struct.pack("<%dh" % len(buf), *[int(max(-1, min(1, v)) * 32767) for v in buf]))
    rms = math.sqrt(sum(v * v for v in buf) / len(buf))
    win = 882
    e = [sum(v * v for v in buf[k:k + win]) / win for k in range(0, len(buf) - win + 1, win)]
    thr = (peak * 0.0316) ** 2
    voiced = [x for x in e if x >= thr]
    vr = math.sqrt(sum(voiced) / len(voiced))
    print("rms %.1f dBFS, voiced rms %.1f dBFS (%d windows), peak %.1f dBFS" % (20 * math.log10(rms), 20 * math.log10(vr), len(voiced), 20 * math.log10(peak)))


main(sys.argv[1])
