package com.blokyardimcisi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RecognizerTest {
    private val page = 0xFF4A6FB5.toInt()
    private val boardColor = 0xFF1F2A55.toInt()
    private val red = 0xFFE53935.toInt()

    /** Draws a simplified game screen, so the logic is checked even without real screenshots. */
    private fun synthetic(
        board: Long, pieces: List<Piece?>,
        page: Int = this.page, boardColor: Int = this.boardColor, red: Int = this.red,
    ): Frame {
        val w = 540
        val h = 1200
        val px = IntArray(w * h) { page }
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
            for (y in y0 until y1) for (x in x0 until x1) px[y * w + x] = c
        }
        val left = 30
        val top = 300
        val cell = 60
        fill(left, top, left + 480, top + 480, boardColor)
        for (r in 0 until 8) for (c in 0 until 8) if (board and bit(r, c) != 0L)
            fill(left + c * cell + 2, top + r * cell + 2, left + (c + 1) * cell - 2, top + (r + 1) * cell - 2, red)
        val tiny = 27
        pieces.forEachIndexed { slot, p ->
            if (p == null) return@forEachIndexed
            val x = left + slot * 160 + 80 - p.width * tiny / 2
            val y = top + 480 + 58 + 82 - p.height * tiny / 2
            for (r in 0 until p.height) for (c in 0 until p.width) if (p.mask and bit(r, c) != 0L)
                fill(x + c * tiny, y + r * tiny, x + (c + 1) * tiny, y + (r + 1) * tiny, red)
        }
        return Frame(w, h, px)
    }

    @Test fun detectsAndReadsSyntheticScreen() {
        val board = bit(0, 0) or bit(3, 4) or bit(7, 7)
        val pieces = listOf(Piece.of("##", "#."), null, Piece.of("#####"))
        val frame = synthetic(board, pieces)
        val layout = assertNotNullLayout(frame)
        val reading = Recognizer.read(frame, layout)!!
        assertEquals(board, reading.board)
        assertEquals(pieces, reading.pieces)
    }

    @Test fun readsLightAndDarkBlockThemes() {
        val board = bit(0, 0) or bit(3, 4) or bit(7, 7)
        val pieces = listOf(Piece.of("##", "#."), null, Piece.of("###"))
        val themes = listOf(
            Triple(0xFFBFFDF0.toInt(), 0xFFD6FFF8.toInt(), 0xFF4FC4AE.toInt()), // mint
            Triple(0xFFFFD9E8.toInt(), 0xFFFFF0F6.toInt(), 0xFFE0407F.toInt()), // bright pink
            Triple(0xFFF5E6C8.toInt(), 0xFFFFF6E0.toInt(), 0xFF1F3A93.toInt()), // dark blocks on cream
        )
        for ((pageColor, cells, block) in themes) {
            val frame = synthetic(board, pieces, pageColor, cells, block)
            val reading = Recognizer.read(frame, assertNotNullLayout(frame))!!
            assertEquals(board, reading.board)
            assertEquals(pieces, reading.pieces)
            val emptyBoard = synthetic(0L, pieces, pageColor, cells, block)
            assertEquals(0L, Recognizer.read(emptyBoard, assertNotNullLayout(emptyBoard))!!.board)
        }
    }

    @Test fun fingerPositionMatchesDragMeasuredOnPhone() {
        assertEquals(645f, fingerFor(855f, 540f), 1f)
        assertEquals(1505f, fingerFor(1788f, 1089f, 274f), 1f)
    }

    @Test fun detectionFailsOnBlankScreen() {
        assertNull(Recognizer.detect(Frame(100, 200, IntArray(20000) { page })))
    }

    /**
     * Real screenshots: put NAME.jpg or NAME.png with NAME.txt in src/test/resources/screenshots.
     * NAME.txt has 8 board rows of '#'/'.', then one block per tray slot separated by a line
     * with "-" ("none" for an empty slot).
     */
    @Test fun readsRealScreenshots() {
        val url = javaClass.getResource("/screenshots")
        assumeTrue("game screenshots are kept out of the repository", url != null)
        val dir = File(url!!.toURI())
        val images = dir.listFiles { f -> f.extension == "png" || f.extension == "jpg" }!!.sorted()
        assertEquals(7, images.size)
        for (png in images) for (frame in loadFrame(png).let { listOf(it, half(it)) }) {
            val lines = File(dir, png.nameWithoutExtension + ".txt").readLines().filter { it.isNotBlank() }
            var board = 0L
            lines.take(8).forEachIndexed { r, row ->
                row.forEachIndexed { c, ch -> if (ch == '#') board = board or bit(r, c) }
            }
            val pieces = lines.drop(8).joinToString("\n").split("\n-\n").map { block ->
                if (block.trim() == "none") null else Piece.of(*block.lines().toTypedArray())
            }
            val reading = Recognizer.read(frame, assertNotNullLayout(frame))
            assertNotNull(png.name, reading)
            assertEquals(png.name, board, reading!!.board)
            assertEquals(png.name, pieces, reading.pieces)
        }
    }

    /** The app captures at half resolution. */
    private fun half(f: Frame): Frame {
        val w = f.width / 2
        val h = f.height / 2
        return Frame(w, h, IntArray(w * h) { f.at(it % w * 2, it / w * 2) })
    }

    // Reflection: the Android compile classpath hides javax.imageio, but the test JVM has it.
    private fun loadFrame(file: File): Frame {
        val image = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, file)
        val type = image.javaClass
        val w = type.getMethod("getWidth").invoke(image) as Int
        val h = type.getMethod("getHeight").invoke(image) as Int
        val int = Int::class.javaPrimitiveType
        val getRgb = type.getMethod("getRGB", int, int, int, int, IntArray::class.java, int, int)
        return Frame(w, h, getRgb.invoke(image, 0, 0, w, h, null, 0, w) as IntArray)
    }

    private fun assertNotNullLayout(frame: Frame): BoardLayout {
        val layout = Recognizer.detect(frame)
        assertNotNull("board not detected", layout)
        return layout!!
    }
}
