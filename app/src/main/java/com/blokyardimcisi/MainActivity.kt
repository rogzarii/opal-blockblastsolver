package com.blokyardimcisi

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var content: LinearLayout
    private lateinit var chip: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroBody: TextView
    private lateinit var startButton: TextView
    private lateinit var overlayRow: Row
    private lateinit var accessibilityRow: Row
    private lateinit var captureRow: Row
    private lateinit var restrictedHint: TextView

    private class Row(val badge: TextView, val number: String, val state: TextView, val action: TextView?)

    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            isVerticalScrollBarEnabled = false
            addView(content)
        })
        addHeader()
        addHero()
        addPermissions()
        addTuning()
        content.addView(label("No internet access. Everything runs on this phone.", 12f, Opal.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        })
        content.addView(label("made by lxoyw in tiktok", 13f, Opal.VIOLET, medium).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        content.alpha = 0f
        content.translationY = dp(10).toFloat()
        content.animate().alpha(1f).translationY(0f).setDuration(260).start()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun addHeader() {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(20))
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.gem)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(12) })

        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(label("Opal", 28f, Opal.TEXT, Typeface.DEFAULT_BOLD).apply {
            paint.shader = LinearGradient(
                0f, 0f, paint.measureText("Opal"), 0f, Opal.SPECTRUM, null, Shader.TileMode.CLAMP,
            )
            includeFontPadding = false
        })
        names.addView(label("BlockSolver", 13f, Opal.MUTED, medium).apply { letterSpacing = 0.08f })
        row.addView(names, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        chip = label("", 12f, Opal.TEXT, medium).apply { setPadding(dp(12), dp(6), dp(12), dp(6)) }
        row.addView(chip)
        content.addView(row)
    }

    private fun addHero() {
        val hero = card(opalRim(Opal.CARD, dp(22).toFloat(), dp(1)))
        heroTitle = label("", 21f, Opal.TEXT, Typeface.DEFAULT_BOLD)
        heroBody = label("", 14f, Opal.MUTED).apply {
            setPadding(0, dp(6), 0, dp(18))
            setLineSpacing(0f, 1.2f)
        }
        startButton = label("", 16f, Opal.INK, Typeface.DEFAULT_BOLD).apply {
            gravity = Gravity.CENTER
            minHeight = dp(54)
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleCapture() }
        }
        hero.addView(heroTitle)
        hero.addView(heroBody)
        hero.addView(startButton, matchWidth())
    }

    private fun toggleCapture() {
        if (CaptureService.running) {
            stopService(Intent(this, CaptureService::class.java))
            content.postDelayed(::refresh, 300)
        } else {
            @Suppress("DEPRECATION")
            startActivityForResult(
                getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), REQUEST_CAPTURE,
            )
        }
    }

    private fun addPermissions() {
        val box = card(solid(Opal.CARD, dp(22).toFloat()))
        box.addView(sectionTitle("Setup"))
        overlayRow = addRow(box, "1", "Display over apps", "Shows the floating bar and move hints.", "Allow") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        accessibilityRow = addRow(box, "2", "Accessibility", "Lets Auto mode drag the blocks for you.", "Open") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        restrictedHint = label(
            "Greyed out? Go to Settings > Apps > ${getString(R.string.app_name)} > ⋮ > " +
                "Allow restricted settings, then try again.",
            12f, Opal.MUTED,
        ).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = solid(Opal.RAISED, dp(12).toFloat())
            setLineSpacing(0f, 1.15f)
        }
        box.addView(restrictedHint, matchWidth().apply { topMargin = dp(4); bottomMargin = dp(6) })
        captureRow = addRow(box, "3", "Screen capture", "Asked when you tap Start. Choose Entire screen.", null, null)
    }

    private fun addRow(
        parent: LinearLayout, number: String, title: String, body: String, actionText: String?, onAction: (() -> Unit)?,
    ): Row {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val badge = label(number, 13f, Opal.TEXT, Typeface.DEFAULT_BOLD).apply { gravity = Gravity.CENTER }
        row.addView(badge, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) })

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 15f, Opal.TEXT, medium))
        texts.addView(label(body, 12.5f, Opal.MUTED).apply { setPadding(0, dp(2), dp(8), 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val state = label("On", 13f, Opal.MINT, medium)
        row.addView(state)
        val action = actionText?.let {
            label(it, 13f, Opal.TEXT, medium).apply {
                gravity = Gravity.CENTER
                minWidth = dp(68)
                minHeight = dp(40)
                background = pressable(solid(Opal.RAISED, dp(20).toFloat()))
                isClickable = true
                isFocusable = true
                setOnClickListener { onAction?.invoke() }
                row.addView(this)
            }
        }
        parent.addView(row)
        return Row(badge, number, state, action)
    }

    private fun addTuning() {
        val box = card(solid(Opal.CARD, dp(22).toFloat()))
        box.addView(sectionTitle("Auto speed"))
        box.addView(label("Lower is faster. Raise Drag time if blocks get missed.", 12.5f, Opal.MUTED).apply {
            setPadding(0, 0, 0, dp(10))
        })
        addSlider(box, "Drag time", 20, 440, 10, settings.dragMs, { "$it ms" }) { settings.dragMs = it }
        addSlider(box, "Pause between moves", 0, 1000, 10, settings.moveDelayMs, { "$it ms" }) {
            settings.moveDelayMs = it
        }
        addSlider(box, "Vertical offset", 0, 60, 1, (settings.dragOffsetCells * 10).toInt(), { "${it / 10f} cells" }) {
            settings.dragOffsetCells = it / 10f
        }
    }

    private fun addSlider(
        parent: LinearLayout, title: String, min: Int, max: Int, step: Int, value: Int,
        format: (Int) -> String, save: (Int) -> Unit,
    ) {
        val head = LinearLayout(this).apply { setPadding(0, dp(10), 0, dp(2)) }
        head.addView(label(title, 14f, Opal.TEXT), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val shown = label(format(value), 14f, Opal.MINT, medium)
        head.addView(shown)
        parent.addView(head)
        parent.addView(SeekBar(this).apply {
            this.max = (max - min) / step
            progress = (value - min) / step
            contentDescription = title
            setPadding(dp(4), dp(10), dp(4), dp(10))
            progressTintList = ColorStateList.valueOf(Opal.MINT)
            thumbTintList = ColorStateList.valueOf(Opal.TEXT)
            progressBackgroundTintList = ColorStateList.valueOf(Opal.MUTED)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                    val v = min + p * step
                    shown.text = format(v)
                    if (fromUser) save(v)
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }, matchWidth())
    }

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessibilityOk = AutoPlayService.instance != null
        val running = CaptureService.running
        show(overlayRow, overlayOk)
        show(accessibilityRow, accessibilityOk)
        show(captureRow, running)
        captureRow.state.visibility = if (running) View.VISIBLE else View.GONE
        restrictedHint.visibility = if (accessibilityOk) View.GONE else View.VISIBLE

        val (chipText, chipColor) = when {
            running -> "Running" to Opal.MINT
            overlayOk -> "Ready" to Opal.SKY
            else -> "Setup needed" to Opal.PINK
        }
        chip.text = chipText
        chip.setTextColor(chipColor)
        chip.background = solid(Opal.RAISED, dp(14).toFloat())

        heroTitle.text = when {
            running -> "Opal is running"
            overlayOk -> "Ready to solve"
            else -> "Finish setup"
        }
        heroBody.text = when {
            running -> "Open your game, then tap Suggest or Auto on the floating bar."
            overlayOk -> "Tap Start, choose Entire screen, then open your game."
            else -> "Allow Display over apps to continue."
        }
        startButton.text = if (running) "Stop" else "Start"
        startButton.isEnabled = overlayOk
        startButton.alpha = if (overlayOk) 1f else 0.4f
        startButton.setTextColor(if (running) Opal.PINK else Opal.INK)
        val radius = dp(16).toFloat()
        startButton.background = pressable(
            if (running) solid(Opal.RAISED, radius, Opal.PINK, dp(1)) else opalFill(radius),
        )
    }

    private fun show(row: Row, on: Boolean) {
        row.badge.text = if (on) "✓" else row.number
        row.badge.setTextColor(if (on) Opal.INK else Opal.TEXT)
        row.badge.background = solid(if (on) Opal.MINT else Opal.RAISED, dp(15).toFloat())
        row.state.visibility = if (on) View.VISIBLE else View.GONE
        row.action?.visibility = if (on) View.GONE else View.VISIBLE
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            CaptureService.start(this, resultCode, data)
            content.postDelayed(::refresh, 500)
        }
    }

    private fun card(background: android.graphics.drawable.Drawable) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        this.background = background
        setPadding(dp(18), dp(18), dp(18), dp(18))
        content.addView(this, matchWidth().apply { bottomMargin = dp(14) })
    }

    private fun sectionTitle(text: String) = label(text, 16f, Opal.TEXT, Typeface.DEFAULT_BOLD).apply {
        setPadding(0, 0, 0, dp(6))
    }

    private fun label(text: String, size: Float, color: Int, face: Typeface = Typeface.DEFAULT) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = face
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private companion object {
        const val REQUEST_CAPTURE = 1
    }
}
