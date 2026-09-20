package com.arti.gesturescroll

internal class GestureInterpreter(
    private val swipeThreshold: Float = 0.14f,
    private val swipeWindowMs: Long = 900L,
    private val swipeCooldownMs: Long = 650L,
    private val likeHoldMs: Long = 350L,
) {
    enum class Action { SWIPE_UP, SWIPE_DOWN, LIKE }

    private var anchorY: Float? = null
    private var anchorAtMs: Long = 0L
    private var cooldownUntilMs: Long = 0L
    private var thumbUpSinceMs: Long? = null
    private var thumbUpConsumed = false

    fun onFrame(
        gestureName: String?,
        gestureScore: Float,
        palmY: Float?,
        timestampMs: Long,
    ): Action? {
        val confidentThumbUp = gestureName == "Thumb_Up" && gestureScore >= 0.70f
        if (confidentThumbUp) {
            anchorY = null
            if (thumbUpConsumed) return null
            val started = thumbUpSinceMs ?: timestampMs.also { thumbUpSinceMs = it }
            if (timestampMs - started >= likeHoldMs) {
                thumbUpConsumed = true
                cooldownUntilMs = timestampMs + swipeCooldownMs
                return Action.LIKE
            }
            return null
        }

        thumbUpSinceMs = null
        thumbUpConsumed = false

        if (palmY == null) {
            anchorY = null
            return null
        }
        if (timestampMs < cooldownUntilMs) {
            anchorY = null
            return null
        }

        val currentAnchor = anchorY
        if (currentAnchor == null || timestampMs - anchorAtMs > swipeWindowMs) {
            anchorY = palmY
            anchorAtMs = timestampMs
            return null
        }

        val delta = palmY - currentAnchor
        val action = when {
            delta <= -swipeThreshold -> Action.SWIPE_UP
            delta >= swipeThreshold -> Action.SWIPE_DOWN
            else -> null
        }
        if (action != null) {
            anchorY = null
            cooldownUntilMs = timestampMs + swipeCooldownMs
        }
        return action
    }

    fun reset() {
        anchorY = null
        thumbUpSinceMs = null
        thumbUpConsumed = false
        cooldownUntilMs = 0L
    }
}
