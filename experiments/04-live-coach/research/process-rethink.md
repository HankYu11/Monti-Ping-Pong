# The wait is the product problem (Nov 2026)

> "Users were mostly waiting during the practice session. If we don't improve the process time,
> it's really hard to sell our painted balls because they only need one ball."

That sentence reframes the speed work. The target was never latency. It is **how many serves in
a row the app can take**, because that is what decides whether a player needs one marked ball or
a basket of them.

## What the app does today, measured

The session of 10 Nov, 0.9.4, on a Pixel 8: **11 serves seen, 1 ignored while measuring, 10
read.** Per serve:

| | mean | worst |
|---|---|---|
| cutting the capture | 2.0 s | 3.1 s |
| fetching it to the engine | 2.5 s | 4.1 s |
| the engine (search + measure) | 3.9 s | 5.9 s |
| **answer, end to end** | **6.4 s** | **9.3 s** |

One serve at a time, and serves arriving during a measurement are dropped by design (0.7.1's
`ignoredWhileMeasuring`, 0.9.2's "only the latest serve is spoken"). That was a fair call when a
serve cost 6.4 s. It also means the practice loop is: serve, wait, fetch the ball, serve. **One
ball is enough.**

A multiball drill is a serve every two or three seconds. At 6.4 s serial, most of them are never
measured.

## Where the time actually is

The engine work of 0.9.5 removes the ball search, which is 2.8 s of the 6.4. Everything else —
**4.5 s of cutting and fetching** — is untouched, and it is now the larger half.

The reason is the shape of a capture. Serve 3's was 166 frames of a 1696x572 crop: the box that
contains the whole flight, which is mostly empty table. About 160 MB moved to measure a ball that
is 30 px across. Cropping a small box per frame around the tracker's own ball — 96x96 is ample —
is about **2 MB for the same serve**, better than 98% less. Cutting and fetching both collapse,
and the 600 MB cliff that 0.6.9 holds off by trimming disappears with them.

## What to change, in order

**1. Crop per frame, not one box round the whole flight.** The largest single win, and it needs
the phone: Kotlin and the graphics chip. Expect cutting and fetching to go from 4.5 s to a few
hundred milliseconds.

**2. Queue serves instead of dropping them.** Only affordable once a capture is 2 MB rather than
160. The capture is cheap and the measurement is the slow part, so take every serve and let the
answers arrive behind the player.

**3. Separate the acknowledgement from the reading.** The player does not need the number in two
seconds; they need to know the serve registered. A tick at contact, straight from the tracker
with no engine, tells them that instantly. The number follows when it is ready, and is spoken
only if they have paused.

**4. Make the session the coaching surface, not the serve.** The most useful thing in the 10 Nov
data is not in any single serve: **of nine readable serves, three were topspin and only two were
side-backspin, while the player was practising a backspin serve.** That only exists across
serves. During a burst the app should be quiet and quick; after it, it should show the picture.

## How we would know it worked

Not latency. **A multiball burst: 20 serves at a three-second cadence, counting how many get
measured.** Today that is roughly 6 of 20. The target is 20 of 20, and that is the number that
decides whether a basket of marked balls is worth selling.

Latency matters only in that the answer should arrive before the player has stopped caring —
which is at the end of the burst, not two seconds after each serve.

---

## The capture can be windows, and what it costs

Tested on all 29 captures either phone has produced (the 21 club and home ones, plus the 10 of
the field session, less a truncated file and one non-serve). Each kept its rect, frame count and
geometry; everything outside a square of k times the ball's radius round the tracker's ball was
replaced, and the engine measured it with the track on.

**A window alone is not enough.** Three things have to travel with it:

| | why |
|---|---|
| windows round the pick **and the near tracks** | N4's measured stretch sits on a near track; with windows round the pick only it reads nothing |
| one background frame — the per-pixel median of ~15 frames | blanking the rest outright fails on **every** capture: the background median reads black wherever the window went, the whole window counts as foreground, and there is no ball to separate out. A single frame-0 background fails where the player moves |
| each frame's row brightness, one number a row | without it the rows outside the window stop flickering with the light, and the flicker correction inside the window goes wrong — V3 and T2 read nothing at 8r and 6r |

With all three, **27 of 29 match the whole crop to a median of 0.2%**:

| window | median error | worst | payload |
|---|---|---|---|
| 4r | 0.11% | 2.6% | 3.0 MB |
| 3r | 0.23% | 2.6% | 1.7 MB |
| 2r | 0.28% | **95.8%** — one serve doubles | 0.8 MB |

Full captures run 30–786 MB, median 167. Serve 3 is **242 MB whole, 1.3 MB as windows at 3r**.
3r is the smallest to trust; 4r for margin.

## What windows cannot do: the fallback search

The two that don't match are the two that depend on the engine searching for itself, and both
fail the same way — the search can only see pixels some track pointed at.

- **M6** reads 23.4 instead of 28.5 at every window size. Its track is refused, as designed, and
  the search then wants frames 52–59, which no track covers, so it finds only the after-bounce
  stretch. The guard added for M6 works only if the search sees the whole picture.
- **The net ball** is worse. At 4r and 3r it reads "unclear"; at 2r the search splits the drop
  into paddle and bounce, the net rule's "no bounce seen" condition fails, and out comes a clear
  41.3 rev/s. That is exactly the bug the net rule was written to stop, back again.

So every refusal — "only after the bounce", "unclear", "no track flies like a ball", "measured to
nothing" — still needs the whole picture. **Windows for the common case, full frames on demand.**
In the field session that is 7 serves of 10 on windows and 3 that ask for more; over all 29 it is
24 and 5.

The engine side of this is built: a capture carrying `windows`, `winW`/`winH`, `background` and
`rowBright` is put back together in `windowsInto` before anything else sees it, so the rest of the
engine is unchanged. `test/windows.mjs` builds both forms from one clip and measures both.

What is left is on the phone: write windows instead of the whole crop, compute the background
frame and the row brightness once per capture, and keep the encoded frames long enough to cut a
full capture when the engine asks for one.
