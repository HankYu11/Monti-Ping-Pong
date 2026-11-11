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
mk() { n="$1"; shift; python3 "$SIM" --out "$OUT/$n.mp4" "$@" "${B[@]}" >/dev/null; echo "  $n"; }

echo "pure gyro, both ways round (the ball flies along its own spin axis):"
mk g_pure_pos  --rps  30 --axis 1 0 0
mk g_pure_neg  --rps -30 --axis 1 0 0
echo "gyro mixed with lift and curve, both ways round:"
mk g_mix_pos   --rps 32 --axis  0.80 -0.35 0.49
mk g_mix_neg   --rps 32 --axis -0.80 -0.35 0.49
mk g_tilt_pos  --rps 28 --axis  0.60  0.30 0.74
mk g_tilt_neg  --rps 28 --axis -0.60  0.30 0.74
echo "written to $OUT"
