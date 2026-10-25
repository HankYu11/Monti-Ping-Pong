#!/usr/bin/env python3
"""Where a backspin serve lands: flight with drag and Magnus lift, table bounces with friction.
Which launch speeds and angles keep a given spin on the table, and short (bounces twice on the
receiver's side)?  python landing.py

Table: 2.74 m long, net 15.25 cm high at 1.37 m. Contact behind the server's end line.
Air: drag C_D 0.45; lift C_L = S / (2 S + 1) with S = spin surface speed / ball speed (a common
fit for table tennis balls, about 0.25 at S = 1). Bounce: restitution 0.9 upward, table friction
0.25 while the ball slides (a backspin ball always does), hollow ball I = 2/3 m r^2."""
import numpy as np

M, R, RHO = 0.0027, 0.020, 1.2
A = np.pi * R * R
K = 0.5 * RHO * A / M
G = 9.81
NET_X, NET_H, END = 1.37, 0.1525, 2.74
E_T, MU_T = 0.9, 0.25
CL_SCALE = 1.0          # for testing how much the lift matters

def fly(x, y, vx, vy, w, until_bounces=3, dt=1e-4, net_check=True):
    """2D side view: x toward the receiver, y up from the table; w > 0 is backspin (rad/s).
    Returns the bounce x positions, and whether the ball cleared the net."""
    bounces, cleared, crossed = [], True, False
    while len(bounces) < until_bounces and x < END + 1.5 and x > -1.0:
        v = np.hypot(vx, vy)
        S = abs(w) * R / max(v, 1e-6)
        cl = CL_SCALE * S / (2 * S + 1)
        # drag along -v; lift perpendicular: backspin lifts (rotate v by +90 deg for w > 0)
        ax = -K * 0.45 * v * vx + np.sign(w) * K * cl * v * (-vy)
        ay = -G - K * 0.45 * v * vy + np.sign(w) * K * cl * v * vx
        nx, ny = x + vx * dt, y + vy * dt
        if not crossed and x < NET_X <= nx:
            crossed = True
            if ny < NET_H + R: cleared = False
        vx, vy, x, y = vx + ax * dt, vy + ay * dt, nx, ny
        if y <= R and vy < 0:
            if not (0 <= x <= END): bounces.append(x); break
            # bounce: the contact point slides forward at vx + w r (backspin), friction pulls back
            vy_in = -vy
            vy = E_T * vy_in
            P = MU_T * (1 + E_T) * vy_in          # tangential impulse per mass, capped by rolling
            slip = vx + w * R
            P = min(P, abs(slip) / 2.5) * np.sign(slip)   # stops once it rolls: slip drops by (1 + m r^2 / I) P = 2.5 P
            vx -= P
            w -= P * R / (2 / 3 * R * R)
            y = R
            bounces.append(x)
    return bounces, cleared

def legal(b, cleared):
    """o: first bounce off the server's side; 2: bounced twice on the server's side; n: net;
    L: past the end (long, out); H: legal, one bounce on the receiver's side; S: short, two"""
    if not b or not (0 < b[0] < NET_X): return "o"
    if len(b) >= 2 and b[1] < NET_X: return "2"
    if not cleared: return "n"
    if len(b) < 2 or b[1] > END: return "L"
    if len(b) >= 3 and NET_X < b[2] <= END: return "S"
    return "H"

if __name__ == "__main__":
    import sys
    angles = list(range(-30, 41, 5))
    for rps in (30, 40, 50):
        w = 2 * np.pi * rps
        print(f"\n=== backspin {rps} rev/s ===  angle (deg, up +): " + " ".join(f"{a:+3d}" for a in angles))
        for h in (0.15, 0.30):
            print(f"contact {h * 100:.0f} cm above the table, 20 cm behind the end line")
            for v in np.arange(2.0, 5.01, 0.25):
                row = []
                for ang in angles:
                    b, c = fly(-0.20, h, v * np.cos(np.radians(ang)), v * np.sin(np.radians(ang)), w)
                    row.append(legal(b, c))
                print(f"  {v:.2f} m/s (spin/speed {w * R / v:.2f}) " + " ".join(f"{x:>3}" for x in row))
