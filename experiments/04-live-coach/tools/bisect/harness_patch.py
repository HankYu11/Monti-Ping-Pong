#!/usr/bin/env python3
"""
The edits applied on top of every version under test, and nothing else.

Always:
  * A video Uri on the launch intent is handed to the app's own TEST VIDEO handler
    (onActivityResult, request 7), with the chip path forced on -- replay only goes through
    the graphics chip when windowRingOn is set, and the chip is how the phone watches live.
  * Every event the app logs is mirrored to logcat under the tag SCBISECT.

Only when asked, for running on an emulator rather than a phone:
  EMU=1    x86_64 added to the ABIs, so the build installs on an x86_64 emulator.
  NOWEB=1  the WebView is never created. It only measures spin after a serve has been spotted,
           so it plays no part in finding one -- and an emulator's WebView can crash at start-up
           under software rendering.
  RIG=1    the replay made lossless at any speed: it waits up to 2 minutes (not 200 ms) for the
           chip to take each frame, the finders' queue holds every frame, the read-back has 64
           buffers, and the chip may take 2 minutes to set up. A software emulator runs ~1000x
           slower than a phone and drops frames everywhere without this; it made 0.12.8, the
           known-good version, find nothing. It changes how fast the machine must be, not how
           serves are found -- and it means frame loss under load cannot be tested with it on.

usage: harness_patch.py <android project dir>
"""
import re, sys, os

root = sys.argv[1]
on = lambda k: bool(os.environ.get(k))
src = lambda f: os.path.join(root, "app/src/main/java/com/monti/servecoach", f)

def edit(path, pairs):
    s = open(path).read()
    for old, new, what in pairs:
        n = s.count(old)
        assert n == 1, f"{what}: anchor found {n} times in {os.path.basename(path)}"
        s = s.replace(old, new, 1)
    open(path, "w").write(s)

# --- always -------------------------------------------------------------------------------
a = open(src("MainActivity.kt")).read()
anchor = "        super.onCreate(savedInstanceState)\n"
assert a.count(anchor) == 1, "onCreate anchor"
a = a.replace(anchor, anchor + (
    "        intent?.data?.let { u -> android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({\n"
    "            windowRingOn = true\n"
    "            android.util.Log.i(\"SCBISECT\", \"harness: replaying $u\")\n"
    "            onActivityResult(7, RESULT_OK, Intent().setData(u))\n"
    "        }, 6000) }\n"), 1)
m = re.search(r"    private fun log\(kind: String, text: String\) \{\n", a)
assert m, "log() anchor"
a = a[:m.end()] + "        android.util.Log.i(\"SCBISECT\", \"$kind: $text\")\n" + a[m.end():]
open(src("MainActivity.kt"), "w").write(a)

# --- emulator only ------------------------------------------------------------------------
if on("EMU"):
    edit(os.path.join(root, "app/build.gradle.kts"), [
        ('abiFilters += listOf("arm64-v8a")', 'abiFilters += listOf("arm64-v8a", "x86_64")', "abiFilters")])

if on("NOWEB"):
    edit(src("MainActivity.kt"), [
        ("    private fun setupEngine() {\n",
         "    private fun setupEngine() {\n        if (!::web.isInitialized) return\n", "setupEngine"),
        ("        web = WebView(this)\n"
         "        screen.root.addView(web, FrameLayout.LayoutParams(2, 2, Gravity.BOTTOM or Gravity.START))\n",
         "", "WebView construction"),
        ('        web.evaluateJavascript("analyseCapture(',
         '        if (::web.isInitialized) web.evaluateJavascript("analyseCapture(', "evaluateJavascript")])

if on("RIG"):
    edit(src("MainActivity.kt"), [
        ("paced.tryAcquire(1, 200, TimeUnit.MILLISECONDS)",
         "paced.tryAcquire(1, 120000, TimeUnit.MILLISECONDS)", "replay pacing")])
    edit(src("LivePipeline.kt"), [
        ("private val jobs = LinkedBlockingQueue<Job>(if (blocking) 16 else 24)",
         "private val jobs = LinkedBlockingQueue<Job>(if (blocking) 100000 else 24)", "jobs queue")])
    edit(src("GlTap.kt"), [
        ("    private val pbo = IntArray(8)\n", "    private val pbo = IntArray(64)\n", "read-back buffers"),
        ("done.await(3, java.util.concurrent.TimeUnit.SECONDS)",
         "done.await(120, java.util.concurrent.TimeUnit.SECONDS)", "chip setup limit")])

print("patched", root, "|", " ".join(k for k in ("EMU", "NOWEB", "RIG") if on(k)) or "phone (no extras)")
