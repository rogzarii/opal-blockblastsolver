package com.blokyardimcisi

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class AutoPlayService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != packageName) {
            foregroundPackage = pkg
        }
    }

    override fun onInterrupt() = Unit

    /** Drags, then rests on the target so the game registers the final position before release. */
    fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long, done: (Boolean) -> Unit) {
        val move = StrokeDescription(
            Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }, 0, durationMs, true,
        )
        send(move) { moved ->
            if (!moved) return@send done(false)
            val rest = Path().apply { moveTo(toX, toY); lineTo(toX, toY) }
            send(move.continueStroke(rest, 0, (durationMs / 4).coerceAtLeast(MIN_REST_MS), false), done)
        }
    }

    private fun send(stroke: StrokeDescription, done: (Boolean) -> Unit) {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) = done(true)
            override fun onCancelled(g: GestureDescription) = done(false)
        }, null)
        if (!accepted) done(false)
    }

    companion object {
        private const val MIN_REST_MS = 10L

        @Volatile var instance: AutoPlayService? = null
            private set

        @Volatile var foregroundPackage: String? = null
            private set
    }
}
