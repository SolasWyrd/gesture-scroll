package com.arti.gesturescroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class GestureAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun dispatchSwipe(up: Boolean): Boolean {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        val startY = if (up) height * 0.72f else height * 0.28f
        val endY = if (up) height * 0.28f else height * 0.72f
        val path = Path().apply {
            moveTo(width * 0.50f, startY)
            lineTo(width * 0.50f, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 260))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun dispatchDoubleTap(): Boolean {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        val firstTap = tapGesture(width * 0.48f, height * 0.47f)
        return dispatchGesture(
            firstTap,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    mainHandler.postDelayed({
                        dispatchGesture(
                            tapGesture(width * 0.48f, height * 0.47f),
                            null,
                            null,
                        )
                    }, 80)
                }
            },
            null,
        )
    }

    private fun tapGesture(x: Float, y: Float): GestureDescription {
        val path = Path().apply { moveTo(x, y) }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 55))
            .build()
    }

    companion object {
        @Volatile
        private var instance: GestureAccessibilityService? = null

        val connected: Boolean
            get() = instance != null

        fun swipeUp(): Boolean = instance?.dispatchSwipe(up = true) ?: false

        fun swipeDown(): Boolean = instance?.dispatchSwipe(up = false) ?: false

        fun like(): Boolean = instance?.dispatchDoubleTap() ?: false
    }
}
