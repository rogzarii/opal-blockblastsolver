package com.blokyardimcisi

import java.util.PriorityQueue
import kotlin.random.Random

/** Board is a bitboard: bit (row * 8 + col) set means the cell is filled. */
data class Piece(val mask: Long, val width: Int, val height: Int) {
    companion object {
        /** Rows of '#' and any other char, e.g. of("##", "#."). */
        fun of(vararg rows: String): Piece {
            var mask = 0L
            rows.forEachIndexed { r, row ->
                row.forEachIndexed { c, ch -> if (ch == '#') mask = mask or bit(r, c) }
            }
            return Piece(mask, rows.maxOf { it.length }, rows.size)
        }
    }
}

data class Move(val slot: Int, val row: Int, val col: Int, val piece: Piece, val boardAfter: Long, val linesCleared: Int)

data class Plan(val moves: List<Move>, val score: Int)

fun bit(row: Int, col: Int): Long = 1L shl (row * 8 + col)

// shortcut: shapes are equally likely here, the game's real odds are unknown; weight them if they get measured.
internal val COMMON_PIECES: List<Piece> = listOf(
    "#", "##", "#|#", "###", "#|#|#", "####", "#|#|#|#", "#####", "#|#|#|#|#",
    "##|##", "###|###|###", "###|###", "##|##|##", ".#|#.", "#.|.#",
    "##|#.", "##|.#", "#.|##", ".#|##",
    "###|#..|#..", "###|..#|..#", "#..|#..|###", "..#|..#|###",
    "###|#..", "###|..#", "#..|###", "..#|###", "##|#.|#.", "##|.#|.#", "#.|#.|##", ".#|.#|##",
    "###|.#.", ".#.|###", "#.|##|#.", ".#|##|.#",
    "##.|.##", ".##|##.", "#.|##|.#", ".#|##|#.",
).map(::parsePiece)

private fun parsePiece(s: String) = Piece.of(*s.split('|').toTypedArray())

private fun repeated(times: Int, vararg shapes: String) = shapes.flatMap { s -> List(times) { parsePiece(s) } }

// shortcut: weights are estimated from a few screenshots, where big blocks were frequent; refine with more data.
private val LIKELY_PIECES: List<Piece> = COMMON_PIECES +
    repeated(10, "###|###|###") + repeated(4, "###|###", "##|##|##") +
    repeated(3, "#####", "#|#|#|#|#", "##|##", "###|#..|#..", "###|..#|..#", "#..|#..|###", "..#|..#|###")

object Solver {
    private const val LINE = 100
    private const val MULTI_LINE = 60 // per extra line cleared by one move
    private const val COMBO = 80 // per consecutive clearing move
    private const val EMPTY_CELL = 4
    private const val HOLE = 45
    private const val ROUGH_EDGE = 3
    private const val BIG_SPOT = 6 // per position where a 3x3 still fits
    private const val NO_BIG_SPOT = 150 // penalty when no 3x3 fits anywhere

    // Combo mode: a clearing move outweighs any board tidiness, and unbroken runs outweigh single clears.
    private const val CLEARING_MOVE = 3_000
    private const val STREAK = 2_000 // times the current run of clearing moves
    private const val NEXT_CLEAR = 2_500 // when every sampled next tray could clear at once
    private const val COMBO_FINALISTS = 64

    private const val LEFT_FILE = 0x0101010101010101L
    private const val RIGHT_FILE = LEFT_FILE shl 7
    private val ROWS = LongArray(8) { 0xFFL shl (it * 8) }
    private val COLS = LongArray(8) { LEFT_FILE shl it }

    // Values below won a full-game simulation against the alternatives tried.
    private const val CANDIDATES = 512
    private const val COARSE_SAMPLES = 40 // quick survival estimate for every candidate
    private const val FINALISTS = 32 // candidates that get the full sample set
    private const val SURVIVAL_WEIGHT = 60_000 // safety first: 1% better odds beats clearing several lines
    private const val WORK_LIMIT = 12_000_000 // placement checks per solve, keeps phones responsive
    private val SAMPLE_TRIPLES = Random(7).let { r ->
        List(200) { List(3) { LIKELY_PIECES[r.nextInt(LIKELY_PIECES.size)] } }
    }

