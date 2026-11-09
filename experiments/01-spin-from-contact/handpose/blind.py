# Fully automatic: contact frame = paddle wrist speed peak 12-40 frames before the flight track; beta = palm angle + offset.
import json, sys, numpy as np
S, OFF = sys.argv[1], float(sys.argv[2])
rows = {json.loads(l)['clip'].split('/')[-1]: json.loads(l) for l in open(f"{S}/contact/all4.jsonl")}
off = {"U2": 3, "V3": 38}
errs = []
for s in sys.argv[3:]:
    o = rows[s]; ph = [q for q in o['phases'] if q['clear']][0]; fl = ph['fl']; st = o['first'] + ph['start']
    dv = np.array(fl['launchVel']) - (np.array(fl['tossVel']) if fl.get('tossVel') else 0)
    P = {}
    for l in open(f"{S}/hand/{s}_pose.jsonl"):
        r = json.loads(l); r['frame'] += off.get(s, 0)
        if 'lw' in r: P[r['frame']] = r
    def w(r, k): v = np.array(r[k]['w']); v[2] = -v[2]; return v
    def speed(h):
        fs = [f for f in range(st - 40, st - 11) if f in P and f - 1 in P]
        v = np.array([np.linalg.norm(np.array(P[f][h + 'w']['px']) - np.array(P[f - 1][h + 'w']['px'])) for f in fs])
        v = np.convolve(v, np.ones(3) / 3, 'same'); return fs, v
    hs = {h: speed(h) for h in 'lr'}
    h = max(hs, key=lambda h: hs[h][1].max()); fs, v = hs[h]; fc = fs[int(np.argmax(v))]
    r = P[fc]; a, b, c = w(r, h + 'w'), w(r, h + 'i'), w(r, h + 'p'); n = np.cross(b - a, c - a); n /= np.linalg.norm(n)
    palm = np.degrees(np.arcsin(abs(n @ dv) / np.linalg.norm(dv))); beta = min(89, palm + OFF)
    pred = 1.5 * np.linalg.norm(dv) * np.cos(np.radians(beta)) / (2 * np.pi * 0.02)
    ceil = 1.5 * np.linalg.norm(dv) / (2 * np.pi * 0.02)
    e = 100 * (pred / ph['rps'] - 1); errs.append(e)
    print(f"{s}: hand {h}, contact {fc} ({fc - st:+d} from flight), vis {r[h+'w']['vis']:.2f}, palm {palm:4.1f} -> beta {beta:4.1f}: "
          f"{pred:5.1f} rev/s vs marked {ph['rps']:4.1f} ({e:+.0f}%)  [ceiling {ceil:.0f}, toss {'yes' if fl.get('tossVel') else 'no'}]")
print("median |error| %.0f%%" % np.median(np.abs(errs)))
