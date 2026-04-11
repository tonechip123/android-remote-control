package com.remotecontrol.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.google.gson.JsonObject

/**
 * AccessibilityService that receives remote touch events and
 * dispatches them as gestures on the controlled device.
 *
 * Supports: tap, swipe, long press, pinch, back, home, recents
 */
class InputInjectionService : AccessibilityService() {

    companion object {
        private const val TAG = "InputInjection"
        var instance: InputInjectionService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used - we only need gesture dispatch
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "Accessibility service destroyed")
    }

    /**
     * Dispatch a remote input event.
     * Event JSON format:
     * { "action": "tap|swipe|long_press|back|home|recents|pinch",
     *   "x": float, "y": float,
     *   "x2": float, "y2": float,  // for swipe end point
     *   "duration": long,           // gesture duration in ms
     *   "screenWidth": int,         // remote screen dimensions for coordinate mapping
     *   "screenHeight": int }
     */
    fun dispatchRemoteEvent(event: JsonObject) {
        val action = event.get("action")?.asString ?: return

        when (action) {
            "tap" -> {
                val x = event.get("x").asFloat
                val y = event.get("y").asFloat
                performTap(x, y)
            }
            "long_press" -> {
                val x = event.get("x").asFloat
                val y = event.get("y").asFloat
                val duration = event.get("duration")?.asLong ?: 1000L
                performLongPress(x, y, duration)
            }
            "swipe" -> {
                val x1 = event.get("x").asFloat
                val y1 = event.get("y").asFloat
                val x2 = event.get("x2").asFloat
                val y2 = event.get("y2").asFloat
                val duration = event.get("duration")?.asLong ?: 300L
                performSwipe(x1, y1, x2, y2, duration)
            }
            "pinch" -> {
                // Pinch requires API 26+ multi-stroke
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val centerX = event.get("x").asFloat
                    val centerY = event.get("y").asFloat
                    val scale = event.get("scale")?.asFloat ?: 1.0f
                    performPinch(centerX, centerY, scale)
                }
            }
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            else -> Log.w(TAG, "Unknown action: $action")
        }
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun performLongPress(x: Float, y: Float, duration: Long) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun performPinch(centerX: Float, centerY: Float, scale: Float) {
        val offset = 200f
        val builder = GestureDescription.Builder()

        if (scale > 1.0f) {
            // Pinch out (zoom in)
            val path1 = Path().apply {
                moveTo(centerX, centerY)
                lineTo(centerX - offset, centerY - offset)
            }
            val path2 = Path().apply {
                moveTo(centerX, centerY)
                lineTo(centerX + offset, centerY + offset)
            }
            builder.addStroke(GestureDescription.StrokeDescription(path1, 0, 500))
            builder.addStroke(GestureDescription.StrokeDescription(path2, 0, 500))
        } else {
            // Pinch in (zoom out)
            val path1 = Path().apply {
                moveTo(centerX - offset, centerY - offset)
                lineTo(centerX, centerY)
            }
            val path2 = Path().apply {
                moveTo(centerX + offset, centerY + offset)
                lineTo(centerX, centerY)
            }
            builder.addStroke(GestureDescription.StrokeDescription(path1, 0, 500))
            builder.addStroke(GestureDescription.StrokeDescription(path2, 0, 500))
        }

        dispatchGesture(builder.build(), null, null)
    }
}
