package com.blokyardimcisi

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as WindowParams
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot

private val MOVE_COLORS = intArrayOf(Opal.MINT, Opal.VIOLET, Opal.PINK)

/** All methods run on the main thread. */
class Overlay(
    private val context: Context,
    private val settings: AppSettings,
    private val onMode: (Mode) -> Unit,
    private val onCalibrate: () -> Unit,
    private val onQuit: () -> Unit,
    private val currentLayout: () -> BoardLayout?,
) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private var screenW = 0
    private var screenH = 0
    private var hints: HintView? = null
    private var pill: LinearLayout? = null
    private var gem: ImageView? = null
    private var resize: ValueAnimator? = null
    private var calibration: View? = null
    private val modeButtons = HashMap<Mode, View>()

    private fun dp(v: Int) = context.dp(v)

    private fun params(w: Int, h: Int, flags: Int) = WindowParams(
        w, h, WindowParams.TYPE_APPLICATION_OVERLAY,
        flags or WindowParams.FLAG_LAYOUT_IN_SCREEN or WindowParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    fun show(width: Int, height: Int) {
        screenW = width
        screenH = height
        val hintView = HintView(context, width, height)
        val hintParams = params(width, height, WindowParams.FLAG_NOT_TOUCHABLE or WindowParams.FLAG_NOT_FOCUSABLE)
        // Android 12+ blocks touches passing through overlays that are more opaque than 0.8.
        hintParams.alpha = 0.8f
        windows.addView(hintView, hintParams)
        hints = hintView
        addPill()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addPill() {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = opalRim(Opal.CARD, dp(26).toFloat(), dp(1))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val lp = params(WindowParams.WRAP_CONTENT, WindowParams.WRAP_CONTENT, WindowParams.FLAG_NOT_FOCUSABLE).apply {
            x = settings.pillX
            y = settings.pillY
        }
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val dragger = View.OnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false
                    view.animate().scaleX(0.93f).scaleY(0.93f).setDuration(70).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (resize == null && (dragging || abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        lp.x = (startX + dx.toInt()).coerceIn(0, (screenW - bar.width).coerceAtLeast(0))
                        lp.y = (startY + dy.toInt()).coerceIn(0, (screenH - bar.height).coerceAtLeast(0))
                        windows.updateViewLayout(bar, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                    if (dragging) dropPill(bar, lp) else view.performClick()
                }
                MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
            }
            true
        }

        val collapsible = ArrayList<View>()
        fun View.wire(description: String, action: () -> Unit) {
            contentDescription = description
            isClickable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                action()
            }
            setOnTouchListener(dragger)
            bar.addView(this)
        }
        fun labelled(label: String, icon: Int, mode: Mode) = TextView(context).apply {
            text = label
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(11), 0, dp(13), 0)
            compoundDrawablePadding = dp(5)
            context.getDrawable(icon)!!.mutate().also {
                it.setBounds(0, 0, dp(17), dp(17))
                setCompoundDrawablesRelative(it, null, null, null)
            }
            wire(label) { onMode(mode) }
            collapsible += this
        }
        fun iconOnly(icon: Int, description: String, action: () -> Unit) = ImageView(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            wire(description, action)
            collapsible += this
        }

        gem = ImageView(context).apply {
            setImageResource(R.drawable.gem)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            wire("Opal. Tap to collapse or expand, drag to move") { toggleBar(bar, lp, collapsible) }
        }
        iconOnly(R.drawable.ic_close, "Close Opal") { onQuit() }.also {
            collapsible -= it // stays reachable while the bar is collapsed
            it.imageTintList = ColorStateList.valueOf(Opal.MUTED)
        }
        modeButtons[Mode.SUGGEST] = labelled("Suggest", R.drawable.ic_suggest, Mode.SUGGEST)
        modeButtons[Mode.AUTO] = labelled("Auto", R.drawable.ic_auto, Mode.AUTO)
        modeButtons[Mode.IDLE] = iconOnly(R.drawable.ic_stop, "Stop") { onMode(Mode.IDLE) }
        iconOnly(R.drawable.ic_tune, "Calibrate") { onCalibrate() }.imageTintList = ColorStateList.valueOf(Opal.MUTED)
        windows.addView(bar, lp)
        pill = bar
        showMode(Mode.IDLE)
        bar.post { if (pill === bar) keepOnScreen(bar, lp) } // it may have been saved while collapsed at the edge
    }

    /** Resizes the window itself, sliding it sideways when the wider bar would leave the screen. */
    private fun toggleBar(bar: LinearLayout, lp: WindowParams, parts: List<View>) {
        if (resize != null) return
        val expanding = parts.first().visibility != View.VISIBLE
        if (!expanding) parts.forEach { part ->
            // Fixed sizes stop the labels from squashing while the window narrows.
            if (part.layoutParams.width <= 0) part.layoutParams = LinearLayout.LayoutParams(part.width, part.height)
        }
        val startWidth = bar.width
        lp.width = startWidth // pinned first, or the window would jump to full size for a frame
        windows.updateViewLayout(bar, lp)
        if (expanding) parts.forEach { it.alpha = 0f; it.visibility = View.VISIBLE }
        bar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val endWidth = if (expanding) bar.measuredWidth else bar.paddingLeft + bar.paddingRight + dp(88)
        val startX = lp.x
        val endX = startX.coerceAtMost((screenW - endWidth).coerceAtLeast(0))

        resize = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                if (pill !== bar) return@addUpdateListener
                val f = it.animatedValue as Float
                lp.width = startWidth + ((endWidth - startWidth) * f).toInt()
                lp.x = startX + ((endX - startX) * f).toInt()
                parts.forEach { part -> part.alpha = if (expanding) f else 1f - f }
                windows.updateViewLayout(bar, lp)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    resize = null
                    if (pill !== bar) return
                    parts.forEach { part ->
                        part.alpha = 1f
                        part.visibility = if (expanding) View.VISIBLE else View.GONE
                    }
                    lp.width = WindowParams.WRAP_CONTENT
                    lp.x = endX
                    windows.updateViewLayout(bar, lp)
                    settings.pillX = lp.x
                }
            })
            start()
        }
    }

    private fun keepOnScreen(bar: View, lp: WindowParams) {
        lp.x = lp.x.coerceIn(0, (screenW - bar.width).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (screenH - bar.height).coerceAtLeast(0))
        windows.updateViewLayout(bar, lp)
        settings.pillX = lp.x
        settings.pillY = lp.y
    }

    /** The pill is captured with the screen, so it must not rest on the board or tray. */
    private fun dropPill(bar: View, lp: WindowParams) {
        currentLayout()?.let { l ->
            val top = l.grid.top * screenH
            val bottom = l.tray.bottom * screenH
            if (lp.y + bar.height > top && lp.y < bottom) lp.y = (top - bar.height).toInt().coerceAtLeast(0)
        }
        keepOnScreen(bar, lp)
    }

    fun showMode(mode: Mode) {
        modeButtons.forEach { (m, view) ->
            val selected = m == mode && m != Mode.IDLE
            view.isSelected = selected
            view.background = if (selected) opalFill(dp(22).toFloat()) else null
            val tint = when {
                selected -> Opal.INK
                m == Mode.IDLE -> if (mode == Mode.IDLE) Opal.MUTED else Opal.PINK
                else -> Opal.TEXT
            }
            when (view) {
                is TextView -> {
                    view.setTextColor(tint)
                    view.compoundDrawableTintList = ColorStateList.valueOf(tint)
                }
                is ImageView -> view.imageTintList = ColorStateList.valueOf(tint)
            }
        }
        gem?.alpha = if (mode == Mode.IDLE) 0.6f else 1f
    }

    fun showPlan(plan: Plan?, reading: Reading?, layout: BoardLayout?) {
        hints?.update(plan, reading, layout)
    }

    fun showCalibration(start: BoardLayout, detect: ((BoardLayout?) -> Unit) -> Unit, done: (BoardLayout?) -> Unit) {
        if (calibration != null) return
        val editor = CalibrationView(context, screenW, screenH, start)
        val root = FrameLayout(context)
        root.addView(editor)
        val close = { result: BoardLayout? ->
            windows.removeView(root)
            calibration = null
            done(result)
        }
        val guide = TextView(context).apply {
            text = "Drag the corners to fit the board and the tray"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Opal.TEXT)
            background = solid(Opal.CARD, dp(16).toFloat())
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(
            guide,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = dp(56) },
        )
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = solid(Opal.CARD, dp(28).toFloat())
            setPadding(dp(6), dp(6), dp(6), dp(6))
            fun add(label: String, primary: Boolean, action: () -> Unit) = addView(TextView(context).apply {
                text = label
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                minHeight = dp(44)
                setPadding(dp(16), 0, dp(16), 0)
                setTextColor(if (primary) Opal.INK else Opal.TEXT)
                background = pressable(
                    if (primary) opalFill(dp(22).toFloat()) else solid(Color.TRANSPARENT, dp(22).toFloat()),
                )
                isClickable = true
                setOnClickListener { action() }
            })
            add("Cancel", false) { close(null) }
            add("Auto-detect", false) {
                detect { found ->
                    if (found != null) editor.setLayout(found)
                    else Toast.makeText(context, "Board not found", Toast.LENGTH_SHORT).show()
                }
            }
            add("Save", true) { close(editor.layout) }
        }
        root.addView(
            buttons,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = dp(40) },
        )
        windows.addView(root, params(screenW, screenH, WindowParams.FLAG_NOT_FOCUSABLE))
        calibration = root
    }

    fun hide() {
        val views = listOfNotNull(hints, pill, calibration)
        pill = null // first, so a cancelled animation no longer touches the window
        resize?.cancel()
        views.forEach(windows::removeView)
        hints = null
        gem = null
        calibration = null
    }
}

