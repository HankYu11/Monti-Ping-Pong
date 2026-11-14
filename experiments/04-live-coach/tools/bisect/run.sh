#!/bin/bash
# usage: run.sh <commit> <label> <clip.mp4>...
#
# Exports the Android project exactly as it was at <commit>, applies harness_patch.py (pass
# EMU=1 NOWEB=1 RIG=1 for a software emulator; nothing for a phone), builds it, installs it
# fresh on the connected device, and replays each clip through the app's own TEST VIDEO.
# Prints one line per clip: what the app said when the replay finished, and how long it took.
# The app's full event log for each run is kept in $WORK/out.
#
# Needs: git, gradle (or the project's own toolchain), adb with exactly one device attached
# (or ANDROID_SERIAL set), Android SDK on ANDROID_HOME.
set -u
COMMIT=$1; LABEL=$2; shift 2
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(git -C "$HERE" rev-parse --show-toplevel)
WORK=${WORK:-${TMPDIR:-/tmp}/servecoach-bisect}
WT=$WORK/wt; OUT=$WORK/out; mkdir -p "$OUT"
ADB=${ADB:-adb}
PKG=com.monti.servecoach

APK=$OUT/$LABEL.apk
if [ ! -f "$APK" ]; then
  rm -rf "$WT"; mkdir -p "$WT"
  git -C "$REPO" archive "$COMMIT" experiments/04-live-coach/android | tar -x -C "$WT"
  P=$WT/experiments/04-live-coach/android
  python3 "$HERE/harness_patch.py" "$P" || { echo "$LABEL: PATCH FAILED"; exit 1; }
  ( cd "$P" && timeout 1500 gradle --no-daemon -q :app:assembleDebug 2>&1 | grep -v JAVA_TOOL | grep -iE "^e:|FAIL" | head -5 )
  cp "$P/app/build/outputs/apk/debug/app-debug.apk" "$APK" 2>/dev/null || { echo "$LABEL: BUILD FAILED"; exit 1; }
  rm -rf "$WT"
fi

$ADB uninstall $PKG >/dev/null 2>&1
timeout 900 $ADB install -g "$APK" >/dev/null 2>&1 || { echo "$LABEL: INSTALL FAILED"; exit 1; }
$ADB shell run-as $PKG mkdir -p files

for CLIP in "$@"; do
  NAME=$(basename "$CLIP")
  $ADB push "$CLIP" /data/local/tmp/$NAME >/dev/null 2>&1
  $ADB shell run-as $PKG cp /data/local/tmp/$NAME files/$NAME
  $ADB shell am force-stop $PKG
  $ADB logcat -c
  T0=$(date +%s)
  $ADB shell am start -n $PKG/.MainActivity -a android.intent.action.VIEW -d file:///data/data/$PKG/files/$NAME >/dev/null
  RES=""
  for i in $(seq 1 360); do          # up to 30 minutes
    sleep 5
    RES=$($ADB logcat -d -s SCBISECT:I 2>/dev/null | grep -E "replay: done|Video test failed|error: replay: " | tail -1)
    [ -n "$RES" ] && break
    $ADB shell pidof $PKG >/dev/null || { RES="APP DIED"; break; }
  done
  $ADB logcat -d -s SCBISECT:I > "$OUT/$LABEL-$NAME.log" 2>/dev/null
  $ADB logcat -d -b crash > "$OUT/$LABEL-$NAME.crash" 2>/dev/null
  echo "$LABEL $NAME: ${RES:-TIMED OUT} ($(( $(date +%s) - T0 ))s)"
  $ADB shell am force-stop $PKG
done
