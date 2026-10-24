"""Rules that don't need the rubber constant: thickness from spin/speed (exit angle from the
face), and 'not enough brushing' from the spin itself. Scored against the simulated truth."""
import numpy as np
from contact import serve, R
truth = lambda V, a: frozenset((["thick"] if a > 20 else []) + (["slow"] if V < 4 else [])) or frozenset(["good"])
def rules(rps, speed, ratio_thr, rps_thr):
    ratio = 2 * np.pi * rps * R / speed
    out = (["thick"] if ratio < ratio_thr else []) + (["slow"] if rps < rps_thr and ratio >= ratio_thr else [])
    return frozenset(out) or frozenset(["good"])
def score(ratio_thr, rps_thr, n=1500, seed=3, sp_noise=0.15):
    rng = np.random.default_rng(seed); res = []
    for _ in range(n):
        V, a, fall, kp = rng.uniform(1.5, 10), rng.uniform(0, 60), rng.uniform(0.5, 2.0), rng.uniform(0.45, 0.80)
        s = serve(V, a, fall, kp)
        res.append((truth(V, a), rules(s["rps"] * (1 + rng.normal(0, 0.05)), s["speed"] * (1 + rng.normal(0, sp_noise)), ratio_thr, rps_thr)))
    # a 'thick' serve that is also slow: telling the player 'thick' first is right (fix that first)
    primary = lambda f: "thick" if "thick" in f else ("slow" if "slow" in f else "good")
    acc = np.mean([primary(t) == primary(h) for t, h in res])
    per = {k: (sum(primary(t) == k and primary(h) == k for t, h in res), sum(primary(t) == k for t, h in res), sum(primary(t) != k and primary(h) == k for t, h in res)) for k in ("thick", "slow", "good")}
    return acc, per
best = max(((score(rt, rp, n=500)[0], rt, rp) for rt in (0.8, 0.9, 1.0, 1.05, 1.1, 1.15) for rp in (20, 25, 30, 35)))
print("best thresholds on a tuning set: spin/speed <", best[1], " spin <", best[2], "rps")
acc, per = score(best[1], best[2], seed=99)
print(f"fresh test set: main fault right {100*acc:.0f}%")
for k, (tp, n, fp) in per.items(): print(f"  {k:6}: {tp}/{n} ({100*tp/max(1,n):.0f}%), false alarms {fp}")
