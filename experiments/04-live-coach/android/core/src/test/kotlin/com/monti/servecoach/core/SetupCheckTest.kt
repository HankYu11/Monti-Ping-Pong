package com.monti.servecoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The setup check's table test on synthetic pictures at the size the phone checks (320 x 180,
 * Y, U, V each full size), coloured the way the camera's YUV (BT.601, full range) would read them.
 * Blue, green and dark blue-grey tables are found; walls and floors of those colours, and things
 * of other colours (a red, brown or black table), are not.
 */
class SetupCheckTest {
    private val W = 320; private val H = 180

    private class Pic(val y: ByteArray, val u: ByteArray, val v: ByteArray)

    private fun yuv(r: Int, g: Int, b: Int): Triple<Int, Int, Int> {
        val y = 0.299 * r + 0.587 * g + 0.114 * b
        val u = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b
        val v = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b
        return Triple(y.roundToInt().coerceIn(0, 255), u.roundToInt().coerceIn(0, 255), v.roundToInt().coerceIn(0, 255))
    }

    /** a room: a grey wall, a darker grey floor, and the regions painted over it, last on top */
    private fun pic(vararg regions: Pair<(Float, Float) -> Boolean, Triple<Int, Int, Int>>): Pic {
        val y = ByteArray(W * H); val u = ByteArray(W * H); val v = ByteArray(W * H)
        for (j in 0 until H) for (i in 0 until W) {
            val fx = (i + 0.5f) / W; val fy = (j + 0.5f) / H
            var c = if (fy > 0.85f) yuv(90, 88, 85) else yuv(150, 148, 145)
            for ((inside, rgb) in regions) if (inside(fx, fy)) c = yuv(rgb.first, rgb.second, rgb.third)
            val p = j * W + i
            y[p] = c.first.toByte(); u[p] = c.second.toByte(); v[p] = c.third.toByte()
        }
        return Pic(y, u, v)
    }

    private fun box(x0: Float, y0: Float, x1: Float, y1: Float): (Float, Float) -> Boolean = { x, y -> x in x0..x1 && y in y0..y1 }
    /** a table seen at an angle: a band whose near edge drops towards the right */
    private val angled: (Float, Float) -> Boolean = { x, y -> x in 0.1f..0.9f && y >= 0.45f && y <= 0.62f + 0.15f * x }

    private val BLUE = Triple(20, 70, 150)
    private val GREEN = Triple(30, 100, 65)          // a table's dark green
    private val BLUE_GREY = Triple(50, 60, 80)       // a dark slate table
    private val GREEN_PAINT = Triple(80, 160, 100)   // a wall or a sports floor
    private val MINT = Triple(150, 200, 160)
    private val RED = Triple(170, 40, 40)
    private val WOOD = Triple(140, 100, 60)
    private val BLACK = Triple(25, 25, 28)

    private fun table(p: Pic) = SetupCheck.findTable(p.y, p.u, p.v, W, H)
    private fun advise(p: Pic, tilt: Float = 0f) = SetupCheck.advise(p.y, p.u, p.v, W, H, tilt)

    // --- the tables

    @Test fun blueTableIsFoundAndLooksGood() {
        val (a, t) = advise(pic(box(0.1f, 0.45f, 0.9f, 0.7f) to BLUE))
        assertEquals(SetupCheck.Kind.GOOD, a.kind)
        assertEquals(SetupCheck.Colour.BLUE, t!!.colour)
        assertEquals(0.2f, t.share, 0.02f)
    }

    @Test fun greenTableIsFoundAndLooksGood() {
        val (a, t) = advise(pic(box(0.1f, 0.45f, 0.9f, 0.7f) to GREEN))
        assertEquals(SetupCheck.Kind.GOOD, a.kind)
        assertEquals(SetupCheck.Colour.GREEN, t!!.colour)
        assertEquals(0.45f, t.y0, 0.02f)
    }

    @Test fun angledGreenTableIsFound() {
        val t = table(pic(angled to GREEN))
        assertNotNull(t); assertEquals(SetupCheck.Colour.GREEN, t!!.colour)
    }

    @Test fun blueGreyTableIsFound() {
        val (a, t) = advise(pic(box(0.1f, 0.45f, 0.9f, 0.7f) to BLUE_GREY))
        assertEquals(SetupCheck.Kind.GOOD, a.kind)
        assertEquals(SetupCheck.Colour.BLUE_GREY, t!!.colour)
    }

