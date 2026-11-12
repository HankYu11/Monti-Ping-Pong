# Getting a serve's answer under three seconds

The target is the wait a player has between finishing a serve and hearing the answer: the app's
own `answerMs`, from "Got it" to the spoken reading. It was about 6.4 s when it was first
measured, and cutting and fetching the capture was 4.5 s of that — a figure this document was
planned around and which the session below replaces.

## What the budget actually is

Measured, on a Pixel 8 at 0.10.2, one session of six serves (`Session_20261112_154728`).

`answerMs` is `fetchMs` plus the engine plus about 35 ms of dispatch, and `writeMs` sits *inside*
`fetchMs` — the WebView's fetch is almost entirely the wait for the writer, with 41–185 ms of
parsing after it on a tracked serve. Queue time, `answerMs` minus `wallMs`, is 5–20 ms on every
serve: nothing waited behind anything. So there are two stages, and the event log splits the
first one further, into decoding the stored frames and cutting the crop out of them.

| serve | answer | decode | cut | write | parse | engine | track |
|---|---|---|---|---|---|---|---|
| 1 | 3.27 s | 741 | 475 | 425 | 41 | 1538 | yes |
| 2 | 8.26 s | 2059 | 771 | 69 | 520 | 4819 | **refused** |
| 3 | 3.62 s | 1040 | 622 | 87 | 124 | 1716 | yes |
| 4 | 11.69 s | 1112 | 4196 | 70 | 981 | 5298 | **refused** |
| 5 | 3.27 s | 977 | 833 | 85 | 62 | 1274 | yes |
| 6 | 4.05 s | 907 | 780 | 73 | 185 | 2070 | yes |

On the four tracked serves, which average 3.55 s:

| stage | mean | share | does a windowed capture help? |
|---|---|---|---|
| decode the stored frames | 916 ms | 26% | no |
| cut the crop | 678 ms | 19% | **yes** |
| write the file | 168 ms | 5% | **yes** |
| JS parse | 103 ms | 3% | **yes** |
| **engine measure** | **1650 ms** | **46%** | no |
| dispatch | 36 ms | 1% | no |

**The lever this document was written around is 27% of the wait, not most of it.** The 4.5 s of
cutting and fetching quoted at the top predates the track-based crop; on a tracked serve those
stages are now 0.95 s together. Strip 90% of them and serves 1, 3 and 5 land at 2.4, 2.9 and
2.4 s — but serve 6 lands at 3.11 s, still over.

**The engine is the biggest stage.** "The engine was never the prize" is no longer true: at
1.27–2.07 s it is 46% of a tracked serve, and with the capture stages gone it would be two
thirds. The desktop A/B that found it flat to about 1% was asking whether it had got *slower*,
which it hadn't; it never asked whether it was now the largest thing left. It is.

**The two slow serves are a different failure.** Both are the engine refusing the tracker's track
— "the serve's own stretch read unclear", "no track flies like a ball" — and searching for the
flight itself, at 3.5 s and 4.6 s of pure search. Both still had a track good enough to cut
windows from, so windowing does shrink their bytes; it takes them to about 7 s, all of it engine.
For these serves the capture is not the problem at any size.

**There is a cheaper lever that was missed.** The capture rect is consistently about 2.1× taller
and 1.1× wider than the bounding box of the very track it was built from, and the engine then
reads a smaller ROI still — 4–20% of the bytes the phone cut, wrote and parsed:

| serve | capture | track bbox + radius | engine ROI × frames used | captured | read |
|---|---|---|---|---|---|
| 1 | 1024×368 | 832×172 | 844×250 × 60 | 79.1 MB | 19.0 MB |
| 3 | 1256×374 | 1169×172 | 1090×238 × 60 | 101.5 MB | 23.3 MB |
| 5 | 1288×330 | 1210×157 | 1065×190 × 60 | 95.6 MB | 18.2 MB |
| 6 | 1344×424 | 1237×189 | 1195×291 × 55 | 112.8 MB | 28.7 MB |

Tightening the vertical margin is worth about 2× on cut, write and parse for a one-line change,
needs no new format, and carries none of the row-alignment risk below. It should be tried first,
if only to find out what the margin is protecting against.

**What the air test does not tell us.** No serve in this session bounced — there was no table, and
every one is annotated "no bounce seen". Capture length barely moved (132–166 frames), so the
window looks close to fixed, but a real serve's flight is *taller*, and crop height feeds cut,
write and parse directly. Those are the stages windowing fixes, so a table session should be
slower in exactly the places the fix lands. The 2-in-6 track refusal rate is also suspect: "no
track flies like a ball" is what a ball that never bounces and leaves frame early would produce.
It needs one session on a table to know.

## The lever that is already built and not shipped

A windowed capture stores, instead of the whole crop per frame:

- a small square window round the ball, three times its radius, per frame;
- one background frame, the per-pixel median of a spread of frames;
- per frame, the mean brightness of every row of the full crop.

All three are needed. Blanking everything outside the window fails on every real capture, because
the engine's background is a per-pixel median across frames and reads black wherever the window
went, so the whole window counts as foreground. Without the row brightness, the rows outside the
window stop flickering with the mains and the correction inside it goes wrong.

Validated at 0.9.5 on 29 real captures: 27 match the whole crop to a median of 0.2%, at a
fiftieth to a hundredth of the bytes — 24.3 MB down to 1.2 MB. The engine reads the format, and
`core/.../Windows.kt` writes it. **Only the desktop harness has ever called it.** The phone still
cuts, writes, serves and parses the whole crop.

## Two corrections to the obvious plan

