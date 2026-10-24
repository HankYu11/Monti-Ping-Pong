"""Can the paddle speed and contact angle be recovered from noisy spin + ball speed readings?

Random serves (paddle speed, angle, fall speed, rubber constant) -> true spin/speed ->
add measurement noise -> invert with the CENTRAL rubber constant and an assumed fall speed ->
compare with the truth, and check the diagnosis it would give."""
import numpy as np
from contact import serve

rng = np.random.default_rng(1)
# lookup table for the inversion (central kp, assumed fall speed 1.0 m/s)
Vs, As = np.arange(1.0, 12.01, 0.1), np.arange(0, 70.1, 0.5)
TAB = np.array([[(lambda s: (s["rps"], s["speed"]))(serve(V, a, 1.0)) for a in As] for V in Vs])

def invert(rps, speed):
    err = ((TAB[..., 0] - rps) / max(rps, 5)) ** 2 + ((TAB[..., 1] - speed) / speed) ** 2
    i, j = np.unravel_index(np.argmin(err), err.shape)
    return Vs[i], As[j]

def diagnose(V, a):
    out = []
    if a > 20: out.append("thick")
    if V < 4.0: out.append("slow")
    return out or ["good"]

N, rows = 400, []
for _ in range(N):
    V, a = rng.uniform(1.5, 10), rng.uniform(0, 60)
    fall, kp = rng.uniform(0.5, 2.0), rng.uniform(0.45, 0.80)
    s = serve(V, a, fall, kp)
    rps_m = s["rps"] * (1 + rng.normal(0, 0.05))         # spin meter: ~5%
    sp_m = s["speed"] * (1 + rng.normal(0, 0.15))        # ball speed from one camera: ~15%
    Vh, ah = invert(rps_m, sp_m)
    rows.append((V, a, Vh, ah, set(diagnose(V, a)), set(diagnose(Vh, ah))))
V, a, Vh, ah = (np.array([r[k] for r in rows]) for k in range(4))
print(f"paddle speed: median error {np.median(np.abs(Vh - V)):.2f} m/s, 90% within {np.percentile(np.abs(Vh - V), 90):.2f} m/s")
print(f"contact angle: median error {np.median(np.abs(ah - a)):.1f} deg, 90% within {np.percentile(np.abs(ah - a), 90):.1f} deg")
same = np.mean([r[4] == r[5] for r in rows])
print(f"diagnosis identical to the truth: {100 * same:.0f}%")
for lab in ("thick", "slow", "good"):
    tp = sum(lab in r[4] and lab in r[5] for r in rows); fn = sum(lab in r[4] and lab not in r[5] for r in rows); fp = sum(lab not in r[4] and lab in r[5] for r in rows)
    print(f"  {lab:6}: found {tp}/{tp + fn} ({100 * tp / max(1, tp + fn):.0f}%), false alarms {fp}")
# where do the misses sit? near the thresholds?
bad = [r for r in rows if r[4] != r[5]]
near = sum(abs(r[1] - 20) < 6 or abs(r[0] - 4) < 0.8 for r in bad)
print(f"wrong diagnoses close to a threshold (angle within 6 deg of 20, or speed within 0.8 m/s of 4): {near}/{len(bad)}")
