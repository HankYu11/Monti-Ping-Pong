# Handover: why ServeCoach stopped finding serves

## RESULT (2026-11-15, on the Pixel 8) -- cause found and fixed in 0.13.19

**Cause:** `GlTap.kt` created `fboTex` (the small picture's texture) without setting a minifying
filter. GL's default is `NEAREST_MIPMAP_LINEAR`; with no mipmaps that texture is *incomplete*, and the
Pixel 8's Mali samples an incomplete texture as black. Nothing sampled `fboTex` before 0.13.10; the
mask passes (0.13.10, `fe3f1cd`) do, and they write their RGB into the picture the finders read back.
So from 0.13.10 on every finder saw the same black frame -> the "empty or 100 % moving" mask, the
blob measured identically for 11 frames, and 0 serves. `maskcheck.py` passed because moderngl sets
NEAREST on its own texture. Hypothesis 1 was right about *where*, wrong about *why* (not a feedback
loop -- an incomplete texture); 0.13.18's unbind/invalidate did not help, which the phone confirmed.

**Fix:** `39e0bcd` -- `plain2D(fboTex, qw, qh)` like every other texture (NEAREST, CLAMP_TO_EDGE).

**Evidence -- release builds (as shipped), phone replay of `clips/s1.mp4` + `clips/s2.mp4`,
counting only runs with the screen on and unlocked and finder time under 10 ms:**

| build | serves found | exact repeats among masked pictures |
|---|---|---|
| 0.12.8 (`b3b0a71`) | 7 / 7 | (no mask passes) |
| 0.13.9 (`92b2f87`) | 4 / 4 | (no mask passes) |
| 0.13.18 + the new report lines, without the fix | **0 / 6** | 213-236 of 214-237 (all but the first) |
| with the fix | **8 / 8** | **0** in every run |

Finder time 4.2-5.2 ms, 0 pictures skipped, for every build.

**Things that looked like regressions but are not:**
- *Debug* (debuggable) builds run the finders 8-10x slower (30-60 ms), so pictures get dropped and
  `s2` misses its serve now and then -- **0.12.8 debug does this too**. Judge by release builds.
- Overnight runs after ~03:15 are void: the runner never woke the screen, so the app ran behind the
  lock screen (finder time 40-2700 ms) or never started. Six more fixed runs found their serve even
  then, but they are not counted. `runguard.sh` (outside the repo) now wakes the screen and refuses to
  run behind the keyguard or while another app is in use.
- Feeding the raw stock videos is wrong: they are stored stretched to 30 fps, and the chip numbers
  frames by time, so each frame looks 8 apart ("missed" ~7x the frame count). Re-time first --
  `cut.sh` does, or multiply the video track's `mdhd` timescale by 8 (same frames, no re-encode).
- 0.13.18 *without* the fix found a serve on the full re-timed video: its repeat detector switched
  the mask passes off ("239 of 240 pictures were exact repeats") and the finders recovered. The short
  clips end before it trips.

**Full stock videos (re-timed to 240 fps), release builds, 2 runs each:**

| build | `..161527357` (2 serves) | `..161337155` (2 by eye + 1 the harness finds) | repeats |
|---|---|---|---|
| 0.12.8 | 2, 2 | 3, 3 | -- |
| with the fix | 2, 2 | 3, 3 | 0 |

Same as the desktop harness. These test builds had `android:largeHeap` added, because replay
(`FileFrames`) holds the WHOLE video in the heap ("a serve clip is a few MB"): 127-178 MB of a 256 MB
heap. Without it every version, 0.12.8 included, dies of `OutOfMemoryError` around the first
capture -- 0.13 a little sooner (its room-background work). Live, `ClipRecorder` keeps only the last
5 s, so live never hit it -- but TEST VIDEO did, on any long video. **Fixed in 0.13.21:** the video's
frames go to a file in the app's cache and are read back as needed (`SpilledFrame`); both stock
videos now replay at the normal heap, 2 and 3 serves, twice each, where `main` at 0.13.20 died.

**Still open:**
- ~~Not verified in a live session.~~ **Verified live (2026-11-15, two sessions on 0.13.19):** serves
  found again; 0 of 6,280 and 0 of 7,828 masked pictures repeated; finder 4.6 / 7.9 ms; 0 dropped,
  0 skipped. One reading was wrong (7,086 rpm), a spin-engine matter, not this one -- see below.
- The chip's own alpha mask is never trusted (`maskTrust` -1 in every run: a few pixels disagree), so
  the finders use the CPU mask and the GPU mask passes are wasted work. Safe, but worth a look.
- Release testing needs the clip in `/data/local/tmp` (no `run-as` on a non-debuggable app); `run.sh`
  only does debug builds.


Everything referred to here is on `main`. (The work was done on `claude/zealous-goldberg-qwboov`, merged
into `main` and deleted; the commits named here are all on `main`.)

## The problem in one paragraph

0.12.8 found serves on the Pixel 8. 0.13.0 started missing some ("stays in Ready after the
serve"). From 0.13.10 on, live sessions find **no serves at all**. Several attempted fixes since
then (0.13.11–0.13.18) did not bring them back, and 0.13.18 has never been run on the phone.

**If you just need a working app today:** `experiments/04-live-coach/release/serve-coach-live-0.12.8.apk`
is the last known-good build, committed at `b3b0a71`.

## What is established by running it (not by reasoning)

| Question | Answer | How it was checked |
|---|---|---|
| Are serves in the footage? | Yes: **2** in `PXL_20261114_161527357.mp4` (near frames 900 and 2150), **2** in `PXL_20261114_161337155.mp4` (near 980 and 2200). The harness finds a third in the second clip, at frame 1488, that I didn't spot by eye — unconfirmed | Counted by eye on contact sheets |
| Is the camera too far away / ball too small? | **No.** Ball radius 5.4–6.0 in the small picture, the same as in clips where serves are found | Desktop harness on the stock clip |
| Did the tracker or detector break? | **No. Identical serves at every version from 0.12.8 to 0.13.18** (table below) | Each version's own core + own desktop harness, built from its commit |
| Does the core find serves when fed like the phone? | Yes: background only from the past (`NOPRIME=1`) and the GPU's box downsample (`GPU=1`): 2–3 serves, 3.6–3.8 candidates/frame | Desktop harness, current code |

Core bisect, both stock clips, every version where the tracker or detector changed:

| version | commit | clip a | clip b |
|---|---|---|---|
| 0.12.8 | `b3b0a71` | 2 (848, 2090) | 3 (846, 1488, 2086) |
| 0.13.0 ×4 | `ee5b75f` `eff8643` `847aa73` `55a37b1` | same | same |
| 0.13.2 / 0.13.4 / 0.13.6 / 0.13.7 | `ac706d1` `9d000c6` `cef133d` `187bd48` | same | same |
| 0.13.9 ×2 | `92b2f87` `fe3f1cd` | same | same |
| 0.13.18 | `e6061c2` | same | same |

**So the fault is in code only the phone runs** — `LivePipeline.kt`, `GlTap.kt` (the GPU path),
`HighSpeedCamera.kt`, `MainActivity.kt` — or in load (frames dropped when the phone falls behind),
or in the phone's GPU (Mali-G715). None of those can be tested off the phone.

## What the phone's own reports showed

| builds | finder time | frames lost | serves | what the mask looked like |
|---|---|---|---|---|
| 0.13.7 | 45 ms | 18% | 5 | (not measured) |
| 0.13.10–0.13.14 (mask cut on the GPU) | 3.5–5.7 ms | 0–5% | **0** | empty on ~96% of frames, 100% "moving" on the rest, nothing in between |
| 0.13.15–0.13.17 (mask cut on the CPU) | 40–44 ms | 22–30% | **0** | same empty-or-full pattern; on checked frames both masks said all 129,600 pixels moving |

Also seen: a blob measured **identically to 0.1 px for 11 frames in a row** (0.13.17). A live
camera never produces the same frame twice, so the picture reaching the finders may be repeating.

## Hypotheses — none of these is proven

1. **The GPU mask passes (0.13.10, `fe3f1cd`) freeze the picture on Mali.** Before 0.13.10 nothing
   sampled the texture the camera frame is drawn into; the mask passes sample it and never unbind it,
   so the next frame draws into a texture still bound for sampling. Mali may drop that draw and,
   because the framebuffer wasn't invalidated, write the previous picture back. Fits: onset at
   0.13.10, empty-or-full mask, identical blob readings, and why switching to the CPU mask (0.13.15)
   didn't help — the CPU reads the same picture. 0.13.18 unbinds, invalidates, and adds a detector
   (`repeats` in the report) that switches the passes off if pictures repeat. **Untested.**
2. **0.13.0's room-background thread adds load.** `ee5b75f` decodes keyframes on a thread every 4 s
   to build a background for captures. On a phone near its limit that can cause clumped frame loss,
   and a track dies after 3 unseen frames. Fits 0.13.0's first misses and "processing got slower".
   0.13.7 made it run every 30 s once built.
3. Less likely: 0.13.17's camera locks or colour compensation — they came after the failure.

## The bisect on the phone — the next step

`tools/bisect/` builds any version exactly as it was at a commit, installs it on the phone, runs
its own **TEST VIDEO** on a committed clip, and prints what the app said. The only edits are how the
clip is handed in and mirroring the app's log to logcat. Full usage: `tools/bisect/README.md`.

Needs on your computer: `adb` seeing the phone, `ANDROID_HOME`, `gradle` on PATH (no wrapper).
Warning: `run.sh` uninstalls ServeCoach before each install, which removes its private app data
(reports and clips in `Download/ServeCoach` are kept).

```bash
git fetch origin main
git worktree add ../servecoach-bisect origin/main
cd ../servecoach-bisect/experiments/04-live-coach/tools/bisect
./run.sh b3b0a71 v0.12.8  clips/s1.mp4 clips/s2.mp4
./run.sh ee5b75f v0.13.0  clips/s1.mp4 clips/s2.mp4
./run.sh 187bd48 v0.13.7  clips/s1.mp4 clips/s2.mp4
./run.sh 92b2f87 v0.13.9  clips/s1.mp4 clips/s2.mp4
./run.sh 99bcc3d v0.13.10 clips/s1.mp4 clips/s2.mp4
./run.sh e6061c2 v0.13.18 clips/s1.mp4 clips/s2.mp4
```

Each clip has one serve. **0.12.8 must print `replay: done: 1 serve(s) spotted` for both clips**;
if it doesn't, the setup is wrong and nothing after it means anything. Logs: `/tmp/servecoach-bisect/out/`.

### Reading the result

| what you see | what it means | what to do |
|---|---|---|
| 0.13.9 finds both, 0.13.10 finds none | Hypothesis 1: the GPU mask passes | If 0.13.18 finds both, keep it. If not, remove the mask passes: drop `maskShaders = mks` from the `GlTap(...)` calls in `MainActivity.kt`. The finders then see the picture 0.13.9 did; 0.13.17's camera locks and colour compensation stay |
| 0.12.8 finds both, 0.13.0 does not | Something in 0.13.0 | Bisect `ee5b75f` `eff8643` `ae0a454` `847aa73` `55a37b1` `348aee3` |
| Every version finds both in replay | The fault needs live load or the live camera | Live sessions with 0.12.8 vs 0.13.x; compare `skipped` and `findMs` in the reports |
| 0.12.8 finds none | Setup problem | Check the log for `replay: chip:` and `error` lines |

Replay on the phone runs the real GPU path and the real finder queue, so it *does* exercise
hypothesis 1 and some load. It does not run the camera, exposure locks, or the encoder.

## Things I got wrong, so you don't rely on them

- I said the detector and tracker were unchanged since 0.12. Wrong — I diffed against 0.13.6. They
  did change, but the bisect above shows the changes don't lose serves.
- I said the camera was too far away. Wrong — the ball is full size in your stock footage.
- I blamed, then cleared, the GPU's mask arithmetic. It is bit-exact; hypothesis 1 is about the GPU
  *state* around it, which is a different thing and still unproven.
- My on-device mask check (0.13.10–0.13.16) could not catch a frozen picture: both masks were cut
  from the same pixels and agreed perfectly.

## Testing without a phone (for reference)

- `experiments/04-live-coach/tools/maskcheck.py` — runs the app's own shaders on llvmpipe against the
  CPU mask, all four image turns. Passes: 0 of 129,600 pixels differ.
- Desktop harness: `experiments/04-live-coach/android/desktop` (`PAR=1`, `GPU=1`, `NOPRIME=1`, `LOSE=n`).
- Emulator: `EMU=1 NOWEB=1 RIG=1 ./run.sh ...`. Its WebView crashes at start-up under software
  rendering (hence `NOWEB`), and without `RIG` the chip replay drops frames so badly that 0.12.8 finds
  nothing. With `RIG` the replay is lossless, so it tests detection only — not load, not a real GPU.
  The `RIG` run was not completed; it last hit the 3 s chip setup limit, which `RIG` now raises.

## Follow-up seen live: a 7,086 rpm reading (spin engine, not the finder)

`Session_20261115_214545/serve04`: half-marked ball, 118.1 +/- 1.95 rps, fit 0.535, passed `isClear`
(score >= 0.5, lead 0.29, 23.6 turns). Measured from the clip (ball-interior brightness over the 49
clean frames): **56.75 rps, about 3,400 rpm**, 68 % of the swing; nothing else above 8 %. The wall
beside the ball alternates every frame (180/188/180/187...): 60 Hz mains, lights at 120 Hz, filmed at
238.25 fps while the engine assumes 240 -- that folds to ~119 rps, inside the reading's +/-. Not the
2x harmonic (113.5, outside it). The segment also ran 10 frames past the ball (frames 66-75 are the
player's arm). Not fixed: the gate has no notion that a rate at fps/2 is where flicker lands.
