package com.blokyardimcisi

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable

internal object Opal {
    const val INK = 0xFF0B0B12.toInt()
    const val CARD = 0xFF15151F.toInt()
    const val RAISED = 0xFF20202D.toInt()
    const val TEXT = 0xFFF4F2FA.toInt()
    const val MUTED = 0xFFA3A1B5.toInt()
    const val MINT = 0xFF5EF2D0.toInt()
    const val SKY = 0xFF6FC3FF.toInt()
    const val VIOLET = 0xFFB79CFF.toInt()
    const val PINK = 0xFFFF8FC7.toInt()
    val SPECTRUM = intArrayOf(MINT, SKY, VIOLET, PINK)
}

internal fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

internal fun solid(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
}

internal fun opalFill(radius: Float) = GradientDrawable(GradientDrawable.Orientation.TL_BR, Opal.SPECTRUM).apply {
    cornerRadius = radius
}

/** A solid surface with a thin iridescent edge. */
internal fun opalRim(fill: Int, radius: Float, rim: Int) =
    LayerDrawable(arrayOf(opalFill(radius), solid(fill, radius - rim))).apply { setLayerInset(1, rim, rim, rim, rim) }

internal fun pressable(background: Drawable) = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), background, null)
