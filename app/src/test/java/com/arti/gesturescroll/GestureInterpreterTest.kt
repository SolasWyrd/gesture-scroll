package com.arti.gesturescroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GestureInterpreterTest {
    @Test
    fun swipeRequiresStableOpenPalmArming() {
        val interpreter = GestureInterpreter()

        assertNull(frame(interpreter, 0L, y = 0.70f, openPalm = 0.9f))
        assertNull(frame(interpreter, 80L, y = 0.70f, openPalm = 0.9f))
        assertNull(frame(interpreter, 170L, y = 0.70f, openPalm = 0.9f))
        assertNull(frame(interpreter, 260L, y = 0.58f, openPalm = 0.9f))
        assertEquals(
            GestureInterpreter.Action.SWIPE_UP,
            frame(interpreter, 420L, y = 0.48f, openPalm = 0.9f),
        )
    }

    @Test
    fun motionWithoutOpenPalmNeverSwipes() {
        val interpreter = GestureInterpreter()

        assertNull(frame(interpreter, 0L, y = 0.75f))
        assertNull(frame(interpreter, 200L, y = 0.55f))
        assertNull(frame(interpreter, 400L, y = 0.35f))
    }

    @Test
    fun thumbUpPreemptsSwipeEvenWhileHandMovesUp() {
        val interpreter = GestureInterpreter()

        assertNull(frame(interpreter, 0L, y = 0.70f, openPalm = 0.9f))
        assertNull(frame(interpreter, 180L, y = 0.70f, openPalm = 0.9f))
        assertNull(
            frame(
                interpreter,
                240L,
                y = 0.63f,
                openPalm = 0.35f,
                thumbUp = 0.50f,
            ),
        )
        assertNull(
            frame(
                interpreter,
                360L,
                y = 0.54f,
                openPalm = 0.20f,
                thumbUp = 0.82f,
            ),
        )
        assertNull(
            frame(
                interpreter,
                500L,
                y = 0.50f,
                openPalm = 0.10f,
                thumbUp = 0.90f,
            ),
        )
    }

    @Test
    fun stationaryThumbUpTriggersLikeExactlyOnceUntilReleased() {
        val interpreter = GestureInterpreter()

        assertNull(frame(interpreter, 0L, y = 0.50f, thumbUp = 0.75f))
        assertNull(frame(interpreter, 180L, y = 0.50f, thumbUp = 0.85f))
        assertEquals(
            GestureInterpreter.Action.LIKE,
            frame(interpreter, 380L, y = 0.50f, thumbUp = 0.90f),
        )
        assertNull(frame(interpreter, 700L, y = 0.50f, thumbUp = 0.90f))
        assertNull(frame(interpreter, 900L, y = 0.50f, thumbUp = 0.10f, openPalm = 0.9f))
        assertNull(frame(interpreter, 1_100L, y = 0.50f, thumbUp = 0.10f, openPalm = 0.9f))
    }

    @Test
    fun movingThumbUpDoesNotLikeAndDoesNotSwipe() {
        val interpreter = GestureInterpreter()

        assertNull(frame(interpreter, 0L, y = 0.70f, thumbUp = 0.80f))
        assertNull(frame(interpreter, 140L, y = 0.60f, thumbUp = 0.85f))
        assertNull(frame(interpreter, 300L, y = 0.48f, thumbUp = 0.90f))
        assertNull(frame(interpreter, 480L, y = 0.38f, thumbUp = 0.92f))
    }

    @Test
    fun horizontalOpenPalmMotionDoesNotSwipe() {
        val interpreter = GestureInterpreter()

        assertNull(frame(interpreter, 0L, x = 0.40f, y = 0.55f, openPalm = 0.9f))
        assertNull(frame(interpreter, 180L, x = 0.40f, y = 0.55f, openPalm = 0.9f))
        assertNull(frame(interpreter, 300L, x = 0.53f, y = 0.53f, openPalm = 0.9f))
        assertNull(frame(interpreter, 460L, x = 0.66f, y = 0.51f, openPalm = 0.9f))
    }

    @Test
    fun returnMotionAfterSwipeCannotTriggerOppositeSwipeWithoutReleaseAndRearm() {
        val interpreter = GestureInterpreter()

        assertNull(frame(interpreter, 0L, y = 0.70f, openPalm = 0.9f))
        assertNull(frame(interpreter, 180L, y = 0.70f, openPalm = 0.9f))
        assertNull(frame(interpreter, 280L, y = 0.58f, openPalm = 0.9f))
        assertEquals(
            GestureInterpreter.Action.SWIPE_UP,
            frame(interpreter, 430L, y = 0.47f, openPalm = 0.9f),
        )

        assertNull(frame(interpreter, 600L, y = 0.56f, openPalm = 0.9f))
        assertNull(frame(interpreter, 780L, y = 0.66f, openPalm = 0.9f))
        assertNull(frame(interpreter, 980L, y = 0.66f, openPalm = 0.9f))
    }

    @Test
    fun shortClassificationDropoutDuringSwipeIsTolerated() {
        val interpreter = GestureInterpreter()

        assertNull(frame(interpreter, 0L, y = 0.70f, openPalm = 0.9f))
        assertNull(frame(interpreter, 180L, y = 0.70f, openPalm = 0.9f))
        assertNull(frame(interpreter, 240L, y = 0.64f, openPalm = 0.1f))
        assertNull(frame(interpreter, 300L, y = 0.57f, openPalm = 0.9f))
        assertEquals(
            GestureInterpreter.Action.SWIPE_UP,
            frame(interpreter, 460L, y = 0.46f, openPalm = 0.9f),
        )
    }

    private fun frame(
        interpreter: GestureInterpreter,
        timeMs: Long,
        x: Float = 0.50f,
        y: Float,
        openPalm: Float = 0f,
        thumbUp: Float = 0f,
        handScale: Float = 0.14f,
    ): GestureInterpreter.Action? = interpreter.onFrame(
        openPalmScore = openPalm,
        thumbUpScore = thumbUp,
        palmX = x,
        palmY = y,
        handScale = handScale,
        timestampMs = timeMs,
    )
}
