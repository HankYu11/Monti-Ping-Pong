"""Coaching-level answers, judged against the simulator's truth.
   thick  - the ball left too steeply from the face (pushed): fix this first
   good   - thin contact and enough spin
   slip   - thin contact, low spin, the ball slid on the rubber (not pressed into it)
   slow   - thin contact, low spin, the rubber gripped: the brush itself was too slow
Measured: spin (5% noise) and ball speed (15% noise) only."""
import numpy as np
from contact import serve, R
TARGET = 30.0   # rps: below this a backspin serve is easy to attack
def truth(s):
    if s["ratio"] < 0.9: return "thick"
    if s["rps"] >= TARGET: return "good"
    return "slip" if s["sliding"] else "slow"
def rule(rps, speed, slip_thr):
    ratio = 2 * np.pi * rps * R / speed
    if ratio < 0.9: return "thick"
    if rps >= TARGET: return "good"
    return "slip" if ratio >= slip_thr else "slow"
def run(slip_thr, n, seed):
    rng = np.random.default_rng(seed); res = []
    for _ in range(n):
        V, a, fall, kp = rng.uniform(1.5, 10), rng.uniform(0, 60), rng.uniform(0.5, 2.0), rng.uniform(0.45, 0.80)
        s = serve(V, a, fall, kp)
        res.append((truth(s), rule(s["rps"] * (1 + rng.normal(0, .05)), s["speed"] * (1 + rng.normal(0, .15)), slip_thr)))
    return res
if __name__ == "__main__":
    thr = max((np.mean([t == h for t, h in run(st, 400, 5)]), st) for st in (1.15, 1.2, 1.25, 1.3, 1.35, 1.4))[1]
    res = run(thr, 2000, 77)
    print(f"slip if spin/speed >= {thr}; fresh test: right {100*np.mean([t == h for t, h in res]):.0f}%")
    labs = ("thick", "good", "slip", "slow")
    print("truth \\ said  " + "  ".join(f"{l:>6}" for l in labs))
    for t in labs: print(f"{t:13} " + "  ".join(f"{sum(a == t and b == l for a, b in res):6d}" for l in labs))
