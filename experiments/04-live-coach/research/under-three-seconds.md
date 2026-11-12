# Getting a serve's answer under three seconds

The target is the wait a player has between finishing a serve and hearing the answer: the app's
own `answerMs`, from "Got it" to the spoken reading. It was about 6.4 s when it was first
measured, and cutting and fetching the capture was 4.5 s of that.

## What the budget actually is

Nobody knows, right now, and that has to be fixed before anything is built. The figures above
predate two changes: measuring along the tracker's track (about 3× less engine work on real
captures) and everything since. The engine is not the suspect — a desktop A/B over rendered clips
puts 0.10.1 within about 1% of the engine from before all the spin work — but that says nothing
about where the phone's seconds go.

**No new code is needed to find out.** The report already records, per serve:

| field | what it covers |
|---|---|
| `answerMs` | "Got it" to the answer: the player's whole wait |
| `writeMs` | cutting the capture out of the decoded frames and writing it |
| `fetchMs` | the WebView pulling those frames into JS memory |
| `engineSeconds`, `time` | the measurement itself, with its own breakdown |
| `wallMs` | from handing the engine the capture to its answer |

`answerMs` minus `wallMs` is the time a serve spent queued behind another. `wallMs` minus
`fetchMs` minus the engine's own time is WebView dispatch and whatever else is unaccounted. One
session at home gives all of it.

So: **one session before any of this is built.** Optimising the wrong stage is the easiest
mistake available here, and the instrumentation to avoid it already exists.

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

## Order of work

1. **Measure.** One session at home; read `answerMs`, `writeMs`, `fetchMs`, `engineSeconds` per
   serve. No code.
2. **Confirm the windowed read still agrees** with the whole crop on the current engine, which has
   changed since the format was validated.
3. **Settle the stride** for row brightness.
4. **Build it on the phone**, background from pre-serve frames, off the critical path.
5. **Prove it on the phone before trusting it**: a session that writes both a whole-crop and a
   windowed capture for each serve and measures both, so the two readings can be compared on the
   same ball. The desktop harness does exactly this comparison already; the phone should do it
   once.

Only if that is not enough: serve the capture from memory rather than through a file, and — last,
because it is weeks of work and risks the accuracy that is already proven — move the spin fit out
of the WebView into Kotlin.

## What would break it, and the test that catches it

The flicker correction. At 240 fps under mains lighting the rolling shutter puts strong horizontal
banding in every frame, and the windowed capture reconstructs the frame from a background plus a
row-brightness ratio plus a window. If the row that Kotlin measures and the row the engine applies
are off by one, or the window's offset and the row index disagree, the correction fights the
banding instead of removing it, and the spin reads wrong while everything still looks plausible.

The test is step 5: the same serve, measured both ways, on a phone, under the worst light to hand.
A drift larger than the 0.2% the format was validated at means the row alignment is wrong.
