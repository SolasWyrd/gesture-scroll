package com.arti.gesturescroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GestureInterpreterTest {
    @Test
    fun upwardMotionDispatchesSwipeUpOnce() {
        val interpreter = GestureInterpreter()

        assertNull(interpreter.onFrame("Open_Palm", 0.9f, 0.70f, 0L))
        assertEquals(
            GestureInterpreter.Action.SWIPE_UP,
            interpreter.onFrame("Open_Palm", 0.9f, 0.52f, 250L),
        )
        assertNull(interpreter.onFrame("Open_Palm", 0.9f, 0.40f, 400L))
    }

    @Test
    fun downwardMotionDispatchesSwipeDown() {
        val interpreter = GestureInterpreter()

        assertNull(interpreter.onFrame("Open_Palm", 0.9f, 0.30f, 0L))
        assertEquals(
            GestureInterpreter.Action.SWIPE_DOWN,
            interpreter.onFrame("Open_Palm", 0.9f, 0.47f, 220L),
        )
    }

    @Test
    fun thumbUpRequiresHoldAndDoesNotRepeatUntilReleased() {
        val interpreter = GestureInterpreter()

        assertNull(interpreter.onFrame("Thumb_Up", 0.95f, 0.5f, 0L))
        assertNull(interpreter.onFrame("Thumb_Up", 0.95f, 0.5f, 300L))
        assertEquals(
            GestureInterpreter.Action.LIKE,
            interpreter.onFrame("Thumb_Up", 0.95f, 0.5f, 360L),
        )
        assertNull(interpreter.onFrame("Thumb_Up", 0.95f, 0.5f, 900L))
        assertNull(interpreter.onFrame("Open_Palm", 0.9f, 0.5f, 1100L))
    }

    @Test
    fun staleMovementWindowStartsNewAnchor() {
        val interpreter = GestureInterpreter()

        assertNull(interpreter.onFrame(null, 0f, 0.75f, 0L))
        assertNull(interpreter.onFrame(null, 0f, 0.40f, 1_000L))
        assertEquals(
            GestureInterpreter.Action.SWIPE_UP,
            interpreter.onFrame(null, 0f, 0.20f, 1_200L),
        )
    }
}