@SuppressLint("ViewConstructor")
private class HintView(context: Context, private val screenW: Int, private val screenH: Int) : View(context) {
    private var plan: Plan? = null
    private var reading: Reading? = null
    private var layout: BoardLayout? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Opal.INK
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val origin = IntArray(2)

    fun update(plan: Plan?, reading: Reading?, layout: BoardLayout?) {
        this.plan = plan
        this.reading = reading
        this.layout = layout
        invalidate()
    }

    private fun badge(canvas: Canvas, number: Int, color: Int, x: Float, y: Float, radius: Float) {
        fill.color = color
        canvas.drawCircle(x, y, radius, fill)
        canvas.drawText("$number", x, y + text.textSize * .35f, text)
    }

    override fun onDraw(canvas: Canvas) {
        val p = plan ?: return
        val l = layout ?: return
        val r = reading ?: return
        getLocationOnScreen(origin)
        canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())
        val cellW = l.grid.width * screenW / 8
        val cellH = l.grid.height * screenH / 8
        val left = l.grid.left * screenW
        val top = l.grid.top * screenH
        val corner = cellH * .14f
        text.textSize = cellH * 0.24f

        p.moves.forEachIndexed { index, move ->
            val color = MOVE_COLORS[index]
            fill.color = color and 0x00FFFFFF or (0xC8 shl 24)
            val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
            var label: Pair<Float, Float>? = null
            for (row in 0 until move.piece.height) for (col in 0 until move.piece.width) {
                if (move.piece.mask and bit(row, col) == 0L) continue
                val x = left + (move.col + col) * cellW
                val y = top + (move.row + row) * cellH
                path.addRoundRect(
                    x + cellW * .05f, y + cellH * .05f, x + cellW * .95f, y + cellH * .95f, corner, corner,
                    Path.Direction.CW,
                )
                // The hole keeps the sampled centre of the cell unobstructed, as this overlay is captured too.
                path.addRect(x + cellW * .25f, y + cellH * .25f, x + cellW * .75f, y + cellH * .75f, Path.Direction.CW)
                if (label == null) label = x + cellW * .2f to y + cellH * .2f
            }
            canvas.drawPath(path, fill)
            label?.let { (x, y) -> badge(canvas, index + 1, color, x, y, cellH * .17f) }

            r.tray[move.slot]?.let { source ->
                val radius = cellH * 0.22f
                // Stays outside the sampled tray area.
                badge(canvas, index + 1, color, source.centerX * screenW, l.tray.top * screenH - radius, radius)
            }
        }
    }
}

