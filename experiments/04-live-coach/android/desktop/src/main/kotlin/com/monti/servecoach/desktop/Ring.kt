package com.monti.servecoach.desktop

import com.monti.servecoach.core.Capture
import com.monti.servecoach.core.Plane
import com.monti.servecoach.core.Predict
import com.monti.servecoach.core.SceneBackground
import com.monti.servecoach.core.ServeTracker
import com.monti.servecoach.core.WindowRing
import com.monti.servecoach.core.Windows
import com.monti.servecoach.core.Yuv

/**
 * The whole of the live window ring, off a phone.
 *
 * On the phone the window is cut on the graphics chip from the camera's own frame, placed where
 * the live track says the ball will be, and a capture is built from what the ring held. None of
 * that can be run here -- but all of it except the shader itself can be *reproduced* here, on a
 * clip the phone recorded: the same tracker, the same Predict, the same WindowRing, the same
 * Windows.Builder, with the window and the row sample cut by the processor from the decoded
 * frame instead of by the chip from the camera's.
 *
 * What that leaves untested is the shader's arithmetic and the chip's timing. What it tests is
 * everything else, and everything else is where the first phone session's three faults were.
 *
 * It also answers the one number the phone could not: how far behind the live track really runs.
 * On the phone the delay is whatever the tracker, the finders and the linker add up to; here it
 * is a dial ([lag], in tracker frames), so the margin a window needs can be read off rather than
 * guessed at.
 */
