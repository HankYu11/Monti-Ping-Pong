#!/usr/bin/env bash
# Render the clips the harnesses measure against. Each .mp4 gets a .json beside it with the
# true spin, axis and the ball's track, so a reading can be scored instead of eyeballed.
#
#   bash test/make_clips.sh /tmp/clips
set -euo pipefail
OUT="${1:-/tmp/spin-clips}"
SIM="$(cd "$(dirname "$0")/../.." && pwd)/spinsim.py"
mkdir -p "$OUT"
# side-on, ball crossing the picture, at a real serve's speed (20 px/frame of a 40 px ball
# at 240 a second is about 4.8 m/s). Slower than this and the engine rightly calls it no serve.
B=(--size 1280 720 --frames 45 --seed 1)
mk() { n="$1"; shift; python3 "$SIM" --out "$OUT/$n.mp4" "$@" "${B[@]}" >/dev/null; echo "  $n"; }

echo "spin rate, axis, ball size, flight speed, noise:"
mk k_rps10  --rps  10 --diameter 40 --speed 20
mk k_rps20  --rps  20 --diameter 40 --speed 20
mk k_rps30  --rps  30 --diameter 40 --speed 20
mk k_rps45  --rps  45 --diameter 40 --speed 20
mk k_rps60  --rps  60 --diameter 40 --speed 20     # about the fastest serve ever measured
mk k_back30 --rps -30 --diameter 40 --speed 20
mk k_side30 --rps  30 --axis 1 0 0   --diameter 40 --speed 20
mk k_tilt30 --rps  30 --axis 0.6 0 0.8 --diameter 40 --speed 20
mk k_small  --rps  30 --diameter 25 --speed 20
mk k_big    --rps  30 --diameter 60 --speed 20
mk k_fast   --rps  30 --diameter 40 --speed 28
mk k_slow   --rps  30 --diameter 40 --speed 13
mk k_noisy  --rps  30 --diameter 40 --speed 20 --noise 9

echo "the same serve under different light and colour conventions (the spin must not move):"
mk p_ref       --rps 30 --diameter 40 --speed 20
mk p_tvrange   --rps 30 --diameter 40 --speed 20 --range tv        # 16-235, tagged
mk p_bright    --rps 30 --diameter 40 --speed 20 --gain 1.35
mk p_pedestal  --rps 30 --diameter 40 --speed 20 --pedestal 40     # lifted black, haze
mk p_dimped    --rps 30 --diameter 40 --speed 20 --gain 0.6 --pedestal 30
mk p_gamma     --rps 30 --diameter 40 --speed 20 --gamma 1.35
mk p_dim       --rps 30 --diameter 40 --speed 20 --gain 0.5        # too dark: a refusal is right

echo "written to $OUT"
