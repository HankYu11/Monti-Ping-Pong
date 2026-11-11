#!/usr/bin/env bash
# Clips with a gyro worth signing: the spin axis points along the flight, so the ball drills
# rather than curves. Same recipe as make_clips.sh, in pairs that differ only in which way round.
#
#   bash test/make_gyro_clips.sh /tmp/gyro
set -euo pipefail
OUT="${1:-/tmp/gyro-clips}"
SIM="$(cd "$(dirname "$0")/../.." && pwd)/spinsim.py"
mkdir -p "$OUT"
B=(--size 1280 720 --frames 45 --seed 1 --diameter 40 --speed 20)
# the base goes FIRST so a clip's own flags win: argparse keeps the last of a repeated option,
# and the base sets --diameter and --speed, which some clips below deliberately change.
mk() { n="$1"; shift; python3 "$SIM" --out "$OUT/$n.mp4" "${B[@]}" "$@" >/dev/null; echo "  $n"; }

echo "pure gyro, both ways round (the ball flies along its own spin axis):"
mk g_pure_pos  --rps  30 --axis 1 0 0
mk g_pure_neg  --rps -30 --axis 1 0 0
echo "gyro mixed with lift and curve, both ways round:"
mk g_mix_pos   --rps 32 --axis  0.80 -0.35 0.49
mk g_mix_neg   --rps 32 --axis -0.80 -0.35 0.49
mk g_tilt_pos  --rps 28 --axis  0.60  0.30 0.74
mk g_tilt_neg  --rps 28 --axis -0.60  0.30 0.74

# The same, in colour, with the marking the Serve Coach actually reads: black half plus the three
# dots. Until spinsim could render colour, every synthetic clip came out one grey channel and so
# read with the HALF marking -- which meant the colour-dot path, the only one whose gyro sign the
# engine trusts, had never once been checked against a known spin.
echo "the real marking, in colour, so the dots path is tested at all:"
mk g_dots_pos  --rps  30 --axis 1 0 0 --diameter 40 --pattern half+dots
mk g_dots_neg  --rps -30 --axis 1 0 0 --diameter 40 --pattern half+dots
mk g_dots_mix  --rps  32 --axis 0.80 -0.35 0.49 --diameter 40 --pattern half+dots

# What actually costs the dots in the field. The club reads half on every serve; these separate
# the three suspects. Small and blue-backed still read dots; heavy blur is what tips it, and blur
# over a blue table is the one that falls back to half.
echo "why the club reads half: ball size, the blue table, motion blur:"
mk g_dots_small  --rps 30 --axis 0.80 -0.35 0.49 --diameter 30 --pattern half+dots
mk g_dots_blue   --rps 30 --axis 0.80 -0.35 0.49 --diameter 40 --pattern half+dots --bg 30 70 140
mk g_dots_blur   --rps 30 --axis 0.80 -0.35 0.49 --diameter 36 --pattern half+dots --exposure 0.9
mk g_dots_worst  --rps 30 --axis 0.80 -0.35 0.49 --diameter 36 --pattern half+dots --exposure 0.9 --bg 30 70 140

echo "written to $OUT"