class RingSim(
    private val frameW: Int, private val frameH: Int,
    private val step: Int,
    /** tracker frames the live track runs behind the frame being cut */
    private val lag: Int,
    /** pixels of margin round the ball's box, for wherever the prediction misses */
    private val margin: Int,
    private val rowStep: Int,
    capacity: Int,
) {
    /**
     * Tracker pixels to picture pixels. The tracker works on a QUARTER of the picture each way,
     * always, so this is 4 -- it has nothing to do with [step], which is how many camera frames
     * go by between the ones it looks at. It was written `step * 2`, which is 4 only when step
     * is 2, so running this harness at any other step silently doubled every window's position:
     * at STEP=4 the windows came out a median of 493 px off the ball and the ring read as
     * useless, which is a thing about this file and not about the ring.
     */
    private val QUARTER = 4f

    val ring = WindowRing(capacity)
    var cut = 0; private set
    var placed = 0; private set

    /** what the tracker had published, by the tracker frame it was published at */
    private val history = ArrayDeque<Pair<Long, List<FloatArray>>>()
    private var planSide = 0

    /** the same rule the app uses, from core, so the two cannot drift */
    private fun sideFor(r: Float): Int = com.monti.servecoach.core.ringSide(r, margin)

    private val GRACE = (System.getenv("RINGGRACE") ?: "10").toInt()
    private val PCT = (System.getenv("RINGPCT") ?: "50").toInt()
    var sideUsed = 0; private set
    private val pick = System.getenv("RINGPICK") ?: "gated"
    private val minSpeed = (System.getenv("RINGMINSPEED") ?: "2").toFloat()

    /** the tracker's thread: the track most likely to be the ball, published for the chip */
    fun seen(tr: ServeTracker, index: Long) {
        var best: List<FloatArray>? = null
        var bestScore = 0f
        for (t in tr.liveTracks) {
            val s: Float
            if (pick == "score") {
                val a = t[0]; val b = t[t.size - 1]
                val disp = Math.hypot((b[1] - a[1]).toDouble(), (b[2] - a[2]).toDouble()).toFloat()
                if (disp < 8f) continue
                s = t.size * disp
            } else if (pick == "gated") {
                // fast enough to be a ball, then the longest of those
                val k = t.size
                val a0 = t[maxOf(0, k - 4)]; val b0 = t[k - 1]
                val df = maxOf(1f, b0[0] - a0[0])
                val v = (Math.hypot((b0[1] - a0[1]).toDouble(), (b0[2] - a0[2]).toDouble()) / df).toFloat()
                if (v < minSpeed) continue
                val a = t[0]; val b = t[t.size - 1]
                s = t.size * Math.hypot((b[1] - a[1]).toDouble(), (b[2] - a[2]).toDouble()).toFloat()
            } else {
                // how fast it is going NOW, over its last few points. A served ball is the
                // fastest thing in the picture by a long way; length times travel, which is what
                // the grouping scores a FINISHED track by, picks an arm that has been alive for
                // a hundred frames over a flight that is ten frames old -- which is exactly the
                // first part of the flight the window is wanted for.
                val k = t.size
                val a = t[maxOf(0, k - 4)]; val b = t[k - 1]
                val df = maxOf(1f, b[0] - a[0])
                s = (Math.hypot((b[1] - a[1]).toDouble(), (b[2] - a[2]).toDouble()) / df).toFloat()
                if (s < minSpeed) continue
            }
            if (s > bestScore) { bestScore = s; best = t }
        }
        if (best != null) {
            history.addLast(index to best)
            while (history.size > 64) history.removeFirst()
        }
    }

    /** the frames' thread: this frame's window, where the track as it stood [lag] frames ago says */
    fun cut(f: Yuv, frameNo: Long) {
        cut++
        val want = frameNo / step - lag
        var track: List<FloatArray>? = null
        for ((at, t) in history) if (at <= want) track = t else break
        val t = track ?: return
        val rs = t.map { it[3] }.sorted()
        // which radius of the live track the window is sized from. The capture's own window is
        // three times the MEDIAN radius over every track it covers, measured when the flight is
        // over; the live median, over a track that is still growing, reads smaller.
        val pick = ((rs.size - 1) * PCT / 100).coerceIn(0, rs.size - 1)
        val wantSide = sideFor(rs[pick] * QUARTER)
        val side = if (planSide > 0 && wantSide <= planSide && wantSide > planSide * 6 / 10) planSide else wantSide
        planSide = side; sideUsed = side
        val p = Predict.at(t, frameNo.toFloat() / step, lag = 0f) ?: return
        val scale = QUARTER
        val x = Math.round(p[0] * scale - side / 2f).coerceIn(0, maxOf(0, frameW - side)) and 1.inv()
        val y = Math.round(p[1] * scale - side / 2f).coerceIn(0, maxOf(0, frameH - side)) and 1.inv()
        // the chip's three draws, on the processor: the window in the capture's own layout, and
        // the whole picture's row sample packed as crop_rows.frag reads it back
        val win = Capture(frameW, frameH, intArrayOf(x, y, side, side), false, 240).crop(f)
        ring.put(frameNo, x, y, side, win, rowSample(f.y))
        placed++
    }

    /** every strideth column of every rowStepth row of the whole picture (crop_rows.frag) */
    private fun rowSample(y: Plane): ByteArray {
        val stride = Windows.ROW_STRIDE
        val cols = (frameW + stride - 1) / stride
        val pitch = ((cols + 3) / 4) * 4
        val rows = (frameH + rowStep - 1) / rowStep
        val out = ByteArray(pitch * rows)
        for (j in 0 until rows) {
            val row = minOf(j * rowStep, frameH - 1)
            val o = j * pitch
            for (k in 0 until cols) out[o + k] = y[minOf(k * stride, frameW - 1), row].toByte()
        }
        return out
    }

    /**
     * The room's background, built from frames OUTSIDE the serve -- what the phone now builds
     * between serves, so that a capture decodes nothing of the span at all. Fed here from the
     * clip's own frames away from the flight, which is the same thing the phone does with
     * keyframes from the seconds before.
     */
    val scene = SceneBackground(frameW, frameH, SceneBackground.stripsFor(frameH))
    fun seeRoom(f: Yuv, frameNo: Long) {
        scene.addWhole(Capture(frameW, frameH, intArrayOf(0, 0, frameW, frameH), false, 240).crop(f), frameNo)
    }

    /**
     * How far the room's background is from what the chip saw, on the windows' outer edges --
     * the same check LivePipeline makes before trusting it. The ball is in the middle of its
     * window; the edges are the room. A camera that has been moved since the background was
     * built shows up here and nowhere else cheap.
     */
    private fun edgeRms(taken: WindowRing.Taken, rect: IntArray, back: ByteArray, frames: Int): Double {
        val edge = 6
        val diffs = IntArray(1 shl 17)
        var n = 0; var looked = 0
        for (i in 0 until frames step maxOf(1, frames / 8)) {
            val at = taken.at.getOrNull(i) ?: continue
            val win = taken.pixels.getOrNull(i) ?: continue
            val side = taken.sides[i]
            val x0 = at[0] - rect[0]; val y0 = at[1] - rect[1]
            for (y in 0 until side) {
                val cy = y0 + y
                if (cy < 0 || cy >= rect[3]) continue
                val top = y < edge || y >= side - edge
                for (x in 0 until side) {
                    if (!top && x >= edge && x < side - edge) continue
                    val cx = x0 + x
                    if (cx < 0 || cx >= rect[2] || n >= diffs.size) continue
                    diffs[n++] = Math.abs((win[y * side + x].toInt() and 255) - (back[cy * rect[2] + cx].toInt() and 255))
                }
            }
            if (++looked >= 8) break
        }
        // the middle difference, not the average of the squares: an arm across one edge, or the
        // ball clipping a corner, is a handful of pixels that an rms chases and a median does
        // not. Over seven recorded serves the rms ran to 24 where the median stays under 6.
        return if (n < 2000) -1.0 else Windows.medianOf(diffs, n).toDouble()
    }

    /** how a serve's capture came out of the ring, as the phone's cutFromRing would report it */
    class Built(val builder: Windows.Builder?, val note: String, val fitted: Int, val need: Int, val worst: Int)

    /**
     * The capture built from the ring, exactly as LivePipeline.cutFromRing builds it: the
     * capture's own window cut out of the live one round where the ball really was, the row
     * sample mapped onto the crop's rows, and the whole crop only for the frames the background
     * is medianed from.
     */
    fun build(rect: IntArray, tracks: List<List<FloatArray>>, crops: List<ByteArray>, firstFrame: Long,
              useScene: Boolean = System.getenv("SCENE") != null): Built {
        // the frames the engine will actually measure: the serve's own track. A capture covers
        // more than that -- the near tracks, and the frames offsets() fills in on either side --
        // and a window missing out there costs nothing, so counting it as a miss is what turned
        // a serve down that was covered where it mattered.
        val main = tracks.firstOrNull() ?: emptyList()
        // and not the first frames after the hit: the ball is merged with the bat there, the
        // live track has not settled, and the engine throws them away itself (skipAfter)
        val lo = (main.minOfOrNull { it[0] }?.toInt() ?: 0) + GRACE
        val hi = main.maxOfOrNull { it[0] }?.toInt() ?: -1
        val n = crops.size
        val b = Windows.Builder(rect, n, tracks)
        val fromRoom = if (useScene) scene.crop(rect) else null
        b.given = fromRoom
        val side = b.side
        val wanted = b.backgroundFrames.toHashSet()
        val taken = ring.take(firstFrame, n)
        var need = 0; var got = 0; var worst = 0; var worstOk = 0
        var flightNeed = 0; var flightGot = 0
        val debug = System.getenv("RINGDEBUG") != null
        val misses = ArrayList<Int>()
        for (i in 0 until n) {
            val place = b.placeOf(i)
            val at = taken.at[i]
            var win: ByteArray? = null
            val onFlight = i in lo..hi
            if (place != null) {
                need++
                if (onFlight) flightNeed++
                if (at != null) {
                    val from = taken.sides[i]
                    val dx = rect[0] + place[0] - at[0]; val dy = rect[1] + place[1] - at[1]
                    val d = maxOf(Math.abs(dx - (from - side) / 2), Math.abs(dy - (from - side) / 2))
                    if (d > worst) worst = d
                    win = taken.pixels[i]?.let { Windows.reCut(it, from, dx, dy, side) }
                    if (win != null && d > worstOk) worstOk = d
                    misses.add(d)
                    if (debug) println("    frame $i: place ${place[0]},${place[1]} at ${at[0]},${at[1]} off $d ${if (win == null) "MISSED" else ""}")
                }
                if (win != null) { got++; if (onFlight) flightGot++ }
            }
            b.addParts(taken.rows[i]?.let { Windows.ringRows(it, rect, frameW, frameH, rowStep) },
                win, if (i in wanted) crops[i] else null)
        }
        misses.sort()
        val med = if (misses.isEmpty()) 0 else misses[misses.size / 2]
        val p90 = if (misses.isEmpty()) 0 else misses[(misses.size * 9) / 10]
        val edges = if (fromRoom == null) -1.0 else edgeRms(taken, rect, fromRoom, n)
        val note = (if (fromRoom != null) "scene background (${scene.ready}/${scene.strips} strips, ${scene.taken} readings, edges %.1f, ".format(edges) else "") +
            "ring ${sideUsed}px -> ${side}x$side windows, flight $flightGot of $flightNeed (all $got of $need), " +
            "off centre: median $med, 90th $p90, worst fitted $worstOk, worst $worst; held ${taken.have} of $n"
        return if (flightNeed == 0 || flightGot < flightNeed * 3 / 4) Built(null, "$note: NOT USED", flightGot, flightNeed, worst)
        else Built(b, note, flightGot, flightNeed, worst)
    }
}
