package com.arti.gesturescroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LowLightFrameEnhancerTest {
    @Test
    fun normalLightDoesNotIncreaseGain() {
        assertEquals(1f, LowLightFrameEnhancer.gainForMeanLuma(100f), 0.001f)
        assertEquals(0, LowLightFrameEnhancer.shadowLiftForMeanLuma(100f))
    }

    @Test
    fun darkFramesReceiveMeaningfulGainAndLift() {
        val gain = LowLightFrameEnhancer.gainForMeanLuma(40f)
        val lift = LowLightFrameEnhancer.shadowLiftForMeanLuma(40f)

        assertTrue(gain >= 2f)
        assertTrue(lift > 0)
    }

    @Test
    fun almostBlackFramesUseBoundedGain() {
        assertEquals(2.4f, LowLightFrameEnhancer.gainForMeanLuma(1f), 0.001f)
        assertEquals(18, LowLightFrameEnhancer.shadowLiftForMeanLuma(0f))
    }
}
