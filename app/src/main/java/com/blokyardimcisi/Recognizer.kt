package com.blokyardimcisi

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** ARGB pixels; kept free of Android types so screenshots can be tested on the JVM. */
class Frame(val width: Int, val height: Int, val pixels: IntArray) {
    fun at(x: Int, y: Int) = pixels[y * width + x]
}

/** Rectangle in fractions of the screen, so it survives any capture resolution. */
data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

data class BoardLayout(val grid: Area, val tray: Area)

data class TrayPiece(val piece: Piece, val centerX: Float, val centerY: Float)

data class Reading(val board: Long, val tray: List<TrayPiece?>) {
    val pieces get() = tray.map { it?.piece }
    fun sameAs(other: Reading?) = other != null && board == other.board && pieces == other.pieces
}

/** Measured on the device: a held block moves 1.5 times as far as the finger. */
private const val DRAG_SPEED = 1.5f

/** Finger coordinate that brings a block from [source] to [target]; [lift] is its jump on pick-up. */
fun fingerFor(source: Float, target: Float, lift: Float = 0f) = source + (target - source + lift) / DRAG_SPEED

object Recognizer {
    // Tuned against game screenshots; every value is relative to colour or board size.
    private const val FILLED_BRIGHTNESS = 125
    private const val BACKGROUND_DISTANCE = 45
    private const val PATCH = 0.30f // central part of a cell; hint boxes leave it uncovered
    private const val BORDER_REACH = 0.1f // of a cell, checked on both sides of a cell border
    private const val MIN_CHROMA_GAP = 20 // below this the board has no usable colour difference
    private const val BLOCK_SHARE = 0.6f // of the typical block colour strength in a tray slot
    private const val TRAY_CELL_RATIO = 0.444f // tray cell size / board cell size
    private const val TRAY_GAP = 0.12f // of board height
    private const val TRAY_HEIGHT = 0.34f // of board height, fits a 5-cell piece

    private fun brightness(c: Int) = max(max((c shr 16) and 255, (c shr 8) and 255), c and 255)

    private fun chroma(c: Int) = brightness(c) - minOf((c shr 16) and 255, (c shr 8) and 255, c and 255)