    private class Work(var left: Int)

    /** Tries every order and position; prefers plans that place the most pieces, then safe boards. */
    fun solve(
        board: Long, pieces: List<Piece?>, lookahead: Boolean = true, budgetMs: Long = 3_600_000, combo: Boolean = false,
    ): Plan? {
        val stopAt = System.nanoTime() + budgetMs * 1_000_000
        val slots = pieces.indices.filter { pieces[it] != null }
        val best = PriorityQueue<Plan>(CANDIDATES + 1, compareBy { it.score })
        val byBoard = HashMap<Long, Plan>()
        var placedMost = 0
        val current = ArrayList<Move>(slots.size)

        fun keep(b: Long, score: Int) {
            if (current.size > placedMost) {
                placedMost = current.size
                best.clear()
                byBoard.clear()
            }
            if (current.size < placedMost) return
            val same = byBoard[b]
            if (same != null) {
                if (same.score >= score) return
                best.remove(same)
            } else if (best.size == CANDIDATES) {
                if (best.peek()!!.score >= score) return
                byBoard.remove(best.poll()!!.moves.last().boardAfter)
            }
            val plan = Plan(current.toList(), score)
            best += plan
            byBoard[b] = plan
        }

        fun search(b: Long, remaining: List<Int>, gained: Int, streak: Int) {
            var placedAny = false
            for (slot in remaining) {
                val piece = pieces[slot]!!
                val rest = remaining - slot
                for (row in 0..8 - piece.height) for (col in 0..8 - piece.width) {
                    val placed = piece.mask shl (row * 8 + col)
                    if (b and placed != 0L) continue
                    placedAny = true
                    val (after, lines) = clear(b or placed)
                    val newStreak = if (lines > 0) streak + 1 else 0
                    val moveScore = when {
                        lines == 0 -> 0
                        combo -> lines * LINE + (lines - 1) * MULTI_LINE + CLEARING_MOVE + newStreak * STREAK
                        else -> lines * LINE + (lines - 1) * MULTI_LINE + (newStreak - 1) * COMBO
                    }
                    current += Move(slot, row, col, piece, after, lines)
                    search(after, rest, gained + moveScore, newStreak)
                    current.removeAt(current.lastIndex)
                }
            }
            if (!placedAny && current.isNotEmpty()) keep(b, gained + evaluate(b))
        }

        search(board, slots, 0, 0)
        val ranked = best.sortedByDescending { it.score }
        if (!lookahead) return ranked.firstOrNull()
        if (combo) {
            // No survival check: among the best plans, leave a board the next tray can clear on at once.
            var pick = ranked.firstOrNull()
            var top = Int.MIN_VALUE
            for (plan in ranked.take(COMBO_FINALISTS)) {
                if (System.nanoTime() > stopAt) break
                val total = plan.score + (nextClearChance(plan.moves.last().boardAfter) * NEXT_CLEAR).toInt()
                if (total > top) {
                    top = total
                    pick = plan
                }
            }
            return pick
        }

        val work = Work(WORK_LIMIT)
        val shortlist = ArrayList<Pair<Plan, Int>>()
        for (plan in ranked) {
            val odds = survival(plan.moves.last().boardAfter, work, COARSE_SAMPLES)
            if (work.left < 0 || System.nanoTime() > stopAt) break // the remaining candidates had lower scores anyway
            shortlist += plan to plan.score + (odds * SURVIVAL_WEIGHT).toInt()
        }
        shortlist.sortByDescending { it.second }
        var winner = shortlist.firstOrNull()?.first ?: ranked.firstOrNull()
        var top = Int.MIN_VALUE
        for ((plan, _) in shortlist.take(FINALISTS)) {
            val odds = survival(plan.moves.last().boardAfter, work, SAMPLE_TRIPLES.size)
            if (work.left < 0 || System.nanoTime() > stopAt) break
            val total = plan.score + (odds * SURVIVAL_WEIGHT).toInt()
            if (total > top) {
                top = total
                winner = plan
            }
        }
        return winner
    }

