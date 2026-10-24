"""Paddle-ball contact for a serve: what spin and speed a given paddle movement gives.

Impulse model for inverted rubber, after Nakashima et al. as used by Gossard (2026,
arXiv 2604.11349, 8,194 measured bounces on 10 rackets):

  - normal:     the ball's speed into the face reverses with restitution e, which falls
                about 0.02 per m/s of impact speed (about 0.15 from 2 to 12 m/s);
  - tangential: impulse P = -kp * v_s, v_s = how fast the ball's contact point slides
                across the rubber. Inverted rubbers share one kp; the rubber's sideways
                spring-back makes it larger than the 'ends up rolling' value m/2.5;
  - friction:   P can't exceed mu times the normal impulse (then the ball slides).

Ball: 2.7 g, 40 mm, hollow: I = 2/3 m r^2.

Axes: x along the paddle face in the swing direction, z out of the face (toward the ball).
"""
import numpy as np

M, R = 0.0027, 0.020
I = 2.0 / 3.0 * M * R * R

# kp as a fraction of the ball's mass. m/2.5 = 0.4 m would be 'ends up rolling'; inverted
# rubber springs back sideways and gives more. The published kp is quoted in units that
# don't map to m directly, so this is the main uncertain number: 0.70 is the central value,
# 0.45-0.80 the range the analysis must stay right across.
KP_OVER_M = 0.70
MU = 1.2            # tacky inverted rubber; sliding limit
E0, E_SLOPE = 0.92, 0.02


def restitution(vz):
    return float(np.clip(E0 - E_SLOPE * abs(vz), 0.55, 0.95))


def impact(v_ball, w_ball, v_paddle, normal, kp_over_m=KP_OVER_M, mu=MU):
    """One contact. v_ball, w_ball: ball velocity (m/s) and spin (rad/s) before; v_paddle:
    velocity of the paddle surface at the contact point; normal: unit vector out of the face
    toward the ball. Returns (v_ball_after, w_ball_after, sliding)."""
    n = np.asarray(normal, float) / np.linalg.norm(normal)
    v = np.asarray(v_ball, float) - np.asarray(v_paddle, float)    # in the paddle's frame
    w = np.asarray(w_ball, float)
    vn = v @ n
    if vn >= 0:                                    # not approaching: no contact
        return np.asarray(v_ball, float), w, False
    e = restitution(vn)
    Pn = -(1 + e) * M * vn                         # normal impulse (along +n)
    # sliding speed of the ball's contact point (at -R n from its centre) across the rubber
    vs = v + np.cross(w, -R * n)
    vs = vs - (vs @ n) * n
    P = -kp_over_m * M * vs
    sliding = np.linalg.norm(P) > mu * Pn
    if sliding:
        P = -mu * Pn * vs / max(np.linalg.norm(vs), 1e-12)
    J = Pn * n + P
    v_after = np.asarray(v_ball, float) + J / M
    w_after = w + np.cross(-R * n, P) / I
    return v_after, w_after, bool(sliding)


def serve(paddle_speed, brush_angle_deg, fall_speed=1.0, kp_over_m=KP_OVER_M, mu=MU):
    """Backspin serve seen side-on, open paddle face pointing up (normal +z), paddle moving
    forward (+x) and slightly up into the ball at brush_angle above the face. The tossed
    ball falls onto it at fall_speed. Returns spin (rps), ball speed (m/s), launch angle above
    the face (deg), spin/speed figure (surface speed / ball speed), sliding."""
    a = np.radians(brush_angle_deg)
    vp = paddle_speed * np.array([np.cos(a), 0, np.sin(a)])
    v1, w1, sl = impact([0, 0, -fall_speed], [0, 0, 0], vp, [0, 0, 1], kp_over_m, mu)
    rps = np.linalg.norm(w1) / (2 * np.pi)
    sp = np.linalg.norm(v1)
    return dict(rps=rps, speed=sp, launch=np.degrees(np.arctan2(v1[2], v1[0])),
                ratio=np.linalg.norm(w1) * R / sp, sliding=sl, backspin=w1[1] < 0 if False else w1[1])


def hit(paddle_speed, brush_angle_deg, fall_speed, kp_over_m=KP_OVER_M, tilt_deg=-20.0, mu=MU):
    """The serve seen side-on, in the world: x forward (toward the net), y up, z toward the
    camera. The face is open, its normal leaning `tilt` from straight up (negative: toward the
    net, as in a real backspin serve); the paddle moves along the face (forward, and down when the
    face leans forward) plus `brush_angle` into the ball. The toss falls straight down onto it.
    Returns the outcome with the vectors and the face's directions."""
    t = np.radians(tilt_deg)
    n = np.array([-np.sin(t), np.cos(t), 0.0])
    along = np.array([np.cos(t), np.sin(t), 0.0])
    a = np.radians(brush_angle_deg)
    vp = paddle_speed * (np.cos(a) * along + np.sin(a) * n)
    v1, w1, sl = impact([0, -fall_speed, 0], [0, 0, 0], vp, n, kp_over_m, mu)
    sp = np.linalg.norm(v1)
    return dict(rps=float(np.linalg.norm(w1) / (2 * np.pi)), speed=float(sp), ratio=float(np.linalg.norm(w1) * R / sp),
                sliding=bool(sl), backspin=bool(w1[2] > 0), v=v1, w=w1, v_paddle=vp, normal=n, along=along)


if __name__ == "__main__":
    print("paddle m/s  brush°  ->  rps   ball m/s  launch°  spin/speed  sliding")
    for V in (4, 6, 8, 10):
        for ang in (0, 5, 10, 20, 30, 45, 60):
            s = serve(V, ang)
            print(f"{V:6.1f} {ang:8.0f}      {s['rps']:6.1f} {s['speed']:7.2f} {s['launch']:7.1f} {s['ratio']:9.2f}   {s['sliding']}")
