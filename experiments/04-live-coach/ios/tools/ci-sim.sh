#!/bin/bash
# The iOS app on a simulator, as CI runs it: build, the in-app self-tests (-selftest), then the
# committed serve clips through the video test (-replay), each answer checked against
# tools/ci-expect.json. Run from experiments/04-live-coach/ios. Exits non-zero on any miss.
set -euo pipefail
cd "$(dirname "$0")/.."
DD=${DERIVED:-/tmp/sc-ci-dd}
SIM=${SIM:-$(xcrun simctl list devices available -j | python3 -c '
import json,sys
d=json.load(sys.stdin)["devices"]
ids=[x["udid"] for rt,l in sorted(d.items(), reverse=True) if "iOS" in rt for x in l if x["name"].startswith("iPhone")]
print(ids[0])')}
echo "simulator $SIM"
xcrun simctl boot "$SIM" 2>/dev/null || true
xcrun simctl bootstatus "$SIM" -b >/dev/null
xcodebuild -project ServeCoach.xcodeproj -scheme ServeCoach -sdk iphonesimulator -destination "id=$SIM" \
  -derivedDataPath "$DD" CODE_SIGNING_ALLOWED=NO build | tail -3
xcrun simctl install "$SIM" "$DD/Build/Products/Debug-iphonesimulator/ServeCoach.app"
# the camera prompt the app raises at start-up would take focus from the next launch, and a video
# test stops when the app loses focus (as on Android): grant it up front so no prompt appears
xcrun simctl privacy "$SIM" grant camera com.monti.servecoach || true
OUT=$(mktemp -d)

# run the app with arguments until a line matching $2 appears (or $3 seconds pass); log in $OUT/$1.log
run() {
  local name=$1 until=$2 secs=$3; shift 3
  xcrun simctl launch --console-pty --terminate-running-process "$SIM" com.monti.servecoach "$@" > "$OUT/$name.log" 2>&1 &
  local pid=$! t=0
  until grep -q "$until" "$OUT/$name.log" 2>/dev/null || [ $t -ge $secs ]; do sleep 2; t=$((t + 2)); done
  kill $pid 2>/dev/null || true
  xcrun simctl terminate "$SIM" com.monti.servecoach 2>/dev/null || true
}

fail=0
run selftest "SCTEST \(OK\|FAILED\)" 120 -selftest
grep "SCTEST" "$OUT/selftest.log" || true
grep -q "SCTEST OK" "$OUT/selftest.log" || { echo "::error::iOS self-tests failed or did not run"; fail=1; }

for clip in $(python3 -c 'import json;print(" ".join(json.load(open("tools/ci-expect.json"))))'); do
  run "$clip" "SCLOG done" 240 -replay "$PWD/../tools/bisect/clips/$clip.mp4"
  python3 - "$clip" "$OUT/$clip.log" <<'P' || fail=1
import json, sys
clip, log = sys.argv[1], sys.argv[2]
want = json.load(open("tools/ci-expect.json"))[clip]
lines = open(log, errors="replace").read().splitlines()
results = [json.loads(l.split(" ", 3)[3]) for l in lines if l.startswith("SCLOG result: #")]
done = [l for l in lines if l.startswith("SCLOG done")]
print(f"{clip}: {done[0] if done else 'no answer'}")
problems = []
if not done: problems.append("the video test never finished")
if len(results) != want["serves"]: problems.append(f"{len(results)} serves read, expected {want['serves']}")
for got, exp in zip(results, want["readings"]):
    s = got.get("serve") or {}
    print(f"  rps {s.get('rps')} phase {s.get('phase')} clear {s.get('clear')} type {s.get('type')}")
    if bool(s.get("clear")) != exp["clear"]: problems.append(f"clear {s.get('clear')}, expected {exp['clear']}")
    if s.get("phase") != exp["phase"]: problems.append(f"phase {s.get('phase')}, expected {exp['phase']}")
    if s.get("rps") is None or abs(s["rps"] - exp["rps"]) > exp.get("tol", 0.3): problems.append(f"rps {s.get('rps')}, expected {exp['rps']}")
for p in problems: print(f"::error::{clip}: {p}")
sys.exit(1 if problems else 0)
P
done
mkdir -p "${LOGS:-ci-logs}" && cp "$OUT"/*.log "${LOGS:-ci-logs}/"
exit $fail
