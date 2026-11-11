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
