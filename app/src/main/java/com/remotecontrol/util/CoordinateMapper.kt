package com.remotecontrol.util

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.gson.JsonObject

/**
 * Maps normalized (0..1) coordinates from the controller
 * to actual screen pixel coordinates on the controlled device.
 */
object CoordinateMapper {

    fun mapToScreen(context: Context, event: JsonObject): JsonObject {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val screenW = metrics.widthPixels.toFloat()
        val screenH = metrics.heightPixels.toFloat()

        val mapped = event.deepCopy()

        if (mapped.has("x")) {
            mapped.addProperty("x", mapped.get("x").asFloat * screenW)
        }
        if (mapped.has("y")) {
            mapped.addProperty("y", mapped.get("y").asFloat * screenH)
        }
        if (mapped.has("x2")) {
            mapped.addProperty("x2", mapped.get("x2").asFloat * screenW)
        }
        if (mapped.has("y2")) {
            mapped.addProperty("y2", mapped.get("y2").asFloat * screenH)
        }

        return mapped
    }
}
