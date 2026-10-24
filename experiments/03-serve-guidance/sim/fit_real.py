"""Which paddle speed and contact angle explain each real backspin serve's spin and speed?"""
import numpy as np
from contact import serve

REAL = {  # off-the-paddle readings from the spin meter: rps, ball speed m/s
    "H (weak)": (15.6, 4.1), "A": (39.0, 5.3), "D": (41.4, 4.3), "M": (44.6, 4.1),
    "N": (46.7, 4.1), "B": (50.8, 4.9), "E2": (53.0, 4.4),
}

def fit(rps, speed, fall, kp=0.70):
    best = None
    for V in np.arange(1.0, 14.01, 0.1):
        for a in np.arange(0, 80.1, 0.5):
            s = serve(V, a, fall, kp)
            err = ((s["rps"] - rps) / max(rps, 5)) ** 2 + ((s["speed"] - speed) / speed) ** 2
            if best is None or err < best[0]:
                best = (err, V, a, s)
    return best

print(f"{'serve':9} {'rps':>5} {'m/s':>5} | " + " | ".join(f"fall {f} m/s: paddle m/s, angle°, fit err" for f in (0.5, 1.0, 2.0)))
for name, (rps, sp) in REAL.items():
    row = []
    for fall in (0.5, 1.0, 2.0):
        err, V, a, s = fit(rps, sp, fall)
        row.append(f"{V:4.1f} {a:5.1f} {np.sqrt(err)*100:4.0f}%{' slide' if s['sliding'] else '      '}")
    print(f"{name:9} {rps:5.1f} {sp:5.1f} | " + " | ".join(row))
