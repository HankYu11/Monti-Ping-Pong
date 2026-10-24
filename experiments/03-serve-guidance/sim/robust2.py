import numpy as np
from contact import serve
Vs, As = np.arange(1.0, 12.01, 0.1), np.arange(0, 70.1, 0.5)
tabs = {}
def table(fall):
    f = round(fall * 4) / 4
    if f not in tabs: tabs[f] = np.array([[(lambda s: (s["rps"], s["speed"]))(serve(V, a, f)) for a in As] for V in Vs])
    return tabs[f]
def invert(rps, speed, fall):
    T = table(fall); err = ((T[..., 0] - rps) / max(rps, 5)) ** 2 + ((T[..., 1] - speed) / speed) ** 2
    i, j = np.unravel_index(np.argmin(err), err.shape); return Vs[i], As[j]
diag = lambda V, a: frozenset(([ "thick"] if a > 20 else []) + (["slow"] if V < 4 else [])) or frozenset(["good"])
def run(name, sp_noise, fall_known, kp_fixed, n=300, seed=2):
    rng = np.random.default_rng(seed); ok = good_ok = good_n = 0
    for _ in range(n):
        V, a, fall = rng.uniform(1.5, 10), rng.uniform(0, 60), rng.uniform(0.5, 2.0)
        kp = 0.70 if kp_fixed else rng.uniform(0.45, 0.80)
        s = serve(V, a, fall, kp)
        Vh, ah = invert(s["rps"] * (1 + rng.normal(0, 0.05)), s["speed"] * (1 + rng.normal(0, sp_noise)), fall if fall_known else 1.0)
        t, h = diag(V, a), diag(Vh, ah); ok += t == h
        if t == {"good"}: good_n += 1; good_ok += h == {"good"}
    print(f"{name:52} identical {100*ok/n:3.0f}%   good serves recognised {100*good_ok/max(1,good_n):3.0f}%")
run("as before (speed 15%, fall unknown, rubber unknown)", 0.15, False, False)
run("ball speed better (5%)", 0.05, False, False)
run("fall speed measured", 0.15, True, False)
run("rubber constant known", 0.15, False, True)
run("speed 5% + fall measured", 0.05, True, False)
run("speed 5% + fall measured + rubber known", 0.05, True, True)