@SuppressLint("ViewConstructor")
private class CalibrationView(
    context: Context,
    private val screenW: Int,
    private val screenH: Int,
    start: BoardLayout,
) : View(context) {
    private val edges = FloatArray(8) // grid l,t,r,b then tray l,t,r,b, in screen pixels
    private val density = context.resources.displayMetrics.density
    private val origin = IntArray(2)
    private var active = -1
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12 * density
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
    }

    init {
        setBackgroundColor(0x66000000)
        setLayout(start)
    }

    val layout: BoardLayout
        get() = BoardLayout(area(0), area(4))

    private fun area(i: Int) = Area(
        edges[i] / screenW, edges[i + 1] / screenH, edges[i + 2] / screenW, edges[i + 3] / screenH,
    )

    fun setLayout(l: BoardLayout) {
        listOf(l.grid, l.tray).forEachIndexed { n, a ->
            edges[n * 4] = a.left * screenW
            edges[n * 4 + 1] = a.top * screenH
            edges[n * 4 + 2] = a.right * screenW
            edges[n * 4 + 3] = a.bottom * screenH
        }
        invalidate()
    }

    /** Corner n of rect: bit 0 picks right over left, bit 1 picks bottom over top. */
    private fun cornerX(rect: Int, n: Int) = edges[rect * 4 + if (n and 1 == 0) 0 else 2]
    private fun cornerY(rect: Int, n: Int) = edges[rect * 4 + if (n and 2 == 0) 1 else 3]

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        getLocationOnScreen(origin)
        val x = e.x + origin[0]
        val y = e.y + origin[1]
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = (0 until 8).minByOrNull { hypot(cornerX(it / 4, it % 4) - x, cornerY(it / 4, it % 4) - y) }!!
                if (hypot(cornerX(active / 4, active % 4) - x, cornerY(active / 4, active % 4) - y) > 48 * density) {
                    active = -1
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (active >= 0) {
                val rect = active / 4
                val n = active % 4
                val minSize = 48 * density
                val base = rect * 4
                if (n and 1 == 0) edges[base] = x.coerceIn(0f, edges[base + 2] - minSize)
                else edges[base + 2] = x.coerceIn(edges[base] + minSize, screenW.toFloat())
                if (n and 2 == 0) edges[base + 1] = y.coerceIn(0f, edges[base + 3] - minSize)
                else edges[base + 3] = y.coerceIn(edges[base + 1] + minSize, screenH.toFloat())
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = -1
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(origin)
        canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())
        drawRect(canvas, 0, 8, 8, Opal.MINT, "BOARD")
        drawRect(canvas, 1, 3, 1, Opal.VIOLET, "TRAY")
    }

    private fun drawRect(canvas: Canvas, rect: Int, cols: Int, rows: Int, color: Int, name: String) {
        val l = edges[rect * 4]
        val t = edges[rect * 4 + 1]
        val r = edges[rect * 4 + 2]
        val b = edges[rect * 4 + 3]
        line.color = color and 0x00FFFFFF or (0x70 shl 24)
        line.strokeWidth = density
        for (i in 1 until cols) (l + (r - l) * i / cols).let { canvas.drawLine(it, t, it, b, line) }
        for (i in 1 until rows) (t + (b - t) * i / rows).let { canvas.drawLine(l, it, r, it, line) }
        line.color = color
        line.strokeWidth = 2 * density
        canvas.drawRect(l, t, r, b, line)
        caption.color = color
        canvas.drawText(name, l + 18 * density, t - 8 * density, caption)
        for (n in 0 until 4) {
            val grabbed = active == rect * 4 + n
            handle.color = color
            canvas.drawCircle(cornerX(rect, n), cornerY(rect, n), (if (grabbed) 16 else 12) * density, handle)
            handle.color = Opal.INK
            canvas.drawCircle(cornerX(rect, n), cornerY(rect, n), 5 * density, handle)
        }
    }
}
