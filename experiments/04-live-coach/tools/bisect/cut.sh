#!/bin/bash
# usage: cut.sh <stock slow-motion.mp4> <first frame> <frame count> <out.mp4>
#
# A stretch of a phone's slow-motion video as the app's camera would deliver it: the same
# frames, re-timed to real 240 fps (the stock camera stores slow motion stretched to 30 fps
# playback, 33 ms apart, and the app's chip path numbers frames by timestamp). Through a raw pipe
# so every frame is kept exactly once -- a duplicated frame is the one thing a test about frozen
# pictures must never be fed. Checks the result and refuses it if a frame repeats.
#
# The two used so far, from PXL_20261114_161527357.mp4 (two serves, counted by eye):
#   cut.sh PXL_20261114_161527357.mp4 700 520 s1.mp4      # serve near frame 900
#   cut.sh PXL_20261114_161527357.mp4 1950 480 s2.mp4     # serve near frame 2150
set -eu
IN=$1; FIRST=$2; N=$3; OUT=$4
LAST=$(( FIRST + N - 1 ))
SIZE=$(ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0:s=x "$IN")
ffmpeg -v error -i "$IN" -vf "select='between(n\,$FIRST\,$LAST)'" -fps_mode passthrough -f rawvideo -pix_fmt yuv420p - |
  ffmpeg -v error -y -f rawvideo -pix_fmt yuv420p -s $SIZE -r 240 -i - -c:v libx264 -preset fast -crf 14 -bf 0 -pix_fmt yuv420p "$OUT"
got=$(ffprobe -v error -select_streams v:0 -count_frames -show_entries stream=nb_read_frames -of csv=p=0 "$OUT")
dups=$(ffmpeg -v error -i "$OUT" -f framemd5 - 2>/dev/null | grep -v '^#' | awk -F', *' '{print $6}' | sort | uniq -d | wc -l)
echo "$OUT: $got frames (wanted $N), $dups repeated"
[ "$got" = "$N" ] && [ "$dups" = 0 ] || { echo "REFUSED: not a faithful cut"; exit 1; }
