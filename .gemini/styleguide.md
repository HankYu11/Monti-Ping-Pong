# Review guide: Monti Ping Pong

The main product is **Serve Coach Live**: a phone at the table films serves at 240 fps, spots each
serve, cuts a capture around the ball and measures its spin with the spin engine
(`experiments/02-spin-meter/web/engine.js`). There is an Android app
(`experiments/04-live-coach/android`) and an iPhone port (`experiments/04-live-coach/ios`) that must
behave the same.

## What matters most in a review

1. **The two phones give the same answer.** The serve tracker, setup check and unread-serve causes
   live in the Android `core` module and are compiled unchanged for iOS (`ios/shared` copies the
   sources at build time). Flag any change that makes iOS and Android diverge: a rule re-implemented
   on one side only, a constant changed in one place, a different rounding (Kotlin `roundToInt` is
   half up; Swift's default `rounded()` is too, but `Int(x)` truncates).
2. **Capture format.** `cap_N.bin` / `cap_N.json` are read by the engine byte for byte: planar Y, U,
   V with no length prefix; big-endian floats in the windowed format; `"windowed":true` only in the
   first header; `full` must state the bytes' real colour range. Changes here need the engine side
   checked too.
3. **Frame numbering.** Live frames are numbered from their timestamps, `round((ts - t0) * fps)`;
   the recording store must use the same timestamps, or a serve's time range is wrong.
4. **Threads.** The camera callback must never block (a blocked callback drops frames at 240 fps).
   `ServeTracker` is used from more than one thread; the Kotlin core's `@Synchronized` does nothing
   on iOS, so Swift callers need their own locks.
5. **Sign conventions in the spin ball.** `SpinMath` works in a y-down, left-handed screen frame and
   uses the transposed matrix on purpose. "Fixing" it makes the ball turn against its own arrow;
   `SpinBallTest` (Android) and `SelfTest.swift` (iOS) guard it. Flag any change there that is not
   covered by those tests.
6. **The player's words.** Text on screen and spoken lines are product copy shared by both apps
   (see `ios/docs/spec-ui.md`). Flag wording that changes on one platform only.
7. **Privacy.** Clips show the player (often a minor). They stay on the phone unless the player
   chooses to share; never upload them, and never log or show the Gemini API key beyond its last
   four characters.

## Less important

- Style and naming: the code favours short names and comments that explain *why*; don't ask for
  long names or docstrings on obvious code.
- Experiments under `experiments/0[13]-*` are research scripts; review them lightly.