    @Test fun greenTableGetsTheSameAdviceAsABlueOne() {
        // too small, too high, a strip along the bottom: the advice doesn't depend on the colour
        for (c in listOf(BLUE, GREEN)) {
            assertEquals(SetupCheck.Kind.FAR, advise(pic(box(0.4f, 0.5f, 0.6f, 0.58f) to c)).first.kind)
            assertEquals(SetupCheck.Kind.TABLE_HIGH, advise(pic(box(0.1f, 0.2f, 0.9f, 0.45f) to c)).first.kind)
            assertEquals(SetupCheck.Kind.TABLE_LOW, advise(pic(box(0.3f, 0.88f, 0.7f, 1f) to c)).first.kind)
        }
    }

    @Test fun theOrderStaysStillLightLevelThenTable() {
        val p = pic(box(0.1f, 0.45f, 0.9f, 0.7f) to GREEN)
        assertEquals(SetupCheck.Kind.MOVING, SetupCheck.advise(p.y, p.u, p.v, W, H, 10f, moving = true).first.kind)
        assertEquals(SetupCheck.Kind.TILTED, advise(p, 10f).first.kind)
        val dark = Pic(ByteArray(W * H) { (p.y[it].toInt() and 0xff).div(4).toByte() }, p.u, p.v)
        assertEquals(SetupCheck.Kind.DARK, advise(dark).first.kind)
    }

    // --- walls and floors of table colours

    @Test fun greenWallIsNotATable() {
        val (a, t) = advise(pic(box(0f, 0f, 1f, 0.7f) to GREEN_PAINT))
        assertNull(t); assertEquals(SetupCheck.Kind.NO_TABLE, a.kind)
    }

    @Test fun blueWallIsNotATable() {
        assertNull(table(pic(box(0f, 0f, 1f, 0.6f) to BLUE)))
    }

    @Test fun greenFloorIsNotATable() {
        assertNull(table(pic(box(0f, 0.65f, 1f, 1f) to GREEN_PAINT)))
        assertNull(table(pic(box(0f, 0.85f, 1f, 1f) to GREEN)))            // just a strip of it, too
    }

    @Test fun blueFloorIsNotATable() {
        assertNull(table(pic(box(0f, 0.6f, 1f, 1f) to BLUE)))
    }

    @Test fun aColourFillingThePictureIsNotATable() {
        assertNull(table(pic(box(0f, 0f, 1f, 1f) to GREEN)))
        assertNull(table(pic(box(0.02f, 0.05f, 0.98f, 0.95f) to BLUE)))   // 85%, edges not touched
    }

    @Test fun greenTableInFrontOfAGreenWallIsStillFound() {
        val t = table(pic(box(0f, 0f, 1f, 0.35f) to GREEN_PAINT, box(0.1f, 0.45f, 0.9f, 0.7f) to GREEN))
        assertNotNull(t); assertEquals(0.45f, t!!.y0, 0.02f)
    }

    @Test fun blueTableOnAGreenFloorIsStillFound() {
        val t = table(pic(box(0f, 0.6f, 1f, 1f) to GREEN_PAINT, box(0.1f, 0.45f, 0.9f, 0.7f) to BLUE))
        assertNotNull(t); assertEquals(SetupCheck.Colour.BLUE, t!!.colour)
        assertEquals(0.7f, t.y1, 0.02f)
    }

    // --- other colours

    @Test fun otherColoursAreNotTables() {
        for (c in listOf(RED, WOOD, BLACK, MINT, Triple(150, 148, 145)))
            assertNull("rgb $c", table(pic(box(0.1f, 0.45f, 0.9f, 0.7f) to c)))
    }

    @Test fun colourOfPixels() {
        fun of(r: Int, g: Int, b: Int) = yuv(r, g, b).let { SetupCheck.colourOf(it.first, it.second, it.third) }
        assertEquals(SetupCheck.Colour.BLUE, of(20, 70, 150))
        assertEquals(SetupCheck.Colour.BLUE, of(0, 60, 140))
        assertEquals(SetupCheck.Colour.GREEN, of(30, 100, 65))
        assertEquals(SetupCheck.Colour.GREEN, of(10, 80, 50))
        assertEquals(SetupCheck.Colour.GREEN, of(40, 120, 90))           // a bluish green
        assertEquals(SetupCheck.Colour.BLUE_GREY, of(50, 60, 80))
        assertEquals(SetupCheck.Colour.BLUE_GREY, of(60, 72, 95))
        assertNull(of(60, 140, 40))      // grass, lime paint: yellow-green
        assertNull(of(150, 200, 160))    // a pale mint wall
        assertNull(of(120, 120, 120))    // grey
        assertNull(SetupCheck.colourOf(110, 131, 126))                   // grey under a cool white balance
        assertNull(of(25, 25, 28))       // black
        assertNull(of(170, 40, 40))      // red
        assertNull(of(140, 100, 60))     // wood
        assertNull(of(200, 190, 170))    // a beige wall
        assertNull(SetupCheck.colourOf(30, 150, 100))                     // blue in deep shadow
    }
}
