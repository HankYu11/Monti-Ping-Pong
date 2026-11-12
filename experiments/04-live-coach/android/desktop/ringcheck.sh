#!/bin/bash
# The live window ring measured off a phone, on clips a phone recorded.
#
#   desktop/ringcheck.sh out/ serve01.mp4 serve02.mp4 ...
#
# For each clip it runs the real tracker, then reproduces the whole ring (RingSim: the same
# Predict, the same WindowRing, the same Windows.Builder, with the window and the row sample cut
# by the processor instead of the chip), and measures the capture it builds against the one cut
# the old way. The rate must match and the split must not move more than the windowed format
# already moves it.
#
# RINGLAG sweeps how many tracker frames the live track runs behind, which is the one number the
# phone cannot report; RINGMARGIN the window's margin. Both default to what the app ships.
#
# What this does NOT cover: the shader's own arithmetic, the chip's timing, and the camera's
# frames against the decoder's (the ring's ColourFit). Those need a phone.
set -e
cd "$(dirname "$0")/.."
OUT="$1"; shift
mkdir -p "$OUT"
[ -x desktop/build/install/desktop/bin/desktop ] || gradle --no-daemon -q :desktop:installDist
WEB=../../02-spin-meter/web
files=()
for clip in "$@"; do
  name=$(basename "$clip" .mp4)
  echo "=== $name"
  RING=1 JAVA_OPTS=-Xmx6g desktop/build/install/desktop/bin/desktop "$clip" "$OUT" | grep -E "ring \(|\"serve\":" || true
  for f in "$OUT/${name}"_*.cap; do
    case "$f" in *.ring.cap|*.win.cap) continue;; esac
    [ -f "${f%.cap}.ring.cap" ] && files+=("$f" "${f%.cap}.ring.cap")
  done
done
[ ${#files[@]} -gt 0 ] || { echo "no ring capture was built"; exit 1; }
node "$WEB/test/cap.mjs" "${files[@]}"