    /** Share of sampled next trays holding a block that clears a line right away on this board. */
    private fun nextClearChance(board: Long): Float {
        val samples = SAMPLE_TRIPLES.subList(0, COARSE_SAMPLES)
        return samples.count { tray -> tray.any { clearsLine(board, it) } }.toFloat() / samples.size
    }

    private fun clearsLine(board: Long, piece: Piece): Boolean {
        for (row in 0..8 - piece.height) for (col in 0..8 - piece.width) {
            val placed = piece.mask shl (row * 8 + col)
            if (board and placed != 0L) continue
            val filled = board or placed
            for (i in 0 until 8) if (filled and ROWS[i] == ROWS[i] || filled and COLS[i] == COLS[i]) return true
        }
        return false
    }

    /** Share of sampled next triples that could all be placed on this board. */
    internal fun survival(board: Long): Float = survival(board, Work(Int.MAX_VALUE), SAMPLE_TRIPLES.size)

    private fun survival(board: Long, work: Work, samples: Int): Float =
        SAMPLE_TRIPLES.subList(0, samples).count { canPlaceAll(board, it, 0, work) }.toFloat() / samples

    /** Bit set at each top-left corner where a 3x3 block fits. */
    internal fun bigSpots(board: Long): Long {
        val empty = board.inv()
        val two = empty and ((empty and LEFT_FILE.inv()) ushr 1)
        val three = two and ((two and LEFT_FILE.inv()) ushr 1)
        val twoRows = three and (three ushr 8)
        return twoRows and (twoRows ushr 8)
    }

    private fun canPlaceAll(board: Long, pieces: List<Piece>, used: Int, work: Work): Boolean {
        if (used == 7) return true
        for (i in 0 until 3) {
            if (used and (1 shl i) != 0) continue
            val piece = pieces[i]
            for (row in 0..8 - piece.height) for (col in 0..8 - piece.width) {
                if (--work.left < 0) return false
                val placed = piece.mask shl (row * 8 + col)
                if (board and placed == 0L &&
                    canPlaceAll(clear(board or placed).first, pieces, used or (1 shl i), work)
                ) return true
            }
        }
        return false
    }

    /** Applies full-row/column clearing; a cell on a full row and column counts once. */
    fun clear(board: Long): Pair<Long, Int> {
        var wipe = 0L
        var lines = 0
        for (i in 0 until 8) {
            if (board and ROWS[i] == ROWS[i]) { wipe = wipe or ROWS[i]; lines++ }
            if (board and COLS[i] == COLS[i]) { wipe = wipe or COLS[i]; lines++ }
        }
        return (board and wipe.inv()) to lines
    }

    /** Empty cells whose four neighbours are all filled or outside the board. */
    fun holes(board: Long): Int {
        val empty = board.inv()
        val hasEmptyNeighbour = (empty shl 8) or (empty ushr 8) or
            ((empty and RIGHT_FILE.inv()) shl 1) or ((empty and LEFT_FILE.inv()) ushr 1)
        return java.lang.Long.bitCount(empty and hasEmptyNeighbour.inv())
    }

    /** Number of filled/empty boundaries between adjacent cells; lower is tidier. */
    fun roughness(board: Long): Int {
        val horizontal = (board xor (board ushr 1)) and RIGHT_FILE.inv()
        val vertical = (board xor (board ushr 8)) and ROWS[7].inv()
        return java.lang.Long.bitCount(horizontal) + java.lang.Long.bitCount(vertical)
    }

    fun evaluate(board: Long): Int {
        val spots = java.lang.Long.bitCount(bigSpots(board))
        return java.lang.Long.bitCount(board.inv()) * EMPTY_CELL - holes(board) * HOLE - roughness(board) * ROUGH_EDGE +
            spots * BIG_SPOT - (if (spots == 0) NO_BIG_SPOT else 0)
    }
}
