"""What to work on, from what the video measures: the spin, the ball's velocity as it left the
paddle and the toss's velocity as it reached it (m/s, camera axes: x right, y down, z toward it).

The paddle changes the ball's velocity by the contact impulse (per unit mass), dv = launch - toss.
Its part along the face sets the spin: the surface speed spin * r is 1.5x that part (hollow
ball, I = 2/3 m r^2), whatever the rubber. So spin gives the along-face part, dv's size gives the
rest, the press into the rubber, and their ratio is how much of the rubber's grip was used:
at the rubber's friction limit (~1.2) the ball slid instead of being carried.
"""
import numpy as np
from contact import R, MU
TARGET = 30.0            # rps: below this a backspin serve is easy to attack
THICK = 0.9              # spin surface speed / ball speed below this: pushed, not brushed
SLIP = 0.92 * MU         # grip used at (nearly) the rubber's limit: it slid

def grip_used(rps, launch, toss):
    dv = np.asarray(launch, float) - np.asarray(toss, float)
    along = 2 * np.pi * rps * R / 1.5
    press = np.sqrt(max(dv @ dv - along * along, 1e-6))
    return along / press

# How far a reading can be off (synthetic end-to-end test): ball speed about 6%, spin under 1%,
# grip used about 0.08. Within these of a line the serve could be either side: both are named.
RATIO_BAND = (0.85, 0.95)
GRIP_BAND = (1.02, 1.18)
RPS_BAND = (28.5, 31.5)

def diagnose(rps, flight):
    speed = flight["launchSpeed"]
    ratio = 2 * np.pi * rps * R / speed
    grip = grip_used(rps, flight["launchVel"], flight["tossVel"]) if flight.get("tossVel") else None
    def thin():                               # thin contact: enough spin, or which of slip / slow
        if rps >= TARGET: return "good", ("slip" if grip is not None and grip >= SLIP else "slow") if rps < RPS_BAND[1] else None
        if grip is None: return ("slip" if ratio >= 1.25 else "slow"), None
        kind = "slip" if grip >= SLIP else "slow"
        alt = ("slow" if kind == "slip" else "slip") if GRIP_BAND[0] <= grip < GRIP_BAND[1] else None
        if rps >= RPS_BAND[0]: alt = "good"
        return kind, alt
    if ratio < THICK:
        kind, alt = "thick", (thin()[0] if ratio >= RATIO_BAND[0] else None)
    else:
        kind, alt = thin()
        if ratio < RATIO_BAND[1]: alt = "thick"
    return dict(kind=kind, alt=alt, ratio=ratio, grip=grip, rps=rps, speed=speed)


KP_RANGE = (0.45, 0.80)  # the rubber's carry (kp / m): what share of the slide it turns into impulse

def advice(d, toss=None):
    """The coaching for one serve: a headline, what was measured, why, and what to change.
    d from diagnose(); toss: the toss's speed into the paddle, m/s, if seen."""
    rps, ratio, grip = d["rps"], d["ratio"], d["grip"]
    along = 2 * np.pi * rps * R / 1.5                       # the impulse along the face, m/s
    brush = tuple(along / k for k in KP_RANGE[::-1])        # paddle speed along the face it takes (grip)
    seen = f"{rps:.0f} rev/s ({rps * 60:.0f} RPM), ball {d['speed']:.1f} m/s, spin÷speed {ratio:.2f}"
    if d["kind"] == "thick":
        return dict(title="You pushed the ball instead of brushing it",
                    measured=seen + " (a brush gives 0.9–1.5)",
                    why="The ball left fast for its spin: the paddle moved into the ball more than along under it, so the "
                        "contact drove it forward instead of spinning it.",
                    fix=["Open the face more and swing along it, under the ball, not toward the net.",
                         "Contact the ball's bottom, a little behind it, and let it roll off the face.",
                         "Aim for a slower ball with the same swing: spin÷speed should reach 0.9 or more."],
                    goal="spin÷speed 0.9 or more")
    if d["kind"] == "good":
        return dict(title="Strong backspin", measured=seen,
                    why="Thin contact and a fast brush: the ball spins hard for its speed.",
                    fix=["Keep the contact and work on placement: short (two bounces on their side) and low over the net."],
                    goal=f"keep it above {TARGET:.0f} rev/s")
    if d["kind"] == "slip":
        t = f" The toss was falling at {toss:.1f} m/s when you hit it." if toss else ""
        return dict(title="The ball slid on the rubber",
                    measured=seen + (f", grip used {grip:.2f} of about {MU:.1f}" if grip is not None else ""),
                    why="The contact was thin, but the ball pressed into the rubber too lightly to be carried: it used all the "
                        "grip there was and slid. A faster swing won't add spin until it presses harder." + t,
                    fix=["Let the ball drop lower before you hit it (from a higher toss), so it falls onto the paddle faster.",
                         "Brush slightly into the ball as well as along it, so it sinks into the rubber.",
                         "Clean the rubber: dust lowers its grip."],
                    goal=f"{TARGET:.0f} rev/s or more with spin÷speed staying above 0.9")
    return dict(title="The brush was too slow",
                measured=seen + (f", grip used {grip:.2f} of about {MU:.1f}" if grip is not None else ""),
                why=f"The contact was thin and the rubber held the ball, so the spin is set by how fast the paddle moved along "
                    f"under it: about {brush[0]:.1f}–{brush[1]:.1f} m/s. {TARGET:.0f} rev/s takes about "
                    f"{brush[0] * TARGET / rps:.1f}–{brush[1] * TARGET / rps:.1f} m/s.",
                fix=["Accelerate through the contact: a loose wrist that snaps at the ball, not a steady push.",
                     "Hit nearer the tip of the blade, which moves fastest.",
                     "Keep the face as open as now: only the speed changes."],
                goal=f"{TARGET:.0f} rev/s or more")