    private fun distance(a: Int, b: Int) =
        abs(((a shr 16) and 255) - ((b shr 16) and 255)) +
            abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))

    /** Returns null when a tray slot cannot be read, e.g. during an animation. */
    fun read(f: Frame, layout: BoardLayout): Reading? {
        val tray = List(3) { readSlot(f, layout, it).getOrElse { return null } }
        return Reading(readBoard(f, layout.grid), tray)
    }

    private fun readBoard(f: Frame, grid: Area): Long {
        val cellW = grid.width * f.width / 8
        val cellH = grid.height * f.height / 8
        val colours = IntArray(64) { i ->
            val cx = grid.left * f.width + (i % 8 + 0.5f) * cellW
            val cy = grid.top * f.height + (i / 8 + 0.5f) * cellH
            patchColour(f, cx, cy, cellW * PATCH, cellH * PATCH)
        }
        val empty = emptyColour(f, grid, colours, cellW, cellH)
        var board = 0L
        for (i in colours.indices) {
            val filled = if (empty == null) brightness(colours[i]) >= FILLED_BRIGHTNESS
            else distance(colours[i], empty) >= BACKGROUND_DISTANCE
            if (filled) board = board or (1L shl i)
        }
        return board
    }

    /**
     * Themes differ in brightness and colour, but neighbouring empty cells run flat into each
     * other while blocks have bevels and gaps between them. The group with the most flat borders
     * is "empty"; ties go to the dullest. Null when nothing can tell them apart but brightness.
     */
    private fun emptyColour(f: Frame, grid: Area, colours: IntArray, cellW: Float, cellH: Float): Int? {
        val groups = ArrayList<IntArray>() // colour, flat borders
        val groupOf = IntArray(64) { i ->
            val known = groups.indexOfFirst { distance(it[0], colours[i]) < BACKGROUND_DISTANCE }
            if (known >= 0) known else { groups += intArrayOf(colours[i], 0); groups.lastIndex }
        }
        if (groups.size == 1) return groups[0][0] // a full board would have cleared, so it is all empty

        val left = grid.left * f.width
        val top = grid.top * f.height
        val reachX = (cellW * BORDER_REACH).toInt().coerceAtLeast(1)
        val reachY = (cellH * BORDER_REACH).toInt().coerceAtLeast(1)
        fun flat(x: Int, y: Int, dx: Int, dy: Int, colour: Int): Boolean {
            val reach = if (dx != 0) reachX else reachY
            return (-reach..reach).all { t ->
                val px = (x + t * dx).coerceIn(0, f.width - 1)
                val py = (y + t * dy).coerceIn(0, f.height - 1)
                distance(f.at(px, py), colour) < BACKGROUND_DISTANCE
            }
        }
        for (i in 0 until 64) {
            val row = i / 8
            val col = i % 8
            val group = groups[groupOf[i]]
            val midX = left + (col + 0.5f) * cellW
            val midY = top + (row + 0.5f) * cellH
            if (col < 7 && groupOf[i + 1] == groupOf[i] &&
                flat((left + (col + 1) * cellW).toInt(), midY.toInt(), 1, 0, group[0])
            ) group[1]++
            if (row < 7 && groupOf[i + 8] == groupOf[i] &&
                flat(midX.toInt(), (top + (row + 1) * cellH).toInt(), 0, 1, group[0])
            ) group[1]++
        }
        val mostFlat = groups.maxOf { it[1] }
        if (mostFlat > 0) return groups.filter { it[1] * 2 >= mostFlat }.minByOrNull { chroma(it[0]) }!![0]
        val dullest = groups.minOf { chroma(it[0]) }
        val vividest = groups.maxOf { chroma(it[0]) }
        return if (vividest - dullest < MIN_CHROMA_GAP) null else groups.first { chroma(it[0]) == dullest }[0]
    }

    private fun patchColour(f: Frame, cx: Float, cy: Float, w: Float, h: Float): Int {
        val x0 = (cx - w / 2).toInt().coerceIn(0, f.width - 1)
        val x1 = (cx + w / 2).toInt().coerceIn(x0, f.width - 1)
        val y0 = (cy - h / 2).toInt().coerceIn(0, f.height - 1)
        val y1 = (cy + h / 2).toInt().coerceIn(y0, f.height - 1)
        var red = 0
        var green = 0
        var blue = 0
        for (y in y0..y1) for (x in x0..x1) {
            val c = f.at(x, y)
            red += (c shr 16) and 255
            green += (c shr 8) and 255
            blue += c and 255
        }
        val n = (x1 - x0 + 1) * (y1 - y0 + 1)
        return (red / n shl 16) or (green / n shl 8) or (blue / n)
    }

    /** Middle value of [value] over the pixels of a slot that differ from its background. */
    private inline fun median(f: Frame, x0: Int, x1: Int, y0: Int, y1: Int, background: Int, value: (Int) -> Int): Int {
        val counts = IntArray(256)
        var total = 0
        for (y in y0..y1) for (x in x0..x1) {
            val c = f.at(x, y)
            if (distance(c, background) >= BACKGROUND_DISTANCE) { counts[value(c)]++; total++ }
        }
        var seen = 0
        for (v in counts.indices) {
            seen += counts[v]
            if (seen * 2 >= total && total > 0) return v
        }
        return 0
    }

    private inline fun patchShare(f: Frame, cx: Float, cy: Float, w: Float, h: Float, test: (Int) -> Boolean): Float {
        val x0 = (cx - w / 2).toInt().coerceIn(0, f.width - 1)
        val x1 = (cx + w / 2).toInt().coerceIn(x0, f.width - 1)
        val y0 = (cy - h / 2).toInt().coerceIn(0, f.height - 1)
        val y1 = (cy + h / 2).toInt().coerceIn(y0, f.height - 1)
        var hit = 0
        for (y in y0..y1) for (x in x0..x1) if (test(f.at(x, y))) hit++
        return hit.toFloat() / ((x1 - x0 + 1) * (y1 - y0 + 1))
    }

    private fun readSlot(f: Frame, layout: BoardLayout, slot: Int): Result<TrayPiece?> {
        val tray = layout.tray
        val slotW = tray.width * f.width / 3
        val x0 = ((tray.left * f.width + slot * slotW).toInt()).coerceIn(0, f.width - 1)
        val x1 = ((x0 + slotW).toInt()).coerceIn(x0, f.width - 1)
        val y0 = (tray.top * f.height).toInt().coerceIn(0, f.height - 1)
        val y1 = (tray.bottom * f.height).toInt().coerceIn(y0, f.height - 1)
        val background = f.at(x0, y0)
        // Relative to the block's own colour, which drops its duller, darker drop shadow in every theme.
        val minChroma = median(f, x0, x1, y0, y1, background, ::chroma) * BLOCK_SHARE
        val minBrightness = median(f, x0, x1, y0, y1, background, ::brightness) * BLOCK_SHARE
        val isBlock = { c: Int ->
            distance(c, background) >= BACKGROUND_DISTANCE && chroma(c) >= minChroma && brightness(c) >= minBrightness
        }

        val cell = layout.grid.width * f.width / 8 * TRAY_CELL_RATIO
        val minRun = (cell * 0.25f).toInt().coerceAtLeast(1) // ignores sparkles and stray pixels
        val columns = IntArray(x1 - x0 + 1)
        val rows = IntArray(y1 - y0 + 1)
        for (y in y0..y1) for (x in x0..x1) if (isBlock(f.at(x, y))) { columns[x - x0]++; rows[y - y0]++ }

        val left = columns.indexOfFirst { it >= minRun }
        val top = rows.indexOfFirst { it >= minRun }
        if (left < 0 || top < 0) return Result.success(null)
        val right = columns.indexOfLast { it >= minRun }
        val bottom = rows.indexOfLast { it >= minRun }

        val width = ((right - left + 1) / cell).roundToInt()
        val height = ((bottom - top + 1) / cell).roundToInt()
        if (width !in 1..5 || height !in 1..5) return Result.failure(IllegalStateException("slot $slot"))

        val cellW = (right - left + 1f) / width
        val cellH = (bottom - top + 1f) / height
        var mask = 0L
        for (r in 0 until height) for (c in 0 until width) {
            val cx = x0 + left + (c + 0.5f) * cellW
            val cy = y0 + top + (r + 0.5f) * cellH
            if (patchShare(f, cx, cy, cellW * 0.5f, cellH * 0.5f, isBlock) >= 0.5f) mask = mask or bit(r, c)
        }
        if (mask == 0L) return Result.failure(IllegalStateException("slot $slot"))
        return Result.success(
            TrayPiece(
                Piece(mask, width, height),
                (x0 + left + (right - left + 1) / 2f) / f.width,
                (y0 + top + (bottom - top + 1) / 2f) / f.height,
            ),
        )
    }

    /** Finds the board as the tallest near-square band that differs from the page background. */
    fun detect(f: Frame): BoardLayout? {
        val probe = (f.width * 0.01f).toInt()
        val minSpan = f.width * 0.75f
        var bandStart = -1
        var lefts = ArrayList<Int>()
        var rights = ArrayList<Int>()
        var best: BoardLayout? = null
        var bestHeight = 0

        fun closeBand(end: Int) {
            if (bandStart >= 0 && lefts.isNotEmpty()) {
                val l = lefts.sorted()[lefts.size / 2]
                val r = rights.sorted()[rights.size / 2]
                val h = end - bandStart
                val ratio = h.toFloat() / (r - l + 1)
                if (ratio in 0.9f..1.1f && h > bestHeight) {
                    bestHeight = h
                    val w = f.width.toFloat()
                    val pageH = f.height.toFloat()
                    val trayTop = (end + h * TRAY_GAP) / pageH
                    best = BoardLayout(
                        Area(l / w, bandStart / pageH, (r + 1) / w, end / pageH),
                        Area(l / w, trayTop, (r + 1) / w, (trayTop + h * TRAY_HEIGHT / pageH).coerceAtMost(1f)),
                    )
                }
            }
            bandStart = -1
            lefts = ArrayList()
            rights = ArrayList()
        }

        for (y in (f.height * 0.1f).toInt() until (f.height * 0.9f).toInt()) {
            val background = f.at(probe, y)
            val differs = { x: Int -> distance(f.at(x, y), background) >= BACKGROUND_DISTANCE / 2 }
            val l = (probe until f.width - 2).firstOrNull { differs(it) && differs(it + 1) && differs(it + 2) }
            val r = (f.width - 1 downTo probe + 2).firstOrNull { differs(it) && differs(it - 1) && differs(it - 2) }
            if (l != null && r != null && r - l >= minSpan) {
                if (bandStart < 0) bandStart = y
                lefts += l
                rights += r
            } else closeBand(y)
        }
        closeBand((f.height * 0.9f).toInt())
        return best
    }
}
