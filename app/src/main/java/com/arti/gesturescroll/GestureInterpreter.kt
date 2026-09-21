package com.arti.gesturescroll

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

internal class GestureInterpreter(
    private val openPalmArmMs: Long = 160L,
    private val openPalmScoreThreshold: Float = 0.62f,
    private val thumbCandidateScore: Float = 0.45f,
    private val thumbConfirmScore: Float = 0.70f,
    private val likeHoldMs: Long = 360L,
    private val swipeMinDurationMs: Long = 110L,
    private val swipeMaxDurationMs: Long = 700L,
    private val swipeDistanceInHandSizes: Float = 0.82f,
    private val swipeMinSpeedInHandSizesPerSecond: Float = 1.35f,
    private val verticalDominanceRatio: Float = 1.45f,
    private val pathConsistencyThreshold: Float = 0.70f,
    private val classificationGraceMs: Long = 120L,
    private val actionLockoutMs: Long = 320L,
    private val releaseStableMs: Long = 180L,
) {
    enum class Action { SWIPE_UP, SWIPE_DOWN, LIKE }

    private enum class State {
        IDLE,
        ARMING_SWIPE,
        TRACKING_SWIPE,
        LIKE_PENDING,
        LOCKED,
    }

    private enum class LockReason { SWIPE, LIKE }

    private data class HandPoint(
        val x: Float,
        val y: Float,
        val scale: Float,
        val timestampMs: Long,
    )

    private val xFilter = OneEuroFilter()
    private val yFilter = OneEuroFilter()

    private var state = State.IDLE
    private var lastHandSeenMs: Long? = null
    private var handMissingSinceMs: Long? = null

    private var armStartedMs = 0L
    private var armOrigin: HandPoint? = null
    private var lastOpenPalmMs = 0L

    private var trackStart: HandPoint? = null
    private var trackLast: HandPoint? = null
    private var cumulativeAbsX = 0f
    private var cumulativeAbsY = 0f

    private var likeStartedMs = 0L
    private var likeAnchor: HandPoint? = null
    private var lastThumbEvidenceMs = 0L
    private var thumbConfirmed = false

    private var lockReason: LockReason? = null
    private var lockStartedMs = 0L
    private var releaseStartedMs: Long? = null
    private var releaseAnchor: HandPoint? = null

    fun onFrame(
        openPalmScore: Float,
        thumbUpScore: Float,
        palmX: Float?,
        palmY: Float?,
        handScale: Float?,
        timestampMs: Long,
    ): Action? {
        val point = buildPoint(palmX, palmY, handScale, timestampMs)
            ?: return onMissingHand(timestampMs)

        lastHandSeenMs = timestampMs
        handMissingSinceMs = null

        if (state != State.LOCKED && thumbUpScore >= thumbCandidateScore) {
            if (state != State.LIKE_PENDING) {
                enterLikePending(point, timestampMs)
            }
            return handleLikePending(point, thumbUpScore, timestampMs)
        }

        return when (state) {
            State.IDLE -> {
                if (isConfidentOpenPalm(openPalmScore, thumbUpScore)) {
                    startSwipeArming(point, timestampMs)
                }
                null
            }

            State.ARMING_SWIPE -> handleSwipeArming(
                point = point,
                openPalmScore = openPalmScore,
                thumbUpScore = thumbUpScore,
                timestampMs = timestampMs,
            )

            State.TRACKING_SWIPE -> handleSwipeTracking(
                point = point,
                openPalmScore = openPalmScore,
                thumbUpScore = thumbUpScore,
                timestampMs = timestampMs,
            )

            State.LIKE_PENDING -> handleLikePending(
                point = point,
                thumbUpScore = thumbUpScore,
                timestampMs = timestampMs,
            )

            State.LOCKED -> handleLocked(
                point = point,
                openPalmScore = openPalmScore,
                thumbUpScore = thumbUpScore,
                timestampMs = timestampMs,
            )
        }
    }

    private fun startSwipeArming(point: HandPoint, timestampMs: Long) {
        state = State.ARMING_SWIPE
        armStartedMs = timestampMs
        armOrigin = point
        lastOpenPalmMs = timestampMs
        clearTracking()
    }

    private fun handleSwipeArming(
        point: HandPoint,
        openPalmScore: Float,
        thumbUpScore: Float,
        timestampMs: Long,
    ): Action? {
        if (isConfidentOpenPalm(openPalmScore, thumbUpScore)) {
            lastOpenPalmMs = timestampMs
        } else if (timestampMs - lastOpenPalmMs > classificationGraceMs) {
            enterIdle()
            return null
        }

        val origin = armOrigin ?: point.also {
            armOrigin = it
            armStartedMs = timestampMs
        }
        val drift = distance(origin, point) / averageScale(origin, point)
        if (drift > ARM_STILLNESS_IN_HAND_SIZES) {
            armOrigin = point
            armStartedMs = timestampMs
            return null
        }

        if (timestampMs - armStartedMs >= openPalmArmMs) {
            state = State.TRACKING_SWIPE
            trackStart = point
            trackLast = point
            cumulativeAbsX = 0f
            cumulativeAbsY = 0f
        }
        return null
    }

    private fun handleSwipeTracking(
        point: HandPoint,
        openPalmScore: Float,
        thumbUpScore: Float,
        timestampMs: Long,
    ): Action? {
        if (isConfidentOpenPalm(openPalmScore, thumbUpScore)) {
            lastOpenPalmMs = timestampMs
        } else if (timestampMs - lastOpenPalmMs > classificationGraceMs) {
            enterIdle()
            return null
        }

        val start = trackStart ?: run {
            state = State.TRACKING_SWIPE
            trackStart = point
            trackLast = point
            return null
        }

        val last = trackLast
        if (last != null) {
            cumulativeAbsX += abs(point.x - last.x)
            cumulativeAbsY += abs(point.y - last.y)
        }
        trackLast = point

        val durationMs = timestampMs - start.timestampMs
        if (durationMs > swipeMaxDurationMs) {
            if (isConfidentOpenPalm(openPalmScore, thumbUpScore)) {
                startSwipeArming(point, timestampMs)
            } else {
                enterIdle()
            }
            return null
        }
        if (durationMs < swipeMinDurationMs) return null

        val scale = averageScale(start, point)
        val dx = point.x - start.x
        val dy = point.y - start.y
        val verticalDistance = abs(dy) / scale
        val horizontalDistance = abs(dx) / scale
        val pathVertical = cumulativeAbsY / scale
        val pathHorizontal = cumulativeAbsX / scale
        val consistency = if (pathVertical > 0f) verticalDistance / pathVertical else 0f
        val speed = verticalDistance / (durationMs / 1_000f)

        val isVerticalEnough =
            verticalDistance >= swipeDistanceInHandSizes &&
                verticalDistance >= horizontalDistance * verticalDominanceRatio &&
                pathVertical >= pathHorizontal * verticalDominanceRatio &&
                consistency >= pathConsistencyThreshold &&
                speed >= swipeMinSpeedInHandSizesPerSecond

        if (!isVerticalEnough) return null

        val action = if (dy < 0f) Action.SWIPE_UP else Action.SWIPE_DOWN
        enterLocked(LockReason.SWIPE, point, timestampMs)
        return action
    }

    private fun enterLikePending(point: HandPoint, timestampMs: Long) {
        state = State.LIKE_PENDING
        likeStartedMs = timestampMs
        likeAnchor = point
        lastThumbEvidenceMs = timestampMs
        thumbConfirmed = false
        clearSwipeCandidate()
    }

    private fun handleLikePending(
        point: HandPoint,
        thumbUpScore: Float,
        timestampMs: Long,
    ): Action? {
        if (thumbUpScore >= thumbCandidateScore) {
            lastThumbEvidenceMs = timestampMs
        }
        if (thumbUpScore >= thumbConfirmScore) {
            thumbConfirmed = true
        }

        val anchor = likeAnchor ?: point.also { likeAnchor = it }
        val drift = distance(anchor, point) / averageScale(anchor, point)
        if (drift > LIKE_MAX_DRIFT_IN_HAND_SIZES) {
            enterIdle()
            return null
        }

        if (timestampMs - lastThumbEvidenceMs > classificationGraceMs) {
            enterIdle()
            return null
        }

        if (
            thumbConfirmed &&
            timestampMs - likeStartedMs >= likeHoldMs &&
            thumbUpScore >= THUMB_TRIGGER_FLOOR
        ) {
            enterLocked(LockReason.LIKE, point, timestampMs)
            return Action.LIKE
        }
        return null
    }

    private fun enterLocked(reason: LockReason, point: HandPoint, timestampMs: Long) {
        state = State.LOCKED
        lockReason = reason
        lockStartedMs = timestampMs
        releaseStartedMs = null
        releaseAnchor = point
        clearSwipeCandidate()
        clearLikeCandidate()
    }

    private fun handleLocked(
        point: HandPoint,
        openPalmScore: Float,
        thumbUpScore: Float,
        timestampMs: Long,
    ): Action? {
        if (timestampMs - lockStartedMs < actionLockoutMs) {
            releaseStartedMs = null
            releaseAnchor = point
            return null
        }

        when (lockReason) {
            LockReason.LIKE -> {
                if (thumbUpScore >= THUMB_RELEASE_SCORE) {
                    releaseStartedMs = null
                    releaseAnchor = point
                    return null
                }
            }

            LockReason.SWIPE -> {
                if (!isConfidentOpenPalm(openPalmScore, thumbUpScore)) {
                    releaseStartedMs = null
                    releaseAnchor = point
                    return null
                }
            }

            null -> Unit
        }

        val anchor = releaseAnchor ?: point.also { releaseAnchor = it }
        val drift = distance(anchor, point) / averageScale(anchor, point)
        if (drift > RELEASE_STILLNESS_IN_HAND_SIZES) {
            releaseAnchor = point
            releaseStartedMs = timestampMs
            return null
        }

        val started = releaseStartedMs ?: timestampMs.also { releaseStartedMs = it }
        if (timestampMs - started >= releaseStableMs) {
            enterIdle()
        }
        return null
    }

    private fun onMissingHand(timestampMs: Long): Action? {
        val missingSince = handMissingSinceMs ?: timestampMs.also {
            handMissingSinceMs = it
        }

        if (
            state == State.LOCKED &&
            timestampMs - lockStartedMs >= actionLockoutMs &&
            timestampMs - missingSince >= releaseStableMs
        ) {
            enterIdle(resetFilters = true)
            return null
        }

        if (timestampMs - missingSince > classificationGraceMs) {
            enterIdle(resetFilters = true)
        }
        return null
    }

    private fun buildPoint(
        palmX: Float?,
        palmY: Float?,
        handScale: Float?,
        timestampMs: Long,
    ): HandPoint? {
        if (palmX == null || palmY == null || handScale == null || handScale <= 0f) {
            return null
        }
        val x = xFilter.filter(palmX, timestampMs)
        val y = yFilter.filter(palmY, timestampMs)
        return HandPoint(
            x = x,
            y = y,
            scale = handScale.coerceAtLeast(MIN_HAND_SCALE),
            timestampMs = timestampMs,
        )
    }

    private fun isConfidentOpenPalm(openPalmScore: Float, thumbUpScore: Float): Boolean =
        openPalmScore >= openPalmScoreThreshold && thumbUpScore < thumbCandidateScore

    private fun enterIdle(resetFilters: Boolean = false) {
        state = State.IDLE
        clearSwipeCandidate()
        clearLikeCandidate()
        lockReason = null
        releaseStartedMs = null
        releaseAnchor = null
        if (resetFilters) {
            xFilter.reset()
            yFilter.reset()
        }
    }

    private fun clearSwipeCandidate() {
        armOrigin = null
        trackStart = null
        trackLast = null
        cumulativeAbsX = 0f
        cumulativeAbsY = 0f
    }

    private fun clearTracking() {
        trackStart = null
        trackLast = null
        cumulativeAbsX = 0f
        cumulativeAbsY = 0f
    }

    private fun clearLikeCandidate() {
        likeAnchor = null
        lastThumbEvidenceMs = 0L
        thumbConfirmed = false
    }

    fun reset() {
        state = State.IDLE
        lastHandSeenMs = null
        handMissingSinceMs = null
        clearSwipeCandidate()
        clearLikeCandidate()
        lockReason = null
        releaseStartedMs = null
        releaseAnchor = null
        xFilter.reset()
        yFilter.reset()
    }

    private fun distance(first: HandPoint, second: HandPoint): Float {
        val dx = second.x - first.x
        val dy = second.y - first.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun averageScale(first: HandPoint, second: HandPoint): Float =
        ((first.scale + second.scale) * 0.5f).coerceAtLeast(MIN_HAND_SCALE)

    private class OneEuroFilter(
        private val minCutoff: Float = 1.7f,
        private val beta: Float = 0.35f,
        private val derivativeCutoff: Float = 1.0f,
    ) {
        private var previousTimestampMs: Long? = null
        private var previousRaw: Float? = null
        private var previousFiltered: Float? = null
        private var previousDerivative = 0f

        fun filter(value: Float, timestampMs: Long): Float {
            val previousTime = previousTimestampMs
            val raw = previousRaw
            val filtered = previousFiltered
            if (previousTime == null || raw == null || filtered == null || timestampMs <= previousTime) {
                previousTimestampMs = timestampMs
                previousRaw = value
                previousFiltered = value
                previousDerivative = 0f
                return value
            }

            val dt = ((timestampMs - previousTime) / 1_000f).coerceAtMost(MAX_FILTER_DT_SECONDS)
            val derivative = (value - raw) / dt
            val derivativeAlpha = alpha(derivativeCutoff, dt)
            val smoothedDerivative =
                derivativeAlpha * derivative + (1f - derivativeAlpha) * previousDerivative
            val cutoff = minCutoff + beta * abs(smoothedDerivative)
            val valueAlpha = alpha(cutoff, dt)
            val smoothed = valueAlpha * value + (1f - valueAlpha) * filtered

            previousTimestampMs = timestampMs
            previousRaw = value
            previousFiltered = smoothed
            previousDerivative = smoothedDerivative
            return smoothed
        }

        fun reset() {
            previousTimestampMs = null
            previousRaw = null
            previousFiltered = null
            previousDerivative = 0f
        }

        private fun alpha(cutoff: Float, dt: Float): Float {
            val tau = 1f / (2f * PI.toFloat() * cutoff.coerceAtLeast(0.001f))
            return 1f / (1f + tau / dt.coerceAtLeast(0.001f))
        }
    }

    private companion object {
        const val MIN_HAND_SCALE = 0.04f
        const val ARM_STILLNESS_IN_HAND_SIZES = 0.30f
        const val LIKE_MAX_DRIFT_IN_HAND_SIZES = 0.38f
        const val RELEASE_STILLNESS_IN_HAND_SIZES = 0.28f
        const val THUMB_TRIGGER_FLOOR = 0.55f
        const val THUMB_RELEASE_SCORE = 0.32f
        const val MAX_FILTER_DT_SECONDS = 0.25f
    }
}
