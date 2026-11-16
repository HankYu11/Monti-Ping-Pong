# Missed serves, 15 Nov 2026 sessions

**Question (product review):** how many real serves does Serve Coach Live miss?

**Answer for these three sessions: none that left a trace.** None of the 46 skipped clips shows a
serve's flight. Every clip was labelled by looking at its frames. Of the 21 recorded "serves", 18
are real and 3 are bodies. So 18 serves were played that the app could see, it recorded all 18,
and the miss rate among the refusals it clipped is **0 / 18**.

This is not a flat "0 % missed". Some refusals leave no clip (see
[What this cannot see](#what-this-cannot-see)). A serve lost there would not show up here.

Sessions: `live-clips/Session_20261115_214434` (app 0.13.19), `_214545` (0.13.19), `_230448`
(0.13.20). Pixel 8, 1920x1080 at 240 fps, home setup with no table: the player serves across a
white wall.

## Method

- **Frames.** Every `skipped*.mp4` was rendered as a 12-frame grid (frame numbers burned in). The
  clips that might hold a toss were also rendered as 16-frame grids of their second half. Every
  clip was looked at.
- **Frame identity.** Each clip was cut to 64x36 grey thumbnails. Every 4th frame of each skipped
  clip was matched against every frame of the other clips in its session (mean |diff| < 0.6). This
  shows which skipped clip runs straight into a recorded serve, so its toss and that serve are the
  same moment, and which skipped clips are cut from the same seconds.
- **Replay.** Every skipped clip and every serve clip was run through the desktop harness, built
  from `main` at de72ec4 (after PR #27), with `DEBUG=1`.
  - The harness primes its background from frames spread over the whole clip. The phone has only
    the past to go on.
  - On these short clips the replay reproduced the phone's reason on **35 of 46**. The other 11
    end with only `short` / `barely moves`.
  - So the replay numbers below are the harness's, not the phone's.
  - One artefact: 21:45 `skipped2_picture-moving` comes out as a serve when primed (moving
    0.118/0.137, under the 0.2 bar) and as nothing with `NOPRIME=1`. That is the harness's
    background, not something the phone did.
- **Serves.** Each of the 21 serve clips was viewed: whole-frame grids for the doubtful ones, and
  crops around the replayed flight for the rest.

## The 21 recorded serves

| Session | Recorded | Real | Not a serve |
|---|---|---|---|
| 21:44 | 5 | 5 | none |
| 21:45 | 6 | 5 | serve02: a second person walks through carrying clothes |
| 23:04 | 10 | 8 | serve04: the player steps in at the right edge, no ball. serve06: the player raises the racket at the right edge, no ball |
| **Total** | **21** | **18** | **3** |

- The 3 bodies are the ones PR #27's `wanders()` was written against.
  - Replayed on the post-#27 tracker, none of them comes out as a serve. 21:45 serve02 is now
    refused as `slow (a body?)`.
  - All 18 real serves still replay as serves, with ball radius 3.3–4.9 and speed 5.4–13 (median,
    quarter-size px per tracker frame).
- In every one of the 18, the crop around the replayed track shows the ball leaving the paddle and
  crossing the wall.

## Every skipped clip

Labels: **flight** = a real serve's flight (none found). **toss** = a toss whose flight is not in
the clip. **body/arm**, **ball handled** (rolling, picked up, bounced) and **other**.

### 21:44 (5 clips)

| Clip | Phone said | Label | What is in it |
|---|---|---|---|
| skipped1 | upward (toss) | body/arm | Serving stance with the ball held. The arm and paddle move; no ball leaves the hand. Its last 20 frames are serve02's first 20, so **that serve was recorded (serve02)**. |
| skipped2 | too big (a body?) | body/arm | Empty room. The player walks in from the right edge (frames 160–255). The pose check also refused a "walking" at the same moment (79.4 s). |
| skipped3 | upward (toss) | toss | The toss: the ball rises from the hand (replay: 776,776 → 752,548, upward). Its last frames are serve05's first, so **the serve was recorded (serve05)**. |
| skipped4 | toss only | body/arm | The player walks out of the picture to the right with the paddle. |
| skipped5 | toss only | other | Empty room. Nothing visible moves in 16 frames across the clip, and the replay finds no track longer than "short". |

### 21:45 (30 clips, the session's cap)

| Clip | Phone said | Label | What is in it |
|---|---|---|---|
| skipped1 | slow (a body?) | body/arm | The player walks across, laughing, paddle low, no ball. |
| skipped2 | picture moving | body/arm | The player walks and laughs; the picture shifts. |
| skipped3 | slow (a body?) | body/arm | The player walks to the right. |
| skipped4–30 (27 clips) | upward (toss) | other | The player walks to the phone and picks it up; the camera swings down onto the tablecloth. All 27 are cut from the same ~1.5 s (frame identity: each shares 100–190 frames with the others). All 27 were viewed (4 frames each). Replay: 5–48 "upward" tracks per clip, from the moving picture. |

### 23:04 (11 clips)

| Clip | Phone said | Label | What is in it |
|---|---|---|---|
| skipped1 | slow (a body?) | ball handled | The player bends at the right edge, picks the ball up, and straightens with the paddle. |
| skipped2 | slow (a body?) | body/arm | The player walks back into position. Its end overlaps skipped3. |
| skipped3 | upward (toss) | body/arm | The player stands facing the camera, ball in hand, paddle low. There is no toss in any of 28 frames viewed. The next recorded serve (serve02) comes 6.6 s later, so it is a later serve. |
| skipped4 | slow (a body?) | body/arm | The player walks with the paddle (overlaps skipped5). |
| skipped5 | slow (a body?) | body/arm | Walking. |
| skipped6 | slow (a body?) | body/arm | The player walks out of the picture to the right. |
| skipped7 | upward (toss) | body/arm | Empty room, then the player steps in at the right edge raising the paddle. The same movement was then recorded as **serve06, which is not a serve**. |
| skipped8 | slow (a body?) | body/arm | Walking with the paddle. |
| skipped9 | upward (toss) | ball handled | The ball is bounced on the paddle and falls. Replay: one falling track and one rising track. |
| skipped10 | upward (toss) | body/arm | The player handles the ball and raises the paddle. The replayed "upward" track is at the hand, median radius 25 px (the ball is about 16). serve09 comes 3.9 s later and does not overlap. |
| skipped11 | upward (toss) | body/arm | Hand and ball before the toss. serve10 runs on from it, so **the serve was recorded (serve10)**. |

### Tally

| Label | Clips |
|---|---|
| Real serve flight | **0** |
| Toss, flight not in the clip | 1 (its serve was recorded) |
| Body/arm | 15 |
| Ball handled (picked up, bounced) | 2 |
| Other (phone picked up: 27; empty picture: 1) | 28 |
| **Total** | **46** |

## Steps 2 and 3: replay of the missed serves, and rule changes

There is no missed serve to replay, so there is no refusal behind a miss, and no rule change is
proposed for one.

The closest calls are the three clips that run straight into a recorded serve (21:44 skipped1 and
skipped3, 23:04 skipped11). In each, `upward (toss)` refused the toss or the arm before the hit,
and the hit itself was then recorded. The rule did what its comment says it should. The sheet
[closest-calls.jpg](missed-serves-2026-11-15/closest-calls.jpg) shows each of those clips beside
the serve it runs into, plus the ball bounced on the paddle (23:04 skipped9).

## What this cannot see

The skipped clips only cover some refusals:

- **Clipped:** `upward (toss)` on a single track, and a whole serve refused by `toss only` or by
  `notABall` (`slow`, `too big`, `picture moving`, `wandering`).
- **Never clipped:** a track refused in `judged()` for any other reason. If a serve's only flight
  track were refused there, the serve would vanish without a clip. The sessions' `rejected` tallies
  (from `report.json`, `watching.rejected`):

  | Session | short | barely moves | short flight | tiny | straight drop | zigzag |
  |---|---|---|---|---|---|---|
  | 21:44 | 1,384 | 1,129 | 82 | 11 | – | – |
  | 21:45 | 2,375 | 1,670 | 183 | 44 | – | – |
  | 23:04 | 2,573 | 2,065 | 197 | 30 | 2 | 2 |

  Most of these are the room's noise (the longest refused tracks barely move: 120 points, 11 px).
  Without a recording of the whole session, they cannot be checked against serves.
- **notABall refusals beyond the clips:** 21:45 counted 4 `slow (a body?)` but kept 2 clips;
  23:04 counted 7 but kept 6.
- **Pose refusals ("the player was walking"):** 21:44 at 79.4 s and 21:45 at 144.2 s, with no
  clip. The 21:44 one comes 0.4 s after skipped2, the player walking in. The 21:45 one comes 0.9 s
  after serve04 was saved.
- **A serve the tracker never followed at all** leaves nothing.
- **Note:** 21:45's `report.json` repeats 21:44's events as its first 87 s; they were counted once.

So the evidence supports "no real serve among the 46 refusals the app kept". It does not support
"the app misses no serves".

## Follow-ups (not misses, found on the way)

1. **One pick-up of the phone filled the clip cap.** 21:45 saved 27 `upward (toss)` clips of the
   same ~1.5 s while the phone was being picked up. That reached the 30-clip limit, so any later
   refusal that session would have gone unclipped. Two possible fixes:
   - don't keep a skipped clip that overlaps the last one kept;
   - don't clip `upward` while much of the picture is moving.
2. **3 of 21 recorded serves were bodies** (all on the pre-#27 app). The post-#27 replay refuses all
   three and keeps all 18 real serves. These sessions are the data #27 was tuned on, so this
   confirms #27 on its own data but is not an independent test.
3. **To measure misses that leave no clip**, a session would need either a low-rate full recording
   or clips of long tracks refused as `short flight` / `tiny`.
