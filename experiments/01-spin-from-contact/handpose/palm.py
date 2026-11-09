# Palm plane (pose model) against the launch direction, per serve, around the contact read by eye.
import json, sys, numpy as np
S = sys.argv[1]
rows = {json.loads(l)['clip'].split('/')[-1]: json.loads(l) for l in open(f"{S}/contact/all4.jsonl")}
contact = {"T1": 197, "T2": 97, "T4": 77, "U2": 57, "V3": 22}
off = {"U2": 3, "V3": 38}          # pose runs started at a negative frame: their labels are shifted
res = {}
for s, fc in contact.items():
    ph = [q for q in rows[s]['phases'] if q['clear']][0]; fl = ph['fl']
    L = np.array(fl['launchVel']); T = np.array(fl['tossVel']) if fl.get('tossVel') else np.zeros(3)
    dv = L - T; rps = ph['rps']
    ratio = 2 * np.pi * 0.02 * rps / np.linalg.norm(dv)
    beta = np.degrees(np.arccos(min(1, ratio / 1.5)))
    P = {}
    for l in open(f"{S}/hand/{s}_pose.jsonl"):
        r = json.loads(l); r['frame'] += off.get(s, 0); P[r['frame']] = r
    def w(r, k): v = np.array(r[k]['w']); v[2] = -v[2]; return v
    def palm(r, h):
        a, b, c = w(r, h + 'w'), w(r, h + 'i'), w(r, h + 'p')
        n = np.cross(b - a, c - a); return n / np.linalg.norm(n)
    def ang(n): return np.degrees(np.arcsin(abs(n @ dv) / np.linalg.norm(dv)))   # 90 - angle to normal = angle to the plane
    # paddle hand = the wrist moving fastest over the 6 frames before contact
    sp = {h: np.mean([np.linalg.norm(w(P[f], h + 'w') - w(P[f - 1], h + 'w')) for f in range(fc - 5, fc + 1) if f in P and f - 1 in P]) for h in 'lr'}
    ph_ = sys.argv[2] if len(sys.argv) > 2 and sys.argv[2] in "lr" else max(sp, key=sp.get); ot = 'l' if ph_ == 'r' else 'r'
    # wrist speed peak (px) as a contact-frame predictor
    px = {f: np.array(P[f][ph_ + 'w']['px']) for f in P if ph_ + 'w' in P[f]}
    v = {f: np.linalg.norm(px[f] - px[f - 1]) for f in px if f - 1 in px}
    fs = sorted(v); vv = np.convolve([v[f] for f in fs], np.ones(3) / 3, 'same'); peak = fs[int(np.argmax(vv))]
    if "auto" in sys.argv: fc = peak
    win = [ang(palm(P[f], ph_)) for f in range(fc - 2, fc + 3) if f in P and ph_ + 'w' in P[f]]
    res[s] = dict(beta=beta, rps=rps, dv=np.linalg.norm(dv), toss=bool(fl.get('tossVel')), hand=ph_,
                  palm=ang(palm(P[fc], ph_)), palm_lo=min(win), palm_hi=max(win), other=ang(palm(P[fc], ot)), peak=peak, fc=fc)
    print(f"{s}: beta {beta:4.1f}  paddle-hand ({ph_}) palm {res[s]['palm']:4.1f} (+-2 frames {min(win):.0f}-{max(win):.0f})  other {res[s]['other']:4.1f}  "
          f"contact {fc}, wrist speed peak {peak} ({peak - fc:+d})  toss {'yes' if res[s]['toss'] else 'no'}")
# constant grip offset, leave one out
k = list(res)
for s in k:
    c = np.mean([res[t]['beta'] - res[t]['palm'] for t in k if t != s])
    b = res[s]['palm'] + c
    pred = 1.5 * res[s]['dv'] * np.cos(np.radians(b)) / (2 * np.pi * 0.02)
    print(f"  leave-one-out {s}: offset {c:.1f}, predicted beta {b:.1f}, spin {pred:.1f} rev/s vs marked {res[s]['rps']:.1f} ({100*(pred/res[s]['rps']-1):+.0f}%)")
print("offsets", {s: round(res[s]['beta'] - res[s]['palm'], 1) for s in k})
print("baseline, no hand (constant beta, leave one out):")
for s in k:
    b = np.mean([res[t]['beta'] for t in k if t != s]); pred = 1.5 * res[s]['dv'] * np.cos(np.radians(b)) / (2 * np.pi * 0.02)
    print(f"  {s}: beta {b:.1f} -> {pred:.1f} vs {res[s]['rps']:.1f} ({100*(pred/res[s]['rps']-1):+.0f}%)")
