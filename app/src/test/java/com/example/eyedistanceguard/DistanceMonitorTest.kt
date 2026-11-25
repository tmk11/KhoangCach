package com.example.eyedistanceguard

import org.junit.Assert.*
import org.junit.Test

class DistanceMonitorTest {
    @Test fun yellowTimeDoesNotTriggerImmediateAlert() {
        val timer = AlertTimer()
        for (t in 0L..3000L step 250) assertFalse(timer.update(35f, 30, t))
        for (t in 3250L..5000L step 250) assertFalse(timer.update(29f, 30, t))
        assertTrue(timer.update(29f, 30, 5250))
        for (t in 5500L..6500L step 250) assertFalse(timer.update(29f, 30, t))
        assertTrue(timer.update(29f, 30, 6750))
    }

    @Test fun crossingThresholdMissingFaceAndCameraPausesBreakContinuity() {
        val timer = AlertTimer()
        for (t in 0L..1750L step 250) assertFalse(timer.update(29f, 30, t))
        assertFalse(timer.update(30f, 30, 2000))
        assertFalse(timer.update(29f, 30, 2250))
        assertFalse(timer.update(29f, 30, 3000))
        timer.reset()
        assertFalse(timer.update(29f, 30, 3250))
        assertFalse(timer.update(29f, 30, 4000))
        assertFalse(timer.update(29f, 30, 6500))
    }

    @Test fun stableCalibrationRecoversDistanceForTheSameFace() {
        val window = CalibrationWindow()
        for (t in 0L..500L step 100) window.add(t, 0.25f)
        val scale = requireNotNull(window.scale(600))
        assertEquals(40f, scale / 0.25f, 0.001f)
        assertEquals(25f, scale / 0.40f, 0.001f)
        assertNull(window.scale(1200))
    }

    @Test fun rejectsMovingOrInterruptedCalibration() {
        val window = CalibrationWindow()
        window.add(0, 0.25f)
        window.add(200, 0.27f)
        window.add(400, 0.30f)
        assertNull(window.scale(400))
        window.add(1500, 0.25f)
        assertNull(window.scale(1500))
        window.reset()
        assertNull(window.scale(1600))
    }
}
