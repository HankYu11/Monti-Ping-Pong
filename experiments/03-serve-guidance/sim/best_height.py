#!/usr/bin/env python3
"""The best contact height for a short backspin serve, from the flight model (landing.py physics,
vectorised: every speed x angle x model variant flown at once).

For each height above the table (hit 5 cm behind the end line): which launch speeds and angles
give a short serve (first bounce on the server's side, over the net, two bounces on the receiver's
side), in every one of 8 variants of the uncertain numbers (table friction 0.15/0.35, table
restitution 0.85/0.93, lift x0.7/x1.3)? And of those, how low can the ball cross the net (lower
is harder to attack)?  python best_height.py [rps]"""
import sys, itertools
import numpy as np

M, R, RHO, G = 0.0027, 0.020, 1.2, 9.81
K = 0.5 * RHO * np.pi * R * R / M
NET_X, NET_H, END = 1.37, 0.1525, 2.74
X0 = -0.05
# friction, restitution, lift scale. Restitution: the ball standard (dropped from 30.5 cm, back up
# 23-25 cm: 0.87-0.905). Friction: the coach's serves lost 11-16 rev/s at the bounce, a friction
# impulse of 0.96-1.36 m/s (spin surface speed drops 1.5x it), which for a ball coming down at
# 2-2.5 m/s is 0.2-0.3. Lift: a common fit, +-30%.
VARIANTS = list(itertools.product((0.20, 0.30), (0.87, 0.905), (0.7, 1.3)))

def fly_many(h, v, ang, w, mu, e, cls, dt=4e-4, tmax=2.5):
    """All trajectories at once. Returns the outcome code per flight and the height over the net."""
    n = len(v)
    x = np.full(n, X0); y = np.full(n, h)
    vx = v * np.cos(np.radians(ang)); vy = v * np.sin(np.radians(ang)); w = np.full(n, w, float)
    nb = np.zeros(n, int); b = np.full((n, 3), np.nan)
    net_y = np.full(n, np.nan); alive = np.ones(n, bool)
    for _ in range(int(tmax / dt)):
        if not alive.any(): break
        sp = np.hypot(vx, vy); S = np.abs(w) * R / np.maximum(sp, 1e-6)
        cl = cls * S / (2 * S + 1)
        ax = -K * 0.45 * sp * vx + np.sign(w) * K * cl * sp * (-vy)
        ay = -G - K * 0.45 * sp * vy + np.sign(w) * K * cl * sp * vx
        nx, ny = x + vx * dt, y + vy * dt
        cross = alive & (x < NET_X) & (nx >= NET_X) & np.isnan(net_y)
        net_y[cross] = ny[cross]
        x = np.where(alive, nx, x); y = np.where(alive, ny, y)
        vx = np.where(alive, vx + ax * dt, vx); vy = np.where(alive, vy + ay * dt, vy)
        hitt = alive & (y <= R) & (vy < 0)
        if hitt.any():
            idx = np.where(hitt)[0]
            on = (x[idx] >= 0) & (x[idx] <= END)
            b[idx, np.minimum(nb[idx], 2)] = x[idx]
            nb[idx] += 1
            off = idx[~on]; alive[off] = False
            j = idx[on]
            vin = -vy[j]; vy[j] = e * vin
            P = mu * (1 + e) * vin; slip = vx[j] + w[j] * R
            P = np.minimum(P, np.abs(slip) / 2.5) * np.sign(slip)
            vx[j] -= P; w[j] -= P * R / (2 / 3 * R * R); y[j] = R
            alive[j[nb[j] >= 3]] = False
        alive &= (x < END + 1.5) & (x > -1.0)
    # outcome
    b0, b1, b2 = b[:, 0], b[:, 1], b[:, 2]
    clear = ~np.isnan(net_y) & (net_y >= NET_H + R)
    short = (b0 > 0) & (b0 < NET_X) & (b1 > NET_X) & (b1 <= END) & clear & (b2 > NET_X) & (b2 <= END)
    legal = (b0 > 0) & (b0 < NET_X) & (b1 > NET_X) & (b1 <= END) & clear
    return short, legal, net_y - R          # ball's bottom over the table at the net

if __name__ == "__main__":
    rps = float(sys.argv[1]) if len(sys.argv) > 1 else 40.0
    w = 2 * np.pi * rps
    speeds = np.arange(2.0, 6.01, 0.25); angles = np.arange(-40, 51, 5)
    V, A = np.meshgrid(speeds, angles, indexing="ij"); V, A = V.ravel(), A.ravel()
    print(f"backspin {rps:.0f} rev/s, hit 5 cm behind the end line; short in all 8 model variants")
    print(" height | short serves (of 323) | speeds       | angles (deg, up +) | lowest over the net | at speed, angle")
    for h in (0.02, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30, 0.40):
        ok = np.ones(len(V), bool); ok_one = np.zeros(len(V), bool); over = np.zeros(len(V)); okL = np.ones(len(V), bool)
        for mu, e, cls in VARIANTS:
            s, lg, ny = fly_many(h, V, A, w, mu, e, cls)
            ok &= s; ok_one |= s; okL &= lg; over = np.maximum(over, np.nan_to_num(ny, nan=9))
        legal_note = f"  [legal in all: {okL.sum()}]"
        if ok.any():
            k = np.argmin(np.where(ok, over, 9))
            print(f" {h * 100:4.0f} cm | {ok.sum():3d} ({ok_one.sum():3d} in some variant) | {V[ok].min():.2f}-{V[ok].max():.2f} m/s | "
                  f"{A[ok].min():+4.0f} .. {A[ok].max():+4.0f}      | {over[k] * 100:4.1f} cm (net 15.3) | {V[k]:.2f} m/s, {A[k]:+.0f}" + legal_note)
        else:
            print(f" {h * 100:4.0f} cm |   0 ({ok_one.sum():3d} in some variant)" + legal_note)