The obvious plan — have the phone write windows using the live track, keep 15 full crops to build
the background, compute row brightness in the same pass — has two problems, both raised by Gemini
when asked to pick holes in it.

**The background should not come from the capture.** Building a 15-frame per-pixel median at the
end of a capture puts that work between the serve and the answer, where every millisecond is the
player waiting. It is not as expensive as Gemini claimed — about 420,000 bytes a frame over 15
frames with a 15-element median each is tens of milliseconds, not seconds — but it is on the
critical path for no reason. The camera is on a tripod and the scene is static: the background can
come from frames *before* the serve, from a rolling ring the tracker already sees, cropped once
the flight's rectangle is known, and computed on another thread while the capture writes. A
background without the ball in it is also a better background than one built from frames the ball
is crossing.

**The row brightness probably does not need every pixel.** It is the one part of the windowed
capture that still reads the whole crop — 400×700 over 50 frames is about 14 million reads a
serve, which undoes a good share of what the windows save. Sampling every Nth column should be
nearly free in accuracy, because `rowBright` is a *ratio* to the background's row mean: use the
same columns for both and the sampling bias is common-mode and cancels. That is reasoning, not
measurement, so it is being measured on the rendered clips before it is built — stride 1, 4, 8,
16, 32, against the whole-crop reading.

## What the experiments said

**The windowed read still agrees.** The format was validated at 0.9.5 and the engine has changed
since. Re-run on 13 rendered clips: 13 of 13 within 0.5 rev/s of the whole crop, drift 0.00 on
seven and 0.05 on six. The signed gyro drifts at most 0.46 rev/s and `gyroDirKnown` agrees on all
13. Bytes 7–18× smaller, median 12×.

**The stride is free, and the correction is not optional.** The first run of this tested nothing:
spinsim renders no flicker, so `rowBright` is 1 everywhere and no stride can fail. Rolling-shutter
bands were injected instead — 100 Hz light at 240 fps — applied identically before both reads,
with a control that left the correction out altogether:

| flicker | strides 1, 4, 8, 16, 32 | no correction at all |
|---|---|---|
| 10% | max drift 0.05, 13/13 clear | max 0.05, 13/13 clear |
| 25% | max drift 0.05, 13/13 clear | max 26.15, 11/13 clear |
| 25% + a wall gradient | max drift 0.05, 13/13 clear | max 66.25, 11/13 clear |

So the correction earns its place — leaving it out wrecks two clips in three — and subsampling it
costs nothing up to every 32nd column. The common-mode argument holds.

**The limit of that test, which matters.** Synthetic flicker is perfectly uniform along a row,
which is exactly the assumption the stride rests on, so this cannot contradict it. A real row has
a player moving across part of it, and a mean over sampled columns is pulled by wherever the body
falls while the full-row mean is pulled differently. The guard is to take the **median** of the
sampled columns rather than the mean, which ignores a body covering a minority of them. That is
what `Windows.rowLevel` now does, at every 16th column. It is untested on real footage: the first
session with windows should log stride 1 beside stride 16.

**End to end through the real writer and reader.** The desktop runner cut two rendered serves both
ways and `cap.mjs --pair` measured each: 30.05 against 30.00, and 45.05 against 45.05 — drift 0.05
and 0.00 rev/s, at 20× and 21× fewer bytes (24.3 MB → 1.2 MB). That is Kotlin's `Windows.write`
and the engine's own reader, not a simulation of them.

**What the window buys the engine is small, and that is fine.** Measuring the same pixels both
ways: whole crop median 671 ms, windowed 604 ms, a ratio of 1.13×. These crops are 9–24 MB where
real ones are 24–786 MB, so it is a floor. The engine was never the prize. The prize is the phone
not cutting, writing and serving the whole crop, and no desktop test can measure that.

## Order of work

1. ~~**Measure.**~~ Done: six serves, broken down above. The result moved the rest of this list.
2. **Tighten the capture rect** to the track's own bounding box plus a margin that is argued for
   rather than inherited. About 2× on cut, write and parse; no new format.
3. **Attack the engine's measure**, now the largest stage at 1.65 s on a tracked serve. Nothing
   here has been profiled yet — that is the next measurement, not the next build.
4. **Stop the engine refusing good tracks**, or cap the search when it does. Two serves in six
   cost 3.5 s and 4.6 s of search; nothing in the capture path touches them.
5. **Build the windowed capture on the phone**, background from pre-serve frames, off the critical
   path. Worth 0.95 s on a tracked serve, and more on a real one with a bounce — real, but on its
   own it does not reach three seconds.
6. **Prove it on the phone before trusting it**: a session writing both a whole-crop and a windowed
   capture for each serve and measuring both, so the two readings can be compared on the same ball.

Steps 2 and 5 are the capture; 3 and 4 are the engine, and the measurement says the engine is
where the target is actually won. Serving the capture from memory rather than through a file is
worth 168 ms and is not the answer. Moving the spin fit out of the WebView into Kotlin now has a
real case for the first time — it is step 3's likeliest ending — but it stays last, because it is
weeks of work and risks accuracy that is already proven.

## What would break it, and the test that catches it

The flicker correction. At 240 fps under mains lighting the rolling shutter puts strong horizontal
banding in every frame, and the windowed capture reconstructs the frame from a background plus a
row-brightness ratio plus a window. If the row that Kotlin measures and the row the engine applies
are off by one, or the window's offset and the row index disagree, the correction fights the
banding instead of removing it, and the spin reads wrong while everything still looks plausible.

The test is step 5: the same serve, measured both ways, on a phone, under the worst light to hand.
A drift larger than the 0.2% the format was validated at means the row alignment is wrong.
