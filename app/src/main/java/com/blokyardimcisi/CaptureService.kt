package com.blokyardimcisi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import java.nio.ByteOrder

enum class Mode { IDLE, SUGGEST, AUTO }

/** Owns capture and the game loop. All loop state is touched only on the worker thread. */
class CaptureService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("capture").apply { start() }
    private val worker = Handler(workerThread.looper)

    private lateinit var settings: AppSettings
    private lateinit var overlay: Overlay
    private var screenW = 0
    private var screenH = 0

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var frame: Frame? = null
    private var hasFrame = false

    @Volatile private var calibrating = false
    private var layout: BoardLayout? = null
    private var mode = Mode.IDLE
    private var previous: Reading? = null
    private var solvedFor: Reading? = null
    private var stableSince = 0L
    private var lastProgress = 0L
    private var warned = false

    // Auto mode
    private val queue = ArrayDeque<Move>()
    private var expectedBoard: Long? = null
    private var gestureRunning = false
    private var notBefore = 0L
    private var deadline = 0L
    private var gamePackage: String? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!gestureRunning) grab()
            step()
            worker.postDelayed(this, if (mode == Mode.AUTO) AUTO_FRAME_MS else FRAME_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        layout = settings.layout
        overlay = Overlay(this, settings, ::requestMode, ::openCalibration, { stopSelf() }) { layout }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        startInForeground()
        if (data == null || projection != null) {
            if (projection == null) stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val granted = manager.getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), data)
        if (granted == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = granted
        granted.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stopSelf()
        }, ui)
        startCapture(granted)
        running = true
        overlay.show(screenW, screenH)
        worker.post(tick)
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val channel = NotificationChannel("capture", getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = Notification.Builder(this, "capture")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Reading the screen")
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun startCapture(granted: MediaProjection) {
        val metrics = DisplayMetrics()
        getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        // Half resolution is plenty for sampling cells and quarters the per-frame work.
        val w = screenW / 2
        val h = screenH / 2
        frame = Frame(w, h, IntArray(w * h))
        val imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = imageReader
        display = granted.createVirtualDisplay(
            "blok", w, h, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, worker,
        )
    }

    /** Polled, because a static screen produces no new images yet timeouts must still run. */
    private fun grab() {
        val image = reader?.acquireLatestImage() ?: return
        try {
            val f = frame ?: return
            if (calibrating) return // keeps the last frame free of the calibration overlay
            val plane = image.planes[0]
            val ints = plane.buffer.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            val stride = plane.rowStride / 4
            for (y in 0 until f.height) {
                ints.position(y * stride)
                ints.get(f.pixels, y * f.width, f.width)
            }
            for (i in f.pixels.indices) {
                val p = f.pixels[i] // memory order is RGBA, so the int reads ABGR
                f.pixels[i] = (p and 0xFF00FF00.toInt()) or ((p and 0xFF) shl 16) or ((p shr 16) and 0xFF)
            }
            hasFrame = true
        } finally {
            image.close()
        }
    }

    private fun step() {
        if (mode == Mode.IDLE || calibrating || gestureRunning) return
        val now = SystemClock.uptimeMillis()
        if (!hasFrame) return warnIfStuck(now, "No screen image yet. Tap Start again.")
        val f = frame ?: return
        if (mode == Mode.AUTO && AutoPlayService.foregroundPackage != gamePackage) {
            previous = null // paused: never drag over another app, carry on when the game returns
            return
        }
        val l = layout ?: return locateBoard(f)
        val reading = Recognizer.read(f, l)
        val same = reading != null && reading.sameAs(previous)
        if (!same) stableSince = now
        // Fast ticks can see one animation frame twice, so auto mode needs the reading to hold for a moment.
        val stable = same && (mode != Mode.AUTO || now - stableSince >= AUTO_STABLE_MS)
        previous = reading
        if (reading == null) {
            warnIfStuck(now, "Can't read the blocks. Check Calibrate.")
        } else {
            lastProgress = now
            warned = false
        }
        if (stable) {
            if (mode == Mode.SUGGEST) suggest(reading!!, l) else autoStep(reading!!, l, now)
        }
    }

    /** Without this a capture or reading failure looks like the app silently doing nothing. */
    private fun warnIfStuck(now: Long, message: String) {
        if (warned || now - lastProgress < STUCK_MS) return
        warned = true
        toast(message)
    }

    private fun locateBoard(f: Frame) {
        val found = Recognizer.detect(f)
        if (found != null) {
            layout = found
            settings.layout = found
        } else {
            toast("Board not found. Drag the corners to set it.")
            openCalibration()
        }
    }

    private fun suggest(r: Reading, l: BoardLayout) {
        if (r.sameAs(solvedFor)) return // nothing changed, keep the current hint
        solvedFor = r
        val plan = Solver.solve(r.board, r.pieces, budgetMs = AUTO_SOLVE_MS, combo = true)
        if (plan == null && r.pieces.any { it != null }) toast("No move fits")
        ui.post { overlay.showPlan(plan, r, l) }
    }

    private fun autoStep(r: Reading, l: BoardLayout, now: Long) {
        if (now < notBefore) return
        val expected = expectedBoard
        if (expected != null) {
            if (r.board != expected && now < deadline) return // animations may still be finishing
            expectedBoard = null // matched, or gave up waiting: plan from what is on screen
        }
        if (r.pieces.all { it == null }) return // waiting for the next three blocks
        val plan = Solver.solve(r.board, r.pieces, budgetMs = AUTO_SOLVE_MS, combo = true)
        if (plan == null) {
            notBefore = now + NO_MOVE_RETRY_MS // nothing fits; keep watching for a new game
            return
        }
        queue.addAll(plan.moves)
        playNext(r, l)
    }

    /** Plays the whole plan without waiting for the screen; tray slots do not move, and the end board is verified. */
    private fun playNext(r: Reading, l: BoardLayout) {
        val move = queue.removeFirst()
        val source = r.tray[move.slot]
        if (source?.piece != move.piece) {
            queue.clear()
            return
        }
        val service = AutoPlayService.instance ?: return stop("Accessibility is off")

        val cellW = l.grid.width * screenW / 8
        val cellH = l.grid.height * screenH / 8
        val fromX = source.centerX * screenW
        val fromY = source.centerY * screenH
        val toX = fingerFor(fromX, l.grid.left * screenW + (move.col + move.piece.width / 2f) * cellW)
        val toY = fingerFor(
            fromY, l.grid.top * screenH + (move.row + move.piece.height / 2f) * cellH, settings.dragOffsetCells * cellH,
        )
        gestureRunning = true
        val gap = settings.moveDelayMs.toLong()
        service.drag(
            fromX, fromY, toX.coerceIn(0f, screenW - 1f), toY.coerceIn(0f, screenH - 1f), settings.dragMs.toLong(),
        ) { ok ->
            worker.post {
                gestureRunning = false
                previous = null
                if (mode != Mode.AUTO) return@post
                if (!ok) {
                    queue.clear() // e.g. a finger touched the screen; replan from the next reading
                    return@post
                }
                if (queue.isNotEmpty()) {
                    gestureRunning = true // keeps step() out during the gap
                    worker.postDelayed({
                        gestureRunning = false
                        if (mode == Mode.AUTO && queue.isNotEmpty()) playNext(r, l)
                    }, gap)
                    return@post
                }
                expectedBoard = move.boardAfter
                notBefore = SystemClock.uptimeMillis() + gap
                deadline = notBefore + REPLAN_MS
            }
        }
    }

    private fun requestMode(requested: Mode) = worker.post {
        resetLoop()
        if (requested == Mode.AUTO) {
            gamePackage = AutoPlayService.foregroundPackage
            if (AutoPlayService.instance == null) return@post stop("Turn on Accessibility first")
        }
        mode = requested
        ui.post { overlay.showMode(requested) }
    }

    private fun resetLoop() {
        mode = Mode.IDLE
        queue.clear()
        expectedBoard = null
        notBefore = 0
        previous = null
        solvedFor = null
        lastProgress = SystemClock.uptimeMillis()
        warned = false
        ui.post { overlay.showPlan(null, null, null) }
    }

    private fun stop(message: String) {
        resetLoop()
        ui.post { overlay.showMode(Mode.IDLE) }
        toast(message)
    }

    private fun openCalibration() {
        worker.post {
            resetLoop()
            calibrating = true
            val start = layout
            ui.post {
                overlay.showMode(Mode.IDLE)
                overlay.showCalibration(start ?: defaultLayout(), ::detectForCalibration) { saved ->
                    worker.post {
                        if (saved != null) {
                            layout = saved
                            settings.layout = saved
                        }
                        previous = null
                        calibrating = false
                    }
                }
            }
        }
    }

    private fun detectForCalibration(result: (BoardLayout?) -> Unit) = worker.post {
        val found = frame?.takeIf { hasFrame }?.let(Recognizer::detect)
        ui.post { result(found) }
    }

    private fun defaultLayout(): BoardLayout {
        val gridH = 0.888f * screenW / screenH
        val gridBottom = 0.235f + gridH
        return BoardLayout(
            Area(0.056f, 0.235f, 0.944f, gridBottom),
            Area(0.056f, gridBottom + gridH * 0.12f, 0.944f, (gridBottom + gridH * 0.46f).coerceAtMost(1f)),
        )
    }

    private fun toast(text: String) = ui.post { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() {
        running = false
        overlay.hide()
        worker.removeCallbacksAndMessages(null)
        display?.release()
        projection?.stop()
        workerThread.quitSafely()
        worker.post { reader?.close() }
        super.onDestroy()
    }

    companion object {
        private const val FRAME_MS = 250L
        private const val AUTO_FRAME_MS = 16L
        private const val AUTO_STABLE_MS = 80L
        private const val AUTO_SOLVE_MS = 150L
        private const val REPLAN_MS = 600L
        private const val NO_MOVE_RETRY_MS = 500L
        private const val STUCK_MS = 3000L
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        @Volatile var running = false
            private set

        fun start(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, CaptureService::class.java)
                    .putExtra(EXTRA_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data),
            )
        }
    }
}
