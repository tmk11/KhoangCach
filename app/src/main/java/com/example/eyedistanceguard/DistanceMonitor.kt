package com.example.eyedistanceguard

import kotlin.math.tan

internal const val MIN_CM = 20
internal const val MAX_CM = 60
internal const val DEFAULT_CM = 30
internal const val REFERENCE_CM = 40

internal class AlertTimer {
    private var since: Long? = null
    private var previous: Long? = null
    private var lastAlert: Long? = null

    fun reset() {
        since = null
        previous = null
        lastAlert = null
    }

    fun update(distance: Float, threshold: Int, now: Long): Boolean {
        if (distance >= threshold) {
            reset()
            return false
        }
        // A paused camera or a long gap between frames breaks continuous observation.
        if (previous?.let { now - it > 1000 } == true) reset()
        previous = now
        val start = since ?: now.also { since = it }
        if (now - start < 2000 || lastAlert?.let { now - it < 1500 } == true) return false
        lastAlert = now
        return true
    }
}

internal class CalibrationWindow {
    private data class Sample(val time: Long, val width: Float)
    private val samples = ArrayDeque<Sample>()

    fun reset() = samples.clear()

    fun add(time: Long, width: Float) {
        if (samples.lastOrNull()?.let { time - it.time > 500 } == true) reset()
        while (samples.isNotEmpty() && time - samples.first().time > 1000) samples.removeFirst()
        samples.addLast(Sample(time, width))
    }

    fun scale(now: Long): Float? {
        if (samples.size < 3 || now - samples.last().time > 500 ||
            samples.last().time - samples.first().time < 350) return null
        val widths = samples.map { it.width }.sorted()
        val median = widths[widths.size / 2]
        if (widths.last() - widths.first() > median * 0.10f) return null
        return REFERENCE_CM * median
    }
}

internal val defaultScale = (14.5 / (2 * tan(Math.toRadians(60.0) / 2))).toFloat()
