#!/usr/bin/env bash
# Download the catalogued serve clips for private analysis.
# Run on your own computer (YouTube blocks downloads from cloud servers).
# Needs: yt-dlp (pip install -U yt-dlp) and ffmpeg.
# Usage: ./fetch_clips.sh [A|B|C|D|all]   (default: A)
# Works with the bash 3.2 that ships with macOS.
set -euo pipefail
cd "$(dirname "$0")"
tier="${1:-A}"
mkdir -p raw

TIER_A="
https://www.youtube.com/watch?v=LFp78dMr3Rk
https://www.youtube.com/watch?v=rRPh_Y5VJTU
https://www.youtube.com/watch?v=k_crfUrDyVI
https://www.youtube.com/watch?v=pBR4g7DoSuc
https://www.youtube.com/watch?v=hFGKoSMHKO8
https://www.youtube.com/watch?v=8GMNuF-eTB4
https://www.youtube.com/watch?v=sphWvygdyEg
https://www.youtube.com/watch?v=luWJofKQUqY
https://www.youtube.com/watch?v=dolQO-M8LJE
https://www.youtube.com/watch?v=2y2WpX1p2LE
https://www.bilibili.com/video/BV1QP4y1W7iJ
https://www.youtube.com/watch?v=yfug_BlwvT0
"
TIER_B="
https://www.youtube.com/watch?v=Je7OoYsVNWo
https://www.youtube.com/watch?v=rfS9hgRQ7QY
https://www.youtube.com/watch?v=F-cUCHj7ibQ
https://www.youtube.com/watch?v=2qSxBl3Omlw
https://www.youtube.com/watch?v=4xD5kbtWqrw
https://www.bilibili.com/video/BV1hBpA6WE1X
https://www.youtube.com/watch?v=RuYhHx3s9-Y
https://www.youtube.com/watch?v=plg3Cxc6MBs
https://www.youtube.com/watch?v=Ag0VJBfgQLw
"
TIER_C="
https://www.youtube.com/watch?v=UzyUDurIR-I
https://www.youtube.com/watch?v=_qdeDyuo_Eo
https://www.youtube.com/watch?v=vLublUZAqlQ
https://www.youtube.com/watch?v=f6BVD8bZw9Y
https://www.youtube.com/watch?v=c_WE17iFnAw
https://www.youtube.com/watch?v=0uelKjwOsqA
https://www.youtube.com/watch?v=oKbhVI7PPD4
https://www.bilibili.com/video/BV1YN4y1F7pq
https://www.bilibili.com/video/BV1fG4y1b7FN
https://www.bilibili.com/video/BV1R94y1B7Pp
https://www.youtube.com/watch?v=tKzr0ILn4Jo
https://www.youtube.com/watch?v=jGqitInXWkY
https://www.youtube.com/watch?v=bQTenTT3bz0
"
TIER_D="
https://www.youtube.com/watch?v=K2t3OOV2RZQ
https://www.youtube.com/watch?v=lpEhmWkWCiI
https://www.bilibili.com/video/BV12t4y1Y7vt
https://www.bilibili.com/video/BV1oW41137cW
https://www.bilibili.com/video/BV16t411C73f
"

if [[ "$tier" == "all" ]]; then tiers=(A B C D); else tiers=("$tier"); fi
for t in "${tiers[@]}"; do
  mkdir -p "raw/$t"
  urls_var="TIER_$t"
  for url in ${!urls_var}; do
    # Highest frame rate first, then resolution: keeps real high-FPS uploads intact.
    yt-dlp --no-overwrites -S "fps,res,vcodec:avc" \
      -o "raw/$t/%(extractor)s_%(id)s.%(ext)s" "$url" \
      || echo "FAILED: $url" >&2
  done
done
