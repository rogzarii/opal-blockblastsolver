package com.blokyardimcisi

import android.content.Context

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Extra wait between drags; 0 means the next drag starts as soon as the last one ends. */
    var moveDelayMs: Int
        get() = prefs.getInt("gapMs", 0)
        set(v) = prefs.edit().putInt("gapMs", v).apply()

    var dragMs: Int
        get() = prefs.getInt("dragMs", 60)
        set(v) = prefs.edit().putInt("dragMs", v).apply()

    /** How far the game lifts a block when it is picked up, in board cells. */
    var dragOffsetCells: Float
        get() = prefs.getFloat("dragLiftCells", 2.3f)
        set(v) = prefs.edit().putFloat("dragLiftCells", v).apply()

    var pillX: Int
        get() = prefs.getInt("pillX", 24)
        set(v) = prefs.edit().putInt("pillX", v).apply()

    var pillY: Int
        get() = prefs.getInt("pillY", 120)
        set(v) = prefs.edit().putInt("pillY", v).apply()

    var layout: BoardLayout?
        get() {
            if (!prefs.contains("gridL")) return null
            fun f(k: String) = prefs.getFloat(k, 0f)
            return BoardLayout(
                Area(f("gridL"), f("gridT"), f("gridR"), f("gridB")),
                Area(f("trayL"), f("trayT"), f("trayR"), f("trayB")),
            )
        }
        set(v) {
            val e = prefs.edit()
            if (v == null) {
                listOf("gridL", "gridT", "gridR", "gridB", "trayL", "trayT", "trayR", "trayB").forEach(e::remove)
            } else {
                e.putFloat("gridL", v.grid.left).putFloat("gridT", v.grid.top)
                    .putFloat("gridR", v.grid.right).putFloat("gridB", v.grid.bottom)
                    .putFloat("trayL", v.tray.left).putFloat("trayT", v.tray.top)
                    .putFloat("trayR", v.tray.right).putFloat("trayB", v.tray.bottom)
            }
            e.apply()
        }
}
