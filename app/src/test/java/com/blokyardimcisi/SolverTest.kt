package com.blokyardimcisi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SolverTest {
    private val single = Piece.of("#")
    private val checker = (0 until 64).fold(0L) { b, i -> if ((i / 8 + i % 8) % 2 == 0) b or (1L shl i) else b }

    private fun fullRow(r: Int) = 0xFFL shl (r * 8)

    @Test fun clearsRow() {
        assertEquals(0L to 1, Solver.clear(fullRow(3)))
    }

    @Test fun clearsColumn() {
        val column = (0 until 8).fold(0L) { b, r -> b or bit(r, 5) }
        assertEquals(bit(0, 0) to 1, Solver.clear(column or bit(0, 0)))
    }

    @Test fun crossingRowAndColumnBothClearAndCountTwice() {
        val column = (0 until 8).fold(0L) { b, r -> b or bit(r, 2) }
        assertEquals(0L to 2, Solver.clear(fullRow(4) or column))
    }

    @Test fun lastRowAndTopBitDoNotBreakClearing() {
        assertEquals(0L to 1, Solver.clear(fullRow(7)))
        assertEquals(bit(7, 7) to 0, Solver.clear(bit(7, 7)))
    }

    @Test fun holeCountsEnclosedSingleCellsIncludingWalls() {
        assertEquals(1, Solver.holes(bit(2, 3).inv()))
        assertEquals(1, Solver.holes(bit(0, 0).inv()))
        assertEquals(0, Solver.holes((bit(2, 3) or bit(2, 4)).inv()))
        assertEquals(0, Solver.holes(0L))
    }

    @Test fun holesDoNotWrapAroundBoardEdges() {
        // (0,7) and (1,0) are adjacent bits but not neighbours on the board.
        assertEquals(2, Solver.holes((bit(0, 7) or bit(1, 0)).inv()))
    }

    @Test fun roughnessCountsInnerEdgesOnly() {
        assertEquals(0, Solver.roughness(0L))
        assertEquals(0, Solver.roughness(-1L))
        assertEquals(4, Solver.roughness(bit(3, 3)))
        assertEquals(2, Solver.roughness(bit(0, 0)))
    }

    @Test fun choosesTheLineClearingPlacement() {
        val plan = Solver.solve(fullRow(0) and bit(0, 7).inv(), listOf(single, null, null))!!
        val move = plan.moves.single()
        assertEquals(0 to 7, move.row to move.col)
        assertEquals(1, move.linesCleared)
        assertEquals(0L, move.boardAfter)
    }

    @Test fun orderMattersWhenClearingMakesRoom() {
        // The domino only fits once the single has cleared row 0.
        val b = (checker and fullRow(0).inv()) or (fullRow(0) and bit(0, 7).inv())
        val plan = Solver.solve(b, listOf(Piece.of("##"), single, null))!!
        assertEquals(listOf(1, 0), plan.moves.map { it.slot })
        replay(b, plan)
    }

    @Test fun triesAllThreePieces() {
        val plan = Solver.solve(0L, listOf(Piece.of("###"), Piece.of("##", "##"), Piece.of("#", "#")))!!
        assertEquals(setOf(0, 1, 2), plan.moves.map { it.slot }.toSet())
        replay(0L, plan)
    }

    @Test fun placesWhatFitsWhenNotEverythingDoes() {
        val big = Piece.of("#####", "#####", "#####", "#####", "#####")
        val plan = Solver.solve(checker, listOf(big, single, null))!!
        assertEquals(listOf(1), plan.moves.map { it.slot })
    }

    @Test fun returnsNullWhenNothingFits() {
        assertNull(Solver.solve(checker, listOf(Piece.of("##"), Piece.of("#", "#"), null)))
        assertNull(Solver.solve(0L, listOf(null, null, null)))
    }

    @Test fun prefersTidyBoardWithoutHoles() {
        val after = Solver.solve(0L, listOf(single, null, null))!!.moves.single().boardAfter
        assertEquals(0, Solver.holes(after))
        assertEquals(2, Solver.roughness(after)) // a corner exposes the fewest edges
    }

    @Test fun takesBothClearsForTheCombo() {
        val b = (fullRow(0) and bit(0, 0).inv()) or (fullRow(7) and bit(7, 0).inv())
        val plan = Solver.solve(b, listOf(single, single, null))!!
        assertTrue(plan.moves.all { it.linesCleared == 1 })
    }

    @Test fun comboModeKeepsEveryMoveClearing() {
        val b = listOf(0, 3, 7).fold(0L) { acc, r -> acc or (fullRow(r) and bit(r, 0).inv()) }
        val plan = Solver.solve(b, listOf(single, single, single), combo = true)!!
        assertEquals(listOf(1, 1, 1), plan.moves.map { it.linesCleared })
        replay(b, plan)
    }

    @Test fun worstCaseEmptyBoardStaysFast() {
        val start = System.nanoTime()
        Solver.solve(0L, listOf(single, single, single))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3000)
    }

    @Test fun survivalDropsAsTheBoardGetsWorse() {
        assertEquals(1f, Solver.survival(0L), 0f)
        assertTrue(Solver.survival(checker) < 0.2f)
    }

    @Test fun bigSpotsFindEvery3x3PositionWithoutWrapping() {
        assertEquals(36, java.lang.Long.bitCount(Solver.bigSpots(0L)))
        val corner = (0 until 3).fold(-1L) { b, r -> b and (0x7L shl (r * 8 + 5)).inv() }
        assertEquals(bit(0, 5), Solver.bigSpots(corner))
        // Three free cells split across the row edge must not count as a run.
        val split = (0 until 3).fold(-1L) { b, r -> b and (0x3L shl (r * 8 + 6)).inv() and (1L shl ((r + 1) * 8)).inv() }
        assertEquals(0L, Solver.bigSpots(split))
    }

    @Test fun lookaheadSurvivesLongerFromCrowdedBoards() {
        fun roundsSurvived(lookahead: Boolean): Int {
            val random = Random(1)
            var total = 0
            repeat(150) {
                var board = Solver.clear((0 until 64).fold(0L) { b, i -> if (random.nextInt(100) < 45) b or (1L shl i) else b }).first
                for (round in 0 until 25) {
                    val tray = List(3) { COMMON_PIECES[random.nextInt(COMMON_PIECES.size)] }
                    val plan = Solver.solve(board, tray, lookahead)
                    if (plan == null || plan.moves.size < 3) break
                    board = plan.moves.last().boardAfter
                    total++
                }
            }
            return total
        }
        val without = roundsSurvived(false)
        val with = roundsSurvived(true)
        println("selfplay rounds without=$without with=$with")
        assertTrue("without=$without with=$with", with > without)
    }

    private fun replay(start: Long, plan: Plan) {
        var b = start
        for (m in plan.moves) {
            val placed = m.piece.mask shl (m.row * 8 + m.col)
            assertEquals("overlap at $m", 0L, b and placed)
            b = Solver.clear(b or placed).first
            assertEquals(m.boardAfter, b)
        }
    }
}
